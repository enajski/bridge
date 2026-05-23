(ns bridge.adapters.sysmobench
  "Converts SysMoBench task.yaml into Bridge artifacts.
   Reads task description, source files, target actions, harness config,
   and invariant templates to produce:
   - verification-brief
   - observable-contract
   - assumption-ledger"
  (:require [bridge.io :as bio]
            [clojure.string :as str]))

;; --- task.yaml reader ---

(defn load-task [path]
  (bio/read-data path))

;; --- System-type → assumption templates ---

(def ^:private system-type-assumptions
  {"distributed"
   [{:id "reliable-channels"
     :description "Network channels are reliable (no message loss in normal operation)"
     :tla-implication "Model channels as sequences or bags. Writes append, reads consume."
     :risk "If modeled with loss, state space explodes."}
    {:id "process-identity"
     :description "Each process has a unique ID from a finite set"
     :tla-implication "Process sets must not overlap. Check CONSTANT assignments."}]

   "concurrent"
   [{:id "shared-memory"
     :description "Threads communicate via shared memory with defined ordering"
     :tla-implication "Model shared variables directly. Interleaving semantics."}
    {:id "atomic-operations"
     :description "Certain operations are atomic (e.g., CAS, load/store)"
     :tla-implication "Each atomic op is one TLA+ step. Don't split into sub-steps."}]})

;; --- Brief generation ---

(defn- actions->mechanism-families [target-actions trace-action-map]
  (mapv (fn [action]
          (let [label (some (fn [[k v]] (when (= v action) (if (keyword? k) (name k) (str k)))) trace-action-map)]
            {:name (-> action
                       (str/replace #"([a-z])([A-Z])" "$1-$2")
                       str/lower-case)
             :tla-action action
             :trace-label (or label "unknown")
             :verification-method "model-checkable"
             :concern-class "safety"}))
        target-actions))

(defn- invariants->concern-classes [invariants-path]
  (when (bio/exists? invariants-path)
    (let [data (bio/read-data invariants-path)
          invs (:invariants data)]
      {:safety-count (count (filter #(= "safety" (:type %)) invs))
       :liveness-count (count (filter #(= "liveness" (:type %)) invs))
       :invariant-names (mapv :name invs)})))

(defn generate-brief [task {:keys [invariants-path]}]
  (let [wv (:wv task)
        target-actions (or (:target_actions wv) (:target-actions wv) [])
        trace-map (or (:trace_action_map (:harness wv))
                      (:trace-action-map (:harness wv)) {})
        system-type (or (:system_type task) (:system-type task) "other")
        inv-info (when invariants-path (invariants->concern-classes invariants-path))
        base-assumptions (get system-type-assumptions system-type [])
        mechanism-families (actions->mechanism-families target-actions trace-map)]
    {:artifact "verification-brief"
     :subject (:name task)
     :category system-type
     :summary [(str "SysMoBench task: " (:description task))
               (str (count target-actions) " target actions for WV evaluation.")
               (when inv-info
                 (str (+ (:safety-count inv-info) (:liveness-count inv-info))
                      " invariants (" (:safety-count inv-info) " safety, "
                      (:liveness-count inv-info) " liveness)."))]
     :concern-classes (vec (distinct
                            (concat
                             (when (and inv-info (pos? (:safety-count inv-info))) ["safety"])
                             (when (and inv-info (pos? (:liveness-count inv-info))) ["liveness"])
                             ["spec-fidelity"])))
     :environment-assumptions (mapv :description base-assumptions)
     :mechanism-families (mapv (fn [mf]
                                 {:name (:name mf)
                                  :description (str "TLA+ action: " (:tla-action mf)
                                                    " (trace label: " (:trace-label mf) ")")
                                  :concern-class (:concern-class mf)
                                  :verification-method (:verification-method mf)
                                  :affected-paths (mapv :path (or (:source_files task) (:source-files task) []))
                                  :planned-evidence ["trace-validation" "model-check"]})
                               mechanism-families)
     :co-model-with []
     :non-goals ["Do NOT define invariants — focus on behavioral modeling."
                 "Do NOT model internal/noise archetypes outside target actions."]
     :handoff-outputs []
     :open-questions (vec (concat
                           (when (empty? target-actions)
                             ["No target_actions in task.yaml wv config."])
                           (when (empty? trace-map)
                             ["No trace_action_map — trace validation scoring may fail."])))}))

;; --- Observable contract generation ---

(defn generate-observable [task]
  (let [wv (:wv task)
        target-actions (or (:target_actions wv) (:target-actions wv) [])
        trace-map (or (:trace_action_map (:harness wv))
                      (:trace-action-map (:harness wv)) {})
        coverage-gaps (or (:coverage_gaps (:harness wv))
                          (:coverage-gaps (:harness wv)) [])]
    {:artifact "observable-contract"
     :subject (:name task)
     :observables (mapv (fn [action]
                          (let [labels (keep (fn [[k v]] (when (= v action) (if (keyword? k) (name k) (str k)))) trace-map)]
                            {:name action
                             :description (str "Trace event mapped to TLA+ action " action)
                             :trigger-point (str/join ", " (or (seq labels) ["unknown"]))
                             :capture-timing "post-step"
                             :snapshot-strength "full"
                             :collection-mode "inline"
                             :maps-to [action]
                             :used-by ["trace validation"]}))
                        target-actions)
     :label-to-action-map (into {} (map (fn [[k v]] [(if (keyword? k) (name k) (str k)) (or v nil)]) trace-map))
     :coverage-notes coverage-gaps}))

;; --- Assumption ledger generation ---

(defn generate-assumption-ledger [task]
  (let [system-type (or (:system_type task) (:system-type task) "other")
        base-assumptions (get system-type-assumptions system-type [])
        wv (:wv task)
        harness (:harness wv)]
    {:artifact "assumption-ledger"
     :subject (:name task)
     :code-assumptions (vec (concat
                             base-assumptions
                             (when (:docker_image harness)
                               [{:id "docker-harness"
                                 :description (str "Traces collected via Docker image: " (:docker_image harness))
                                 :tla-implication "Trace timing may differ from bare-metal execution."
                                 :risk "Docker scheduling can affect trace ordering."}])
                             (when-let [action-map (or (:trace_action_map harness)
                                                       (:trace-action-map harness))]
                               [{:id "trace-action-mapping"
                                 :description (str "Trace labels mapped to " (count action-map) " spec actions. Unmapped labels carry action=null.")
                                 :tla-implication "Only mapped actions are scored. Internal labels are noise."
                                 :risk "Wrong mapping → 0% score for affected action."}])))
     :differential-assumptions []
     :formal-assumptions []}))

;; --- Orchestrator: task.yaml → all artifacts ---

(defn adapt-task
  "Convert SysMoBench task.yaml to Bridge artifacts.
   Returns map of {:brief ... :observable ... :assumptions ...}"
  [task-yaml-path & {:keys [invariants-path]}]
  (let [task (load-task task-yaml-path)]
    {:brief (generate-brief task {:invariants-path invariants-path})
     :observable (generate-observable task)
     :assumptions (generate-assumption-ledger task)}))

(defn write-artifacts
  "Write all adapted artifacts to output directory."
  [task-yaml-path out-dir & {:keys [invariants-path]}]
  (let [{:keys [brief observable assumptions]} (adapt-task task-yaml-path :invariants-path invariants-path)
        task-name (:subject brief)]
    (bio/write-data (str out-dir "/" task-name "-verification-brief.yaml") brief)
    (bio/write-data (str out-dir "/" task-name "-observable-contract.yaml") observable)
    (bio/write-data (str out-dir "/" task-name "-assumption-ledger.yaml") assumptions)
    {:written [(str out-dir "/" task-name "-verification-brief.yaml")
               (str out-dir "/" task-name "-observable-contract.yaml")
               (str out-dir "/" task-name "-assumption-ledger.yaml")]}))
