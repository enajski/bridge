(ns bridge.eval
  (:require [bridge.artifacts :as artifacts]
            [bridge.evidence :as evidence]
            [bridge.io :as bio]
            [bridge.schema :as schema]
            [bridge.status :as status]))

(defn- normalize-evaluation-profile [path data]
  (let [base (or (.getParent (java.io.File. path)) ".")
        working-directory (bio/resolve-path base (:working-directory data))]
    (cond-> (assoc data
                   :source-path (bio/absolute-path path)
                   :working-directory working-directory)
      (:artifact-dir data) (update :artifact-dir #(bio/resolve-path working-directory %))
      (:output-dir data) (update :output-dir #(bio/resolve-path working-directory %))
      (:command-steps data) (update :command-steps (fn [steps]
                                                     (mapv (fn [step]
                                                             (cond-> step
                                                               (:cwd step) (update :cwd #(bio/resolve-path working-directory %))
                                                               (:output-path step) (update :output-path #(bio/resolve-path working-directory %))))
                                                           steps))))))

(defn load-evaluation-profile [path]
  (let [data (bio/read-data path)
        result (schema/validate-artifact-data data {:root (.getParent (java.io.File. path))
                                                    :expected-kind "evaluation-profile"})]
    (when-not (:valid? result)
      (throw (ex-info "Invalid evaluation profile" {:path path :validation result})))
    (normalize-evaluation-profile path data)))

(defn- step-timeout-ms [step]
  (if-let [seconds (:timeout-seconds step)]
    (* 1000 (long seconds))
    evidence/default-timeout-ms))

(defn- step-cwd [profile step]
  (or (:cwd step) (:working-directory profile) "."))

(defn- validate-step-cwd! [profile step cwd]
  (when-not (and (bio/exists? cwd)
                 (bio/directory? cwd))
    (throw (ex-info "Evaluation step cwd does not exist or is not a directory"
                    {:id (:id step)
                     :cwd cwd})))
  (when-not (bio/path-within? (:working-directory profile) cwd)
    (throw (ex-info "Evaluation step cwd must be inside working directory"
                    {:id (:id step)
                     :cwd cwd
                     :working-directory (:working-directory profile)}))))

(defn- run-step [profile step output-root]
  (let [stdout-path (bio/resolve-path output-root (str (:id step) ".stdout.log"))
        stderr-path (bio/resolve-path output-root (str (:id step) ".stderr.log"))
        cwd (step-cwd profile step)
        timeout-ms (step-timeout-ms step)
        started-at (bio/now-iso)
        started-ms (System/currentTimeMillis)
        _ (validate-step-cwd! profile step cwd)
        result (bio/run-shell {:shell evidence/shell-command
                               :command (:command step)
                               :cwd cwd
                               :timeout-ms timeout-ms})
        finished-at (bio/now-iso)
        duration-ms (- (System/currentTimeMillis) started-ms)
        parsed (evidence/parse-result step (:out result) (:err result))]
    (bio/write-text stdout-path (:out result))
    (bio/write-text stderr-path (:err result))
    (cond-> {:id (:id step)
             :exit-code (:exit result)
             :status (if (zero? (:exit result)) "ok" "failed")
             :execution-status (evidence/execution-status (:exit result))
             :evidence-status (:evidence-status parsed)
             :failure-signals (:failure-signals parsed)
             :parsed-metrics (:parsed-metrics parsed)
             :stdout-path stdout-path
             :stderr-path stderr-path
             :started-at started-at
             :finished-at finished-at
             :duration-ms duration-ms
             :timeout-ms timeout-ms
             :timed-out? (boolean (:timed-out? result))}
      (or (:kind step) (:kind parsed)) (assoc :kind (or (:kind step) (:kind parsed)))
      (or (:role step) (:role parsed)) (assoc :role (or (:role step) (:role parsed)))
      (:output-path step) (assoc :output-path (:output-path step))
      (:related-paths parsed) (assoc :related-paths (:related-paths parsed))
      (:failure-summary parsed) (assoc :failure-summary (:failure-summary parsed)))))

(defn- bridge-metrics [profile]
  (let [artifact-dir (or (:artifact-dir profile) (get-in profile [:bridge-metric-sources :artifact-dir]))]
    (if-not artifact-dir
      {}
      (let [validation (artifacts/validate-dir artifact-dir)
            convergence (status/convergence-report artifact-dir)
            phase-dir (bio/resolve-path artifact-dir "phases")
            handoff {:expected-count (count (filter bio/exists? (when (bio/exists? phase-dir) (bio/data-files-under phase-dir))))
                     :present-count (count (when (bio/exists? phase-dir) (bio/data-files-under phase-dir)))
                     :missing-count 0
                     :complete? (bio/exists? phase-dir)}
            change-intents (filter #(= "change-intent-card" (:artifact %)) (artifacts/find-artifacts artifact-dir))]
        {:artifact-valid-count (count (filter (comp :valid? :result) validation))
         :artifact-invalid-count (count (remove (comp :valid? :result) validation))
         :artifact-total-count (count validation)
         :converged-subject-count (:converged-subject-count convergence)
         :regressed-subject-count (:regressed-subject-count convergence)
         :open-obligation-count (reduce + 0 (map #(count (:missing-obligations %)) change-intents))
         :change-intent-count (count change-intents)
         :handoff-present-count (:present-count handoff)
         :handoff-complete? (:complete? handoff)}))))

(defn- verification-status [task-results]
  (let [statuses (map :evidence-status task-results)]
    (cond
      (some #(= "failed" %) statuses) "failed"
      (and (seq statuses) (every? #(= "passed" %) statuses)) "passed"
      (some #(= "partial" %) statuses) "partial"
      (or (some #(= "passed" %) statuses)
          (some #(= "unknown" %) statuses)) "partial"
      :else "unknown")))

(defn- execution-summary-status [task-results]
  (if (every? #(= "executed" (:execution-status %)) task-results)
    "executed"
    "execution-failed"))

(defn- workflow-status [execution-status verification-status]
  (cond
    (= "failed" verification-status) "regressed"
    (= "execution-failed" execution-status) "regressed"
    (= "passed" verification-status) "converged"
    :else "active"))

(defn- outcome-labels [verification-status]
  (case verification-status
    "passed" ["clean-with-scope"]
    "failed" ["suspected"]
    ["none"]))

(defn- top-failure-summary [task-results]
  (some :failure-summary task-results))

(defn run-evaluation [profile {:keys [out-path]}]
  (let [output-root (bio/resolve-path (or (:working-directory profile) ".")
                                      (or (:output-dir profile) "artifacts/evaluations"))
        task-results (mapv #(run-step profile % output-root) (:command-steps profile))
        external-metrics (into {}
                               (mapcat (fn [result]
                                         (for [[k v] (:parsed-metrics result)]
                                           [(keyword (str (:id result) "." (name k))) v])))
                               task-results)
        execution-status (execution-summary-status task-results)
        verification-status (verification-status task-results)
        report {:artifact "evaluation-report"
                :evaluation-id (:evaluation-id profile)
                :subject (:subject profile)
                :status (workflow-status execution-status verification-status)
                :execution-status execution-status
                :verification-status verification-status
                :started-at (or (:started-at (first task-results)) (bio/now-iso))
                :finished-at (or (:finished-at (last task-results)) (bio/now-iso))
                :task-results task-results
                :external-metrics external-metrics
                :bridge-metrics (bridge-metrics profile)
                :outcome-labels (outcome-labels verification-status)
                :related-artifacts []
                :notes ["Truth-preserving evaluation adapter."]}
        report* (cond-> report
                  (top-failure-summary task-results) (assoc :failure-summary (top-failure-summary task-results)))
        out-path* (or out-path (bio/resolve-path output-root (str (:evaluation-id profile) ".yaml")))]
    (bio/write-data out-path* report*)
    {:report report* :path out-path*}))
