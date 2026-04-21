(ns bridge.evidence
  (:require [bridge.io :as bio]
            [bridge.schema :as schema]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def shell-command ["bash" "-lc"])
(def default-timeout-ms 300000)

(def evidence-kind-aliases
  {"unit" "unit-tests"
   "unit-tests" "unit-tests"
   "property" "property-tests"
   "property-tests" "property-tests"
   "runtime" "runtime-assertions"
   "runtime-assertions" "runtime-assertions"
   "docs" "docs-or-nl-spec"
   "docs-or-nl-spec" "docs-or-nl-spec"
   "formal-spec" "formal-spec"
   "proof" "proof"
   "model-check" "model-check"
   "differential" "differential-tests"
   "differential-tests" "differential-tests"
   "trace-validation" "trace-validation"
   "benchmarks" "benchmarks"
   "benchmark" "benchmarks"
   "confirmation" "confirmation-evidence"
   "confirmation-evidence" "confirmation-evidence"
   "custom" "custom"})

(def evidence-status-rank
  {"unknown" 0
   "passed" 1
   "partial" 2
   "failed" 3})

(defn normalize-evidence-kind [kind]
  (let [text (some-> kind schema/normalize-enum-value str str/lower-case)]
    (get evidence-kind-aliases text text)))

(defn commands [profile]
  (:canonical-commands profile))

(defn command-by-id [profile id]
  (first (filter #(= id (:id %)) (commands profile))))

(defn execution-status [exit-code]
  (if (zero? (long exit-code)) "executed" "execution-failed"))

(defn- maybe-json [s]
  (try
    (json/parse-string s keyword)
    (catch Exception _ nil)))

(defn- maybe-edn [s]
  (try
    (edn/read-string s)
    (catch Exception _ nil)))

(defn- stream-text [stream stdout stderr]
  (case (keyword (or stream :stdout))
    :stderr (or stderr "")
    :both (str (or stdout "") "\n" (or stderr ""))
    (or stdout "")))

(defn- regex-match [regex text]
  (let [match (re-find (re-pattern regex) (or text ""))]
    (cond
      (string? match) {:value match :whole match}
      (vector? match) {:value (or (second match) (first match))
                       :whole (first match)
                       :groups (vec (rest match))}
      :else nil)))

(defn- choose-evidence-status [statuses]
  (let [statuses* (remove nil? statuses)]
    (if (seq statuses*)
      (apply max-key #(get evidence-status-rank % 0) statuses*)
      "unknown")))

(defn- capture-entry [entry stdout stderr]
  (when-let [match (regex-match (:regex entry) (stream-text (:stream entry) stdout stderr))]
    {:as (some-> (:as entry) str)
     :value (:value match)
     :whole (:whole match)
     :failure-signal? (boolean (:failure-signal? entry))}))

(defn- parser-paths [parser stdout stderr]
  (->> (:path-extractors parser)
       (keep #(capture-entry % stdout stderr))
       (keep (fn [{:keys [as value]}]
               (when (and as value)
                 [as value])))
       (into {})))

(defn- bridge-result-payload [stdout]
  (let [data (maybe-edn stdout)
        result (cond
                 (and (map? data) (map? (:result data))) (:result data)
                 (map? data) data
                 :else nil)]
    (when (and (map? result)
               (or (contains? result :evidence-status)
                   (contains? result :execution-status)
                   (contains? result :failure-signals)
                   (contains? result :parsed-metrics)))
      result)))

(defn- parse-bridge-result [result-parser stdout]
  (when-let [result (bridge-result-payload stdout)]
    (let [evidence-status (or (some-> (:evidence-status result) schema/normalize-enum-value str) "unknown")
          failure-signals (vec (distinct (filter seq (map str (:failure-signals result)))))
          parsed-metrics (or (:parsed-metrics result) {})
          related-paths (or (:related-paths result) {})
          suggested-labels (vec (map str (or (:suggested-labels result)
                                             (:suggested-labels result-parser))))
          failure-summary (or (:failure-summary result)
                              (when (= evidence-status "failed")
                                {:primary-signal (first failure-signals)
                                 :related-paths related-paths
                                 :suggested-labels suggested-labels}))]
      (cond-> {:evidence-status evidence-status
               :failure-signals failure-signals
               :parsed-metrics parsed-metrics}
        (:kind result) (assoc :kind (schema/normalize-enum-value (:kind result)))
        (:role result) (assoc :role (schema/normalize-enum-value (:role result)))
        (seq related-paths) (assoc :related-paths related-paths)
        failure-summary (assoc :failure-summary failure-summary)))))

(defn parse-result [{:keys [result-parser parse-json-stdout?]} stdout stderr]
  (let [json-metrics (if parse-json-stdout? (or (maybe-json stdout) {}) {})
        parser-type (some-> (:type result-parser) str)]
    (cond
      (or (= parser-type "bridge-result")
          (and (nil? result-parser)
               (bridge-result-payload stdout)))
      (or (parse-bridge-result result-parser stdout)
          {:evidence-status "unknown"
           :failure-signals []
           :parsed-metrics json-metrics})

      (= parser-type "regex-status")
      (let [regex-rules (->> (:rules result-parser)
                             (keep (fn [rule]
                                     (when-let [match (regex-match (:regex rule)
                                                                   (stream-text (:stream rule) stdout stderr))]
                                       {:status (some-> (:status rule) schema/normalize-enum-value str)
                                        :match (or (:whole match) (:value match))})))
                             vec)
            captures (->> (:captures result-parser)
                          (keep #(capture-entry % stdout stderr))
                          vec)
            evidence-status (choose-evidence-status (map :status regex-rules))
            failed-rule-signals (->> regex-rules
                                     (filter #(= "failed" (:status %)))
                                     (map :match)
                                     (filter seq))
            captured-failure-signals (->> captures
                                          (filter (fn [{:keys [as failure-signal?]}]
                                                    (or failure-signal?
                                                        (str/includes? (or as "") "failure"))))
                                          (map :value)
                                          (filter seq))
            failure-signals (vec (distinct (concat captured-failure-signals failed-rule-signals)))
            parsed-metrics (merge json-metrics
                                  (into {}
                                        (keep (fn [{:keys [as value]}]
                                                (when (and as value)
                                                  [(keyword as) value])))
                                        captures))
            related-paths (parser-paths result-parser stdout stderr)
            failure-summary (when (= evidence-status "failed")
                              {:primary-signal (first failure-signals)
                               :related-paths related-paths
                               :suggested-labels (vec (map str (:suggested-labels result-parser)))})]
        (cond-> {:evidence-status evidence-status
                 :failure-signals failure-signals
                 :parsed-metrics parsed-metrics}
          (seq related-paths) (assoc :related-paths related-paths)
          failure-summary (assoc :failure-summary failure-summary)))

      :else
      {:evidence-status "unknown"
       :failure-signals []
       :parsed-metrics json-metrics})))

(defn- timeout-ms [seconds]
  (if seconds
    (* 1000 (long seconds))
    default-timeout-ms))

(defn- evidence-artifact-path [output-root id out-path]
  (or out-path
      (bio/resolve-path output-root (str id ".yaml"))))

(defn command-execution-plan [profile id {:keys [out-dir out-path subject timeout-seconds]}]
  (try
    (let [command (command-by-id profile id)]
      (when-not command
        (throw (ex-info "Unknown evidence command"
                        {:id id
                         :profile-source-path (:source-path profile)
                         :available (mapv :id (commands profile))})))
      (let [output-root (bio/resolve-path (:root-path profile)
                                          (or out-dir
                                              (get-in profile [:artifact-paths :evidence])
                                              "artifacts/evidence"))
            cwd (or (:cwd command) (:root-path profile))]
        {:id (:id command)
         :kind (:kind command)
         :role (:role command)
         :description (:description command)
         :subject (or subject (:subject command) (:project-name profile))
         :profile-source-path (:source-path profile)
         :profile-root (:root-path profile)
         :cwd cwd
         :output-root output-root
         :stdout-path (bio/resolve-path output-root (str id ".stdout.log"))
         :stderr-path (bio/resolve-path output-root (str id ".stderr.log"))
         :artifact-path (evidence-artifact-path output-root id out-path)
         :output-path (:output-path command)
         :command (:command command)
         :result-parser (:result-parser command)
         :shell shell-command
         :timeout-ms (timeout-ms (or timeout-seconds (:timeout-seconds command)))
         :env-overlay-keys []}))
    (catch Exception e
      (throw (ex-info "Failed to build evidence execution plan"
                      {:id id
                       :profile-source-path (:source-path profile)
                       :profile-root (:root-path profile)
                       :error (ex-message e)
                       :data (ex-data e)}
                      e)))))

(defn list-commands [profile]
  (mapv (fn [command]
          (let [plan (command-execution-plan profile (:id command) {})]
            {:id (:id command)
             :kind (:kind command)
             :role (:role command)
             :description (:description command)
             :profile-root (:profile-root plan)
             :cwd (:cwd plan)
             :command (:command plan)
             :output-path (:output-path plan)
             :subject (:subject plan)
             :default-output-root (:output-root plan)
             :default-artifact-path (:artifact-path plan)
             :timeout-ms (:timeout-ms plan)
             :result-parser (some-> (:result-parser plan) :type)}))
        (commands profile)))

(defn commands-by-role [profile]
  (group-by #(some-> (:role %) schema/normalize-enum-value) (commands profile)))

(defn role-coverage [profile]
  (->> (commands-by-role profile)
       (map (fn [[role cmds]] [role (mapv :id cmds)]))
       (into {})))

(defn missing-roles [profile required-roles]
  (let [coverage (role-coverage profile)]
    (->> required-roles
         (map schema/normalize-enum-value)
         (remove #(seq (get coverage %)))
         vec)))

(defn- validate-cwd! [plan]
  (when-not (and (bio/exists? (:cwd plan))
                 (bio/directory? (:cwd plan)))
    (throw (ex-info "Evidence command cwd does not exist or is not a directory"
                    {:id (:id plan)
                     :cwd (:cwd plan)})))
  (when-not (bio/path-within? (:profile-root plan) (:cwd plan))
    (throw (ex-info "Evidence command cwd must be inside profile root"
                    {:id (:id plan)
                     :cwd (:cwd plan)
                     :profile-root (:profile-root plan)}))))

(defn- parser-metadata [parser]
  (when parser
    (cond-> {:type (str (:type parser))}
      (:suggested-labels parser) (assoc :suggested-labels (vec (map str (:suggested-labels parser)))))))

(defn- evidence-run-artifact [plan parsed result started-at finished-at duration-ms]
  (cond-> {:artifact "evidence-run"
           :evidence-id (:id plan)
           :subject (str (:subject plan))
           :kind (schema/normalize-enum-value (:kind plan))
           :execution-status (execution-status (:exit result))
           :evidence-status (:evidence-status parsed)
           :exit-code (:exit result)
           :stdout-path (:stdout-path plan)
           :stderr-path (:stderr-path plan)
           :started-at started-at
           :finished-at finished-at
           :duration-ms duration-ms
           :timeout-ms (:timeout-ms plan)
           :timed-out? (boolean (:timed-out? result))
           :command (:command plan)
           :cwd (:cwd plan)
           :failure-signals (vec (:failure-signals parsed))
           :parsed-metrics (or (:parsed-metrics parsed) {})}
    (:role plan) (assoc :role (schema/normalize-enum-value (:role plan)))
    (:output-path plan) (assoc :output-path (:output-path plan))
    (:related-paths parsed) (assoc :related-paths (:related-paths parsed))
    (:failure-summary parsed) (assoc :failure-summary (:failure-summary parsed))
    (parser-metadata (:result-parser plan)) (assoc :parser (parser-metadata (:result-parser plan)))))

(defn- validate-artifact! [artifact]
  (let [result (schema/validate-artifact-data artifact)]
    (when-not (:valid? result)
      (throw (ex-info "Evidence run produced schema-invalid artifact"
                      {:validation result
                       :artifact artifact}))))
  artifact)

(defn run-command [profile id {:keys [out-dir out-path dry-run? subject timeout-seconds]}]
  (let [plan (command-execution-plan profile id {:out-dir out-dir
                                                 :out-path out-path
                                                 :subject subject
                                                 :timeout-seconds timeout-seconds})]
    (if dry-run?
      (assoc plan :dry-run? true)
      (let [started-at (bio/now-iso)
            started-ms (System/currentTimeMillis)]
        (try
          (validate-cwd! plan)
          (let [result (bio/run-shell {:shell shell-command
                                       :command (:command plan)
                                       :cwd (:cwd plan)
                                       :timeout-ms (:timeout-ms plan)})
                finished-at (bio/now-iso)
                duration-ms (- (System/currentTimeMillis) started-ms)
                parsed (parse-result {:result-parser (:result-parser plan)} (:out result) (:err result))
                artifact (validate-artifact!
                           (evidence-run-artifact plan parsed result started-at finished-at duration-ms))]
            (bio/write-text (:stdout-path plan) (:out result))
            (bio/write-text (:stderr-path plan) (:err result))
            (bio/write-data (:artifact-path plan) artifact)
            artifact)
          (catch Exception e
            (throw (ex-info "Evidence command execution failed"
                            {:id id
                             :profile-source-path (:profile-source-path plan)
                             :profile-root (:profile-root plan)
                             :cwd (:cwd plan)
                             :output-root (:output-root plan)
                             :artifact-path (:artifact-path plan)
                             :timeout-ms (:timeout-ms plan)
                             :command (:command plan)
                             :error (ex-message e)
                             :data (ex-data e)}
                            e))))))))
