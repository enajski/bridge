(ns bridge.workflow
  (:require [bridge.brief :as brief]
            [bridge.change :as change]
            [bridge.evidence :as evidence]
            [bridge.io :as bio]
            [bridge.observe :as observe]
            [bridge.profile :as profile]
            [bridge.schema :as schema]
            [bridge.status :as status]
            [bridge.templates :as templates]))

(def default-phases
  [{:id :analyze
    :action :analyze-change
    :output-extension "yaml"}
   {:id :brief
    :action :generate-brief
    :output-extension "yaml"}
   {:id :observe
    :action :generate-observable
    :output-extension "yaml"}
   {:id :prompt
    :action :render-prompt
    :template "verification-brief-synthesis"
    :output-extension "md"}])

(defn phases [profile]
  (if (seq (:phases profile))
    (:phases profile)
    (mapv #(profile/normalize-phase profile %) default-phases)))

(defn phase-by-id [profile phase-id]
  (first (filter #(= (keyword phase-id) (keyword (:id %))) (phases profile))))

(defn init-phase! [profile phase-id out-path]
  (let [phase (or (phase-by-id profile phase-id)
                  (throw (ex-info "Unknown phase" {:phase phase-id :available (mapv :id (phases profile))})))
        handoff {:phase (name (:id phase))
                 :action (name (:action phase))
                 :project (:project-name profile)
                 :initialized-at (bio/now-iso)
                 :output-path (or out-path (:output-path phase))}]
    (bio/write-data (or out-path (:output-path phase)) handoff)))

(defn run-phase! [profile phase-id {:keys [changed-files policy out-path subject subsystem]}]
  (let [phase (or (phase-by-id profile phase-id)
                  (throw (ex-info "Unknown phase" {:phase phase-id :available (mapv :id (phases profile))})))
        target (or out-path (:output-path phase))]
    (case (:action phase)
      :analyze-change
      (let [artifact (change/initial-change-intent profile policy changed-files (or subject (str (name phase-id) "-change")))]
        (bio/write-data target artifact)
        {:phase (:id phase) :path target :artifact artifact})

      :init-artifact
      (let [artifact-kind (:artifact-kind phase)
            artifact (case artifact-kind
                       "verification-brief" (brief/generate-brief profile policy {:changed-files changed-files
                                                                                   :subsystem subsystem
                                                                                   :subject subject})
                       "observable-contract" (observe/generate-observable-contract profile {:subsystem-name (or (some-> subsystem :name)
                                                                                                                    (some-> (first (profile/match-subsystems profile changed-files)) :name))
                                                                                            :changed-files changed-files
                                                                                            :subject subject})
                       (assoc (schema/stub-artifact artifact-kind)
                         :subject (or subject (:project-name profile))))]
        (bio/write-data target artifact)
        {:phase (:id phase) :path target :artifact artifact})

      :generate-brief
      (let [artifact (brief/generate-brief profile policy {:changed-files changed-files
                                                           :subsystem subsystem
                                                           :subject subject})]
        (bio/write-data target artifact)
        {:phase (:id phase) :path target :artifact artifact})

      :generate-observable
      (let [artifact (observe/generate-observable-contract profile {:subsystem-name (or (some-> subsystem :name)
                                                                                          (some-> (first (profile/match-subsystems profile changed-files)) :name))
                                                                    :changed-files changed-files
                                                                    :subject subject})]
        (bio/write-data target artifact)
        {:phase (:id phase) :path target :artifact artifact})

      :plan-seed
      (let [plan (brief/plan-seed profile policy {:changed-files changed-files
                                                  :change-id subject})]
        (bio/write-data target plan)
        {:phase (:id phase) :path target :plan-seed plan})

      :render-prompt
      (let [rendered (templates/render-from-profile (:template phase)
                                                    profile
                                                    {:subsystem nil
                                                     :files changed-files
                                                     :extra {:subject subject}})]
        (bio/write-text target rendered)
        {:phase (:id phase) :path target})

      :run-evidence
      (let [result (evidence/run-command profile
                                         (:evidence-id phase)
                                         {:out-dir (some-> target java.io.File. .getParent)
                                          :out-path target
                                          :subject subject})]
        {:phase (:id phase) :path target :result result})

      (throw (ex-info "Unsupported phase action" {:phase (:id phase) :action (:action phase)})))))

(defn run-phases!
  ([profile opts]
   (run-phases! profile nil opts))
  ([profile phase-ids {:keys [changed-files policy subject subsystem continue-on-error?]}]
   (let [selected (if (seq phase-ids)
                    (mapv #(phase-by-id profile %) phase-ids)
                    (phases profile))
         finish (fn [results failure]
                  {:results results
                   :failure failure
                   :handoff (status/handoff-completeness profile results)
                   :convergence (status/convergence-report profile (get-in profile [:artifact-paths :root]))})]
     (when (some nil? selected)
       (throw (ex-info "Unknown phase in sequence"
                       {:requested phase-ids
                        :available (mapv :id (phases profile))})))
     (loop [remaining selected
            results []
            failure nil]
       (if (empty? remaining)
         (finish results failure)
         (if (and failure (not continue-on-error?))
           (finish results failure)
           (let [phase (first remaining)
                 outcome (try
                           {:ok? true
                            :result (run-phase! profile (:id phase) {:changed-files changed-files
                                                                     :policy policy
                                                                     :subject subject
                                                                     :subsystem subsystem})}
                           (catch Exception e
                             {:ok? false
                              :failed {:phase (:id phase)
                                       :status :failed
                                       :error {:message (ex-message e)
                                               :data (ex-data e)}}}))]
             (if (:ok? outcome)
               (recur (rest remaining)
                      (conj results (assoc (:result outcome) :status :ok))
                      failure)
               (let [failed (:failed outcome)
                     next-results (conj results failed)]
                 (if continue-on-error?
                   (recur (rest remaining) next-results failed)
                   (finish next-results failed)))))))))))
