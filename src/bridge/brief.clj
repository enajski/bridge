(ns bridge.brief
  (:require [bridge.change :as change]
            [bridge.evidence :as evidence]
            [bridge.io :as bio]
            [bridge.policy :as policy]
            [bridge.profile :as profile]
            [clojure.string :as str]))

(defn- subsystem-concern-classes [profile changed-files]
  (->> (profile/match-glob-rules profile changed-files)
       (keep :concern-class)
       (map #(if (keyword? %) (name %) (str %)))
       distinct
       vec))

(defn- required-evidence-labels [policy-view]
  (->> policy/evidence-keys
       (keep (fn [k]
               (let [level (some-> (get-in policy-view [:required-evidence k]) str)]
                 (when (contains? #{"required" "recommended"} level)
                   (name k)))))
       vec))

(defn- normalize-environment-assumption [s]
  (let [text (str/lower-case (str s))]
    (cond
      (str/includes? text "scheduler") "scheduler"
      (str/includes? text "memory") "memory-model"
      (str/includes? text "transport") "transport"
      (str/includes? text "delivery") "runtime-delivery"
      (str/includes? text "type") "type-system"
      :else "other")))

;; --- B4: model-finiteness detection ---
;; Detect patterns that suggest unbounded state space and recommend
;; bounding constants. This was the decisive factor in raftkvs eval:
;; Bridge spec explored 53K states in 1.69s vs vanilla's 0 states/60s timeout.

(def ^:private finiteness-patterns
  [{:trigger-fn (fn [category assumptions]
                 (or (= category "distributed")
                     (some #(let [t (str/lower-case (str %))]
                              (or (str/includes? t "channel")
                                  (str/includes? t "network")
                                  (str/includes? t "message")
                                  (str/includes? t "queue")
                                  (str/includes? t "inbox")
                                  (str/includes? t "fifo")
                                  (str/includes? t "bag"))) assumptions)))
    :hint {:variable-pattern "message-channel / network inbox"
           :suggested-constant "BufferSize"
           :reason "Unbounded message channels create infinite state space. Bound channel capacity."
           :typical-values "2-10 for model checking, larger for simulation"}}
   {:trigger-fn (fn [category assumptions]
                 (some #(let [t (str/lower-case (str %))]
                          (or (str/includes? t "term")
                              (str/includes? t "epoch")
                              (str/includes? t "ballot")
                              (str/includes? t "round")
                              (str/includes? t "view"))) assumptions))
    :hint {:variable-pattern "term / epoch / ballot / round"
           :suggested-constant "MaxTerm"
           :reason "Monotonically increasing protocol counters create infinite state space."
           :typical-values "2-4 for model checking"}}
   {:trigger-fn (fn [category assumptions]
                 (some #(let [t (str/lower-case (str %))]
                          (or (str/includes? t "log")
                              (str/includes? t "append")
                              (str/includes? t "sequence")
                              (str/includes? t "history"))) assumptions))
    :hint {:variable-pattern "log / append-only sequence"
           :suggested-constant "MaxLogLen"
           :reason "Growing log/sequence variables create infinite state space."
           :typical-values "3-5 for model checking"}}
   {:trigger-fn (fn [category assumptions]
                 (some #(let [t (str/lower-case (str %))]
                          (or (str/includes? t "commit")
                              (str/includes? t "applied")
                              (str/includes? t "progress"))) assumptions))
    :hint {:variable-pattern "commit-index / applied-index"
           :suggested-constant "MaxCommitIndex"
           :reason "Monotonically advancing index variables need upper bounds."
           :typical-values "3-5 for model checking"}}
   {:trigger-fn (fn [category assumptions]
                 (some #(let [t (str/lower-case (str %))]
                          (or (str/includes? t "counter")
                              (str/includes? t "stream")
                              (str/includes? t "monotonic")
                              (str/includes? t "increment"))) assumptions))
    :hint {:variable-pattern "counter / stream counter"
           :suggested-constant "MaxCounter"
           :reason "Unbounded counters create infinite state space."
           :typical-values "3-5 for model checking"}}])

(defn- detect-finiteness-hints [category assumptions]
  (->> finiteness-patterns
       (filter #((:trigger-fn %) category assumptions))
       (mapv :hint)))

(defn- preferred-evidence-for-family [mechanism-family]
  (case (some-> mechanism-family str str/lower-case)
    "trace-conversion" ["trace-validation"]
    "verification-harness" ["trace-validation"]
    "spectrace-generation" ["model-check" "trace-validation"]
    "verification-orchestration" ["model-check" "trace-validation"]
    "tooling-config" ["model-check" "trace-validation"]
    []))

(defn- planned-evidence-for-rule [available rule]
  (->> (concat
         (preferred-evidence-for-family (:mechanism-family rule))
         (when (:formal-module rule) ["model-check"])
         (when (:test-path rule) ["unit-tests"]))
       (map change/normalize-evidence-kind)
       (filter #(contains? available %))
       distinct
       vec))

(defn- verification-method [planned-evidence rule]
  (cond
    (some #{"model-check"} planned-evidence) "model-checkable"
    (some #{"trace-validation" "unit-tests" "property-tests" "runtime-assertions" "differential-tests"} planned-evidence) "test-verifiable"
    (:formal-module rule) "model-checkable"
    (:test-path rule) "test-verifiable"
    (:contract rule) "code-review-only"
    :else "out-of-scope"))

(defn- family-from-rule [profile changed-files subsystem available rule]
  (let [planned-evidence (planned-evidence-for-rule available rule)]
    {:name (or (:mechanism-family rule) (str "change-surface-" (hash (:glob rule))))
     :description (or (:risk-note rule)
                      (:contract rule)
                      (str "Affected by files matching " (:glob rule)))
     :concern-class (or (some-> (:concern-class rule) name) "code-level")
     :verification-method (verification-method planned-evidence rule)
     :affected-paths (->> changed-files
                          (map #(bio/resolve-path (:root-path profile) %))
                          (map #(bio/relativize-path (:root-path profile) %))
                          vec)
     :planned-evidence planned-evidence}))

(defn generate-brief
  ([profile changed-files]
   (generate-brief profile nil {:changed-files changed-files}))
  ([profile policy {:keys [changed-files subsystem subject]}]
   (let [subsystem* (or subsystem
                        (first (profile/match-subsystems profile changed-files))
                        (first (:subsystems profile)))
         subsystem-name (:name subsystem*)
         change-kinds (change/categorize-change-kinds profile changed-files)
         categories (change/infer-categories change-kinds)
         concern-classes (vec (or (seq (subsystem-concern-classes profile changed-files)) ["code-level"]))
         context {:subsystems [subsystem-name]
                  :change-categories categories
                  :system-categories [(or (some-> subsystem* :system-category name) "other")]
                  :risk-class (change/risk-class [subsystem*])
                  :concern-classes concern-classes}
         policy-view (when policy (policy/derive-policy policy context))
         rules (profile/match-glob-rules profile changed-files)
         available-evidence (change/available-evidence-kinds profile [subsystem*])
         mechanism-families (if (seq rules)
                              (mapv #(family-from-rule profile changed-files subsystem* available-evidence %) rules)
                              [{:name "changed-surface"
                                :description "Changed files require manual mechanism-family review."
                                :concern-class (first concern-classes)
                                :verification-method "code-review-only"
                                :affected-paths (mapv #(bio/relativize-path (:root-path profile)
                                                                            (bio/resolve-path (:root-path profile) %))
                                                      changed-files)
                                :planned-evidence []}])
         evidence-summary (required-evidence-labels policy-view)
         role-summary (some->> (:required-roles policy-view) keys (map str) sort vec)]
     {:artifact "verification-brief"
      :subject (or subject subsystem-name (:project-name profile))
      :category (or (some-> subsystem* :system-category name) "other")
      :summary (vec (concat
                      [(str "Changed surface maps to subsystem " subsystem-name ".")]
                      (when (seq evidence-summary)
                        [(str "Policy expects evidence classes: " (str/join ", " evidence-summary) ".")])
                      (when (seq role-summary)
                        [(str "Policy expects evidence roles: " (str/join ", " role-summary) ".")])
                      (when (seq changed-files)
                        [(str "Files under review: "
                              (str/join ", "
                                        (map #(bio/relativize-path (:root-path profile)
                                                                   (bio/resolve-path (:root-path profile) %))
                                             changed-files)) ".")])
                      (when (some #(not= "code-review-only" (:verification-method %)) mechanism-families)
                        ["Executable evidence required for at least one affected mechanism family."])) )
      :concern-classes concern-classes
      :environment-assumptions (->> (concat (:environment-assumptions subsystem*)
                                            (:environment-assumptions profile))
                                     (remove nil?)
                                     distinct
                                     vec)
      :environment-assumption-categories (->> (concat (:environment-assumptions subsystem*)
                                                      (:environment-assumptions profile))
                                               (remove nil?)
                                               (map normalize-environment-assumption)
                                               distinct
                                               vec)
      :mechanism-families mechanism-families
      :model-finiteness-hints (let [cat-raw (:system-category subsystem*)
                                    category (cond
                                               (keyword? cat-raw) (name cat-raw)
                                               (some? cat-raw) (str cat-raw)
                                               :else "other")
                                    raw-assumptions (->> (concat (:environment-assumptions subsystem*)
                                                                 (:environment-assumptions profile))
                                                         (remove nil?)
                                                         vec)]
                                (detect-finiteness-hints category raw-assumptions))
      :co-model-with (vec (or (:coupled-with subsystem*) []))
      :non-goals (vec (concat
                        (when-not (seq (:formal-globs subsystem*))
                          ["No formal surface declared for this subsystem."])
                        (when-not (seq (:test-globs subsystem*))
                          ["No dedicated test surface declared for this subsystem."])))
      :handoff-outputs [(bio/resolve-path (get-in profile [:artifact-paths :root])
                                          (str subsystem-name "-verification-brief.yaml"))
                        (bio/resolve-path (get-in profile [:artifact-paths :root])
                                          (str subsystem-name "-observable-contract.yaml"))]
      :open-questions (vec (concat
                             (when (empty? rules)
                               ["No mechanism-family rule matched. Add file-glob-rules for stronger planning."])
                             (when (and policy-view (empty? evidence-summary))
                               ["No policy rule matched. Confirm evidence expectations manually."])
                             (let [missing-roles (when policy-view
                                                   (evidence/missing-roles profile role-summary))]
                               (when (seq missing-roles)
                                 [(str "No command registered for evidence role(s): "
                                       (str/join ", " missing-roles) ".")])))) })))

(defn plan-seed
  ([profile changed-files]
   (plan-seed profile nil {:changed-files changed-files}))
  ([profile policy {:keys [changed-files change-id]}]
   (let [intent (change/initial-change-intent profile policy changed-files (or change-id "plan-seed-change"))
         obligations (:missing-obligations intent)]
     {:artifact "plan-seed"
      :plan-seed/id (or change-id "plan-seed-change")
      :subject (first (get-in intent [:semantic-scope :subsystems]))
      :workflow-state (:workflow-state intent)
      :steps (vec (concat
                    [{:step 1 :kind "review-intent" :summary "Confirm inferred intent and subsystem scope."}]
                    (map-indexed (fn [idx obligation]
                                   {:step (+ idx 2)
                                    :kind "close-obligation"
                                    :summary obligation})
                                 obligations)
                    [{:step (+ 2 (count obligations))
                      :kind "refresh-artifacts"
                      :summary "Update verification-brief, observable-contract, and ledgers as needed."}]))
      :artifacts-to-update (vec (concat (:stale-artifacts intent)
                                        [(str (first (get-in intent [:semantic-scope :subsystems])) " verification-brief")]))
      :commands (mapv :command (:canonical-commands profile))})))
