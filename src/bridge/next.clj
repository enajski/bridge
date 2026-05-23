(ns bridge.next
  (:require [bridge.artifacts :as artifacts]
            [bridge.change :as change]
            [bridge.evidence :as evidence]
            [bridge.io :as bio]
            [bridge.policy :as policy]
            [bridge.schema :as schema]
            [bridge.status :as status]
            [clojure.string :as str]))

(defn- shell-lines [root command]
  (try
    (let [{:keys [exit out]} (bio/run-shell {:command command
                                             :cwd root
                                             :timeout-ms 10000})]
      (if (zero? exit)
        (->> (str/split-lines out)
             (map str/trim)
             (remove str/blank?)
             vec)
        []))
    (catch Exception _ [])))

(defn- process-lines [root argv]
  (let [{:keys [exit out err timed-out?]} (bio/run-process {:argv argv
                                                            :cwd root
                                                            :timeout-ms 10000})]
    (if (zero? exit)
      (->> (str/split-lines out)
           (map str/trim)
           (remove str/blank?)
           vec)
      (throw (ex-info "Git diff failed"
                      {:argv argv
                       :cwd root
                       :exit exit
                       :timed-out? timed-out?
                       :stderr err})))))

(defn detect-git-changed-files [profile]
  (let [root (:root-path profile)]
    (->> (concat
          (shell-lines root "git diff --name-only")
          (shell-lines root "git diff --cached --name-only")
          (shell-lines root "git ls-files --others --exclude-standard"))
         distinct
         vec)))

(defn git-diff-changed-files [profile spec]
  (let [root (:root-path profile)
        argv (if (str/includes? spec "..")
               ["git" "diff" "--name-only" spec]
               ["git" "diff" "--name-only" spec "HEAD"])]
    (process-lines root argv)))

(defn- normalize-changed-files [profile changed-files]
  (->> changed-files
       (map #(bio/relativize-path (:root-path profile)
                                  (bio/resolve-path (:root-path profile) %)))
       distinct
       vec))

(defn resolve-changed-files
  [profile opts]
  (let [{:keys [changed-files git-diff-spec]} (or opts {})
        files (cond
                (seq changed-files) changed-files
                git-diff-spec (git-diff-changed-files profile git-diff-spec)
                :else (detect-git-changed-files profile))]
    (normalize-changed-files profile files)))

(defn resolve-change-detection
  [profile {:keys [changed-files git-diff-spec]}]
  (let [mode (cond
               (seq changed-files) "explicit-files"
               git-diff-spec "git-diff"
               :else "working-tree")]
    {:changed-files (resolve-changed-files profile {:changed-files changed-files
                                                    :git-diff-spec git-diff-spec})
     :change-detection (cond-> {:mode mode}
                         git-diff-spec (assoc :spec git-diff-spec))
     :change-id (cond
                  (seq changed-files) "explicit-files"
                  git-diff-spec (str "git-diff:" git-diff-spec)
                  :else "working-tree")}))

(defn- load-policy [profile]
  (let [policy-path (or (:verification-policy-path profile)
                        (let [default-path (bio/resolve-path (:root-path profile) ".bridge/verification-policy.yaml")]
                          (when (bio/exists? default-path)
                            default-path)))]
    (when (and policy-path (bio/exists? policy-path))
      (policy/load-policy policy-path))))

(defn- resolve-result-status [result]
  (let [status (schema/normalize-enum-value (:evidence-status result))]
    (assoc result :evidence-status
           (if (and (= "unknown" status)
                    (or (zero? (:exit-code result))
                        (= 0 (:exit result))
                        (= "executed" (:execution-status result))))
             "passed"
             status))))

(defn- evidence-results [profile artifacts]
  (->> (concat
        (->> artifacts
             (filter #(= "evaluation-report" (:artifact %)))
             (mapcat :task-results))
        (->> artifacts
             (filter #(= "evidence-run" (:artifact %)))
             (remove #(evidence/stale-evidence-run? profile %))))
       (map resolve-result-status)
       vec))

(defn- evidence-statuses-by-kind [profile artifacts]
  (reduce (fn [acc result]
            (if-let [kind (some-> (:kind result) evidence/normalize-evidence-kind)]
              (update acc kind (fnil conj []) (schema/normalize-enum-value (:evidence-status result)))
              acc))
          {}
          (evidence-results profile artifacts)))

(defn- obligation-state [statuses-by-kind obligation]
  (let [required-kinds (map evidence/normalize-evidence-kind (:required-evidence obligation))
        statuses-per-kind (map #(get statuses-by-kind % []) required-kinds)]
    (cond
      (= "policy-evidence-forbidden" (:kind obligation)) "failed"
      (empty? required-kinds) "open"
      (some #(some #{"failed"} %) statuses-per-kind) "failed"
      (every? seq statuses-per-kind)
      (if (every? #(some #{"passed"} %) statuses-per-kind) "completed" "open")
      :else "open")))

(defn- action-suggestions [profile obligation]
  (let [required (set (map evidence/normalize-evidence-kind (:required-evidence obligation)))]
    (->> (:canonical-commands profile)
         (filter #(contains? required (evidence/normalize-evidence-kind (:kind %))))
         (mapv (fn [cmd]
                 {:op "bridge/run-evidence"
                  :args {:id (:id cmd)}
                  :evidence-id (:id cmd)
                  :evidence-kind (some-> (:kind cmd) evidence/normalize-evidence-kind)
                  :role (:role cmd)
                  :description (:description cmd)})))))

(defn- obligation-summary [profile statuses-by-kind obligation fallback-summary]
  (let [state (obligation-state statuses-by-kind obligation)
        required-kinds (mapv evidence/normalize-evidence-kind (:required-evidence obligation))]
    {:kind (:kind obligation)
     :subject (:subject obligation)
     :artifact (:artifact obligation)
     :required-evidence required-kinds
     :summary (or fallback-summary
                  (:artifact obligation)
                  (first required-kinds)
                  (:role obligation)
                  (:kind obligation))
     :reason (:reason obligation)
     :state state
     :actions (action-suggestions profile obligation)}))

(defn- transient-obligations [profile artifacts intent]
  (let [statuses-by-kind (evidence-statuses-by-kind profile artifacts)
        structured (:missing-obligations-structured intent)
        plain (:missing-obligations intent)]
    (mapv (fn [idx obligation]
            (obligation-summary profile statuses-by-kind obligation (nth plain idx nil)))
          (range)
          structured)))

(defn- transient-recommendations [profile artifacts intent]
  (let [statuses-by-kind (evidence-statuses-by-kind profile artifacts)
        structured (:recommended-obligations-structured intent)
        plain (:recommended-obligations intent)]
    (mapv (fn [idx obligation]
            (obligation-summary profile statuses-by-kind obligation (nth plain idx nil)))
          (range)
          structured)))

(defn- completed-evidence [profile artifacts]
  (->> (evidence-results profile artifacts)
       (keep (fn [result]
               (let [status (schema/normalize-enum-value (:evidence-status result))]
                 (when (= "passed" status)
                   {:id (or (:evidence-id result) (:id result))
                    :kind (some-> (:kind result) evidence/normalize-evidence-kind)
                    :subject (:subject result)
                    :status status
                    :finished-at (:finished-at result)}))))
       vec))

(defn- subject-problems [convergence]
  (->> (:subjects convergence)
       (keep (fn [[subject summary]]
               (let [open (:open-obligation-count summary)
                     failed (:failed-obligation-count summary)
                     open-evidence (:open-evidence-count summary)
                     failed-evidence (:failed-evidence-count summary)
                     state (:workflow-state summary)]
                 (when (or (pos? open)
                           (pos? failed)
                           (pos? open-evidence)
                           (pos? failed-evidence)
                           (= "regressed" state))
                   {:subject subject
                    :workflow-state state
                    :open-obligation-count open
                    :failed-obligation-count failed
                    :open-evidence-count open-evidence
                    :failed-evidence-count failed-evidence
                    :verification-status (:verification-status summary)}))))
       vec))

(defn build-status
  [profile opts]
  (let [{:keys [changed-files git-diff-spec policy]} (or opts {})
        {:keys [changed-files change-detection change-id]}
        (resolve-change-detection profile {:changed-files changed-files
                                           :git-diff-spec git-diff-spec})
        changed-files* changed-files
        policy (or policy (load-policy profile))
        artifact-root (get-in profile [:artifact-paths :root])
        artifacts (if artifact-root (artifacts/find-artifacts artifact-root) [])
        intent (when (seq changed-files*)
                 (change/initial-change-intent profile policy changed-files* change-id))
        obligations (if intent (transient-obligations profile artifacts intent) [])
        recommendations (if intent (transient-recommendations profile artifacts intent) [])
        open-obligations (filterv #(= "open" (:state %)) obligations)
        failed-obligations (filterv #(= "failed" (:state %)) obligations)
        completed-obligations (filterv #(= "completed" (:state %)) obligations)
        open-recommendations (filterv #(= "open" (:state %)) recommendations)
        convergence (status/convergence-report profile artifact-root)
        subject-problems* (subject-problems convergence)
        stale-artifacts (vec (:stale-artifacts-detailed intent))
        issue-count (+ (count open-obligations)
                       (count failed-obligations)
                       (count stale-artifacts)
                       (count subject-problems*))]
    {:project (:project-name profile)
     :profile-source-path (:source-path profile)
     :profile-root (:root-path profile)
     :artifact-root artifact-root
     :changed-files changed-files*
     :change-detection change-detection
     :active-change? (boolean (seq changed-files*))
     :intent (some-> intent
                     (select-keys [:change-id :change-sources :inferred-intent
                                   :semantic-scope :risk-class :workflow-state
                                   :open-questions]))
     :open-obligations open-obligations
     :failed-obligations failed-obligations
     :completed-obligations completed-obligations
     :recommended-obligations open-recommendations
     :stale-artifacts stale-artifacts
     :completed-evidence (completed-evidence profile artifacts)
     :subject-problems subject-problems*
     :convergence-summary (select-keys convergence
                                       [:converged-subject-count :regressed-subject-count])
     :status (if (zero? issue-count) "clear" "attention-required")
     :issue-count issue-count}))

(defn exit-code [status]
  (if (= "clear" (:status status)) 0 1))

(defn- colorize [enabled code s]
  (if enabled
    (str "\u001b[" code "m" s "\u001b[0m")
    s))

(defn- line [s] (str s "\n"))

(defn- render-obligation [color? marker obligation]
  (str "  " marker " " (:summary obligation)
       (when-let [reason (:reason obligation)]
         (str " (" reason ")"))
       "\n"
       (when-let [actions (seq (:actions obligation))]
         (apply str
                (map (fn [{:keys [evidence-id]}]
                       (str "      -> bb bridge run-evidence --id " evidence-id "\n"))
                     actions)))))

(defn- action-from-obligation [state obligation]
  (when-let [action (first (:actions obligation))]
    (assoc action
           :state state
           :summary (:summary obligation)
           :reason (:reason obligation)
           :required-evidence (:required-evidence obligation))))

(defn planned-actions [status]
  (vec
   (concat
    (keep #(action-from-obligation "failed" %) (:failed-obligations status))
    (keep #(action-from-obligation "open" %) (:open-obligations status)))))

(defn next-action [status]
  (first (planned-actions status)))

(defn- render-next-action [color? status]
  (when-let [{:keys [evidence-id summary reason]} (next-action status)]
    (str (line (colorize color? "36" "Next Action:"))
         "  Run evidence: bb bridge run-evidence --id " evidence-id "\n"
         "  Why: " summary
         (when reason (str " (" reason ")"))
         "\n")))

(defn render-plain
  ([status] (render-plain status {}))
  ([status {:keys [color?]}]
   (let [attention? (= "attention-required" (:status status))
         title (if attention?
                 (colorize color? "33" "Bridge Status: Attention Required")
                 (colorize color? "32" "Bridge Status: All Clear"))
         intent (:intent status)
         subsystems (get-in intent [:semantic-scope :subsystems])]
     (str
      (line title)
      (line "------------------------------------------------------------------")
      (line (str "Project: " (:project status)))
      (when (seq (:changed-files status))
        (str (line (str "Changed files: " (str/join ", " (:changed-files status))))
             (when (seq subsystems)
               (line (str "Subsystems: " (str/join ", " subsystems))))
             (when-let [risk (:risk-class intent)]
               (line (str "Risk Class: " risk)))
             (when-let [categories (seq (get-in intent [:inferred-intent :categories]))]
               (line (str "Intent: " (str/join ", " categories))))))
      (when-not (seq (:changed-files status))
        (line "Changed files: none detected"))
      "\n"
      (or (render-next-action color? status) "")
      (if (seq (:failed-obligations status))
        (str (line (colorize color? "31" "Failed Obligations:"))
             (apply str (map #(render-obligation color? "[!]" %) (:failed-obligations status))))
        "")
      (if (seq (:open-obligations status))
        (str (line (colorize color? "33" "Pending Obligations:"))
             (apply str (map #(render-obligation color? "[ ]" %) (:open-obligations status))))
        "")
      (if (seq (:recommended-obligations status))
        (str (line (colorize color? "36" "Recommended Actions (Advisory):"))
             (apply str (map #(render-obligation color? "[ ]" %) (:recommended-obligations status))))
        "")
      (if (seq (:stale-artifacts status))
        (str (line (colorize color? "33" "Stale Artifacts:"))
             (apply str
                    (map (fn [{:keys [path kind status stale-because]}]
                           (str "  [ ] " (or path kind)
                                (when status (str " (" status ")"))
                                (when (seq stale-because)
                                  (str " because of " (str/join ", " stale-because)))
                                "\n"))
                         (:stale-artifacts status))))
        "")
      (if (seq (:subject-problems status))
        (str (line (colorize color? "31" "Existing Workflow Problems:"))
             (apply str
                    (map (fn [{:keys [subject workflow-state open-obligation-count failed-obligation-count
                                      open-evidence-count failed-evidence-count]}]
                           (str "  [!] " subject
                                " state=" workflow-state
                                " open=" open-obligation-count
                                " failed=" failed-obligation-count
                                " evidence-open=" (or open-evidence-count 0)
                                " evidence-failed=" (or failed-evidence-count 0)
                                "\n"))
                         (:subject-problems status))))
        "")
      (if (seq (:completed-obligations status))
        (str (line (colorize color? "32" "Completed Obligations:"))
             (apply str (map #(render-obligation color? "[x]" %) (:completed-obligations status))))
        "")
      (if (seq (:completed-evidence status))
        (str (line (colorize color? "32" "Completed Evidence:"))
             (apply str
                    (map (fn [{:keys [id kind subject finished-at]}]
                           (str "  [x] " (or id kind)
                                (when subject (str " for " subject))
                                (when finished-at (str " at " finished-at))
                                "\n"))
                         (:completed-evidence status))))
        "")
      (when (= "clear" (:status status))
        (line "No pending obligations, stale artifacts, or regressions in tracked subsystems/artifact subjects were found."))))))
