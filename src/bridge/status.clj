(ns bridge.status
  (:require [bridge.artifacts :as artifacts]
            [bridge.evidence :as evidence]
            [bridge.io :as bio]
            [bridge.schema :as schema]))

(def workflow-states #{"draft" "active" "converged" "regressed"})

(defn- explicit-workflow-state [artifact]
  (let [state (some-> (:workflow-state artifact) str)]
    (when (contains? workflow-states state)
      state)))

(defn- changelog-effects [artifact]
  (when (= "verification-changelog" (:artifact artifact))
    (mapv #(some-> (:status-effect %) str) (:entries artifact))))

(defn- state-rank [state]
  ({"draft" 0 "active" 1 "converged" 2 "regressed" 3} state 0))

(defn merge-state [a b]
  (let [sa (str a)
        sb (str b)]
    (cond
      (= "regressed" sa) sa
      (= "regressed" sb) sb
      (> (state-rank sa) (state-rank sb)) sa
      :else sb)))

(defn- evaluation-report? [artifact]
  (= "evaluation-report" (:artifact artifact)))

(defn- evidence-run? [artifact]
  (= "evidence-run" (:artifact artifact)))

(defn- evidence-results [artifacts]
  (vec
   (concat
    (->> artifacts
         (filter evaluation-report?)
         (mapcat :task-results))
    (->> artifacts
         (filter evidence-run?)))))

(defn- evidence-statuses-by-kind [artifacts]
  (reduce (fn [acc result]
            (if-let [kind (some-> (:kind result) evidence/normalize-evidence-kind)]
              (update acc kind (fnil conj []) (schema/normalize-enum-value (:evidence-status result)))
              acc))
          {}
          (evidence-results artifacts)))

(defn- plain-obligations [artifact]
  (mapv (fn [summary]
          {:kind "legacy-missing-obligation"
           :subject (artifacts/subject-of artifact)
           :summary summary
           :reason "Legacy change-intent card has only plain missing-obligations."})
        (:missing-obligations artifact)))

(defn- artifact-obligations [artifact]
  (let [structured (:missing-obligations-structured artifact)]
    (if (seq structured)
      structured
      (plain-obligations artifact))))

(defn- all-obligations [artifacts]
  (->> artifacts
       (filter #(= "change-intent-card" (:artifact %)))
       (mapcat artifact-obligations)
       vec))

(defn- obligation-closure-state [statuses-by-kind obligation]
  (let [required-kinds (map evidence/normalize-evidence-kind (:required-evidence obligation))
        statuses-per-kind (map #(get statuses-by-kind % []) required-kinds)]
    (cond
      (= "policy-evidence-forbidden" (:kind obligation)) "executed-failed"
      (empty? required-kinds) "open"
      (some #(some #{"failed"} %) statuses-per-kind) "executed-failed"
      (every? seq statuses-per-kind)
      (if (every? #(some #{"passed"} %) statuses-per-kind)
        "executed-passed"
        "open")
      :else "open")))

(defn- obligation-closures [artifacts]
  (let [statuses-by-kind (evidence-statuses-by-kind artifacts)]
    (->> (all-obligations artifacts)
         (mapv (fn [obligation]
                 (assoc obligation
                        :closure {:state (obligation-closure-state statuses-by-kind obligation)
                                  :satisfied-by (vec (:required-evidence obligation))
                                  :last-observed-outcome (case (obligation-closure-state statuses-by-kind obligation)
                                                           "executed-passed" "passed"
                                                           "executed-failed" "failed"
                                                           "open" "unknown"
                                                           "unknown")}))))))

(defn- verification-status [artifacts]
  (let [statuses (map #(schema/normalize-enum-value (:evidence-status %)) (evidence-results artifacts))]
    (cond
      (some #{"failed"} statuses) "failed"
      (and (seq statuses) (every? #{"passed"} statuses)) "passed"
      (some #{"partial"} statuses) "partial"
      (or (some #{"passed"} statuses)
          (some #{"unknown"} statuses)) "partial"
      :else "unknown")))

(defn- execution-status [artifacts]
  (let [statuses (map #(schema/normalize-enum-value (:execution-status %)) (evidence-results artifacts))]
    (when (seq statuses)
      (if (every? #{"executed"} statuses)
        "executed"
        "execution-failed"))))

(defn subject-summary [artifacts]
  (let [states (keep explicit-workflow-state artifacts)
        effects (mapcat changelog-effects artifacts)
        base-derived (reduce merge-state "draft" (concat states effects))
        closures (obligation-closures artifacts)
        failed-obligation-count (count (filter #(= "executed-failed" (get-in % [:closure :state])) closures))
        open-obligation-count (count (filter #(= "open" (get-in % [:closure :state])) closures))
        verification-status* (verification-status artifacts)
        execution-status* (execution-status artifacts)
        evidence-present? (seq (evidence-results artifacts))
        workflow-state (cond
                         (or (= "failed" verification-status*)
                             (= "execution-failed" execution-status*)
                             (pos? failed-obligation-count)
                             (some #(= "regressed" %) effects)) "regressed"
                         (and evidence-present?
                              (seq closures)
                              (zero? open-obligation-count)
                              (zero? failed-obligation-count)
                              (= "passed" verification-status*)) "converged"
                         (or evidence-present?
                             (seq closures)) (merge-state base-derived "active")
                         :else base-derived)]
    {:workflow-state workflow-state
     :verification-status verification-status*
     :execution-status execution-status*
     :artifact-count (count artifacts)
     :converged-count (count (filter #(= "converged" %) effects))
     :regressed-count (count (filter #(= "regressed" %) effects))
     :open-obligation-count open-obligation-count
     :failed-obligation-count failed-obligation-count
     :obligation-closures closures
     :subjects (->> artifacts (map artifacts/subject-of) (filter some?) distinct vec)}))

(defn convergence-report
  ([artifact-root]
   (let [items (artifacts/find-artifacts artifact-root)
         by-subject (->> items
                         (group-by artifacts/subject-of)
                         (remove (comp nil? key))
                         (into {}))
         summaries (into {}
                         (map (fn [[subject xs]] [subject (subject-summary xs)]))
                         by-subject)]
     {:artifact-root artifact-root
      :subjects summaries
      :converged-subject-count (count (filter #(= "converged" (get-in % [1 :workflow-state])) summaries))
      :regressed-subject-count (count (filter #(= "regressed" (get-in % [1 :workflow-state])) summaries))}))
  ([profile artifact-root]
   (let [items (artifacts/find-artifacts artifact-root)
         by-subject (artifacts/group-by-canonical-subject profile items)
         summaries (into {}
                         (map (fn [[subject xs]] [subject (subject-summary xs)]))
                         by-subject)]
     {:artifact-root artifact-root
      :subjects summaries
      :converged-subject-count (count (filter #(= "converged" (get-in % [1 :workflow-state])) summaries))
      :regressed-subject-count (count (filter #(= "regressed" (get-in % [1 :workflow-state])) summaries))})))

(defn handoff-completeness
  ([profile]
   (handoff-completeness profile nil))
  ([profile phase-results]
   (let [phase-files (->> (or phase-results (map :output-path (:phases profile)))
                          (keep #(cond
                                   (string? %) %
                                   (map? %) (:path %)
                                   :else nil))
                          vec)
         present (filter bio/exists? phase-files)]
     {:expected-count (count phase-files)
      :present-count (count present)
      :missing-count (- (count phase-files) (count present))
      :complete? (= (count phase-files) (count present))
      :missing-paths (vec (remove bio/exists? phase-files))})))

(defn project-summary [profile]
  (let [artifact-root (get-in profile [:artifact-paths :root])
        coverage (artifacts/coverage profile)
        convergence (convergence-report profile artifact-root)
        ledgers (artifacts/summarize-ledgers artifact-root)
        by-role (evidence/role-coverage profile)
        subsystem-summary (mapv (fn [{:keys [subsystem missing]}]
                                  {:subsystem subsystem
                                   :workflow-state (get-in convergence [:subjects subsystem :workflow-state] "draft")
                                   :missing-artifact-count (count missing)
                                   :open-obligation-count (get-in convergence [:subjects subsystem :open-obligation-count] 0)
                                   :failed-obligation-count (get-in convergence [:subjects subsystem :failed-obligation-count] 0)})
                                (:subsystems coverage))]
    {:project (:project-name profile)
     :artifact-root artifact-root
     :artifact-count (:artifact-count coverage)
     :subsystems subsystem-summary
     :regressed-subjects (->> (:subjects convergence)
                              (keep (fn [[subject summary]]
                                      (when (= "regressed" (:workflow-state summary)) subject)))
                              vec)
     :missing-artifacts (->> (:subsystems coverage)
                             (mapcat (fn [{:keys [subsystem missing]}]
                                       (map (fn [artifact] {:subsystem subsystem :artifact artifact}) missing)))
                             (take 8)
                             vec)
     :evidence-roles by-role
     :ledger-counts (:counts ledgers)
     :handoff (handoff-completeness profile)}))
