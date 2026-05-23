(ns bridge.change
  (:require [bridge.artifacts :as artifacts]
            [bridge.evidence :as evidence]
            [bridge.io :as bio]
            [bridge.policy :as policy]
            [bridge.profile :as profile]
            [bridge.requirements :as requirements]
            [bridge.schema :as schema]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn normalize-evidence-kind [kind]
  (evidence/normalize-evidence-kind kind))

(defn available-evidence-kinds [profile subsystems]
  (->> (concat
        (map :kind (:canonical-commands profile))
        (mapcat :expected-evidence subsystems))
       (map normalize-evidence-kind)
       (filter some?)
       set))

(defn categorize-change-kinds [profile changed-files]
  (->> changed-files
       (map #(profile/change-surface-kind profile %))
       set))

(defn- verification-config-paths [profile]
  (let [root (:root-path profile)]
    (->> [(:source-path profile)
          (:verification-policy-path profile)
          (bio/resolve-path root ".bridge/verification-policy.yaml")]
         (filter some?)
         (map #(bio/relativize-path root %))
         set)))

(defn- verification-config-change? [profile changed-files]
  (let [root (:root-path profile)
        config-paths (verification-config-paths profile)]
    (boolean
     (some (fn [path]
             (contains? config-paths
                        (bio/relativize-path root (bio/resolve-path root path))))
           changed-files))))

(defn matched-change-files [profile changed-files subsystems matched-rules]
  (let [root (:root-path profile)
        subsystem-globs (mapcat profile/subsystem-rel-globs subsystems)
        rule-globs (keep :glob matched-rules)
        globs (vec (concat subsystem-globs rule-globs))]
    (if (seq globs)
      (->> changed-files
           (filter (fn [path]
                     (let [resolved (bio/resolve-path root path)
                           rel (bio/relativize-path root resolved)]
                       (profile/any-glob-match? globs rel))))
           vec)
      [])))

(defn change-sources [change-kinds]
  (vec
   (concat
    (when (contains? change-kinds :docs) ["spec-diff"])

    (when (contains? change-kinds :code) ["code-diff"])
    (when (contains? change-kinds :tests) ["test-diff"])
    (when (contains? change-kinds :formal) ["formal-diff"]))))

(defn infer-categories [change-kinds]
  (cond
    (= change-kinds #{:docs}) ["spec-only"]
    (= change-kinds #{:code}) ["code-only"]
    (= change-kinds #{:tests}) ["tests-only"]
    (and (contains? change-kinds :code) (contains? change-kinds :docs)) ["mixed" "feature"]
    (contains? change-kinds :code) ["mixed" "bugfix"]
    :else ["mixed"]))

(defn mechanism-families [profile changed-files]
  (let [matched-rules (profile/match-glob-rules profile changed-files)]
    (->> matched-rules
         (map :mechanism-family)
         (filter some?)
         distinct
         vec)))

(defn risk-class [subsystems]
  (if (some #(= "high" (some-> % :risk-class schema/normalize-enum-value)) subsystems)
    "high"
    (if (seq subsystems) "medium" "low")))

(defn- repo-relative-files [root]
  (->> (file-seq (io/file root))
       (filter #(.isFile %))
       (map #(.getCanonicalPath %))
       (map #(bio/relativize-path root %))
       vec))

(defn- repo-absolute-files [root]
  (->> (file-seq (io/file root))
       (filter #(.isFile %))
       (map #(.getCanonicalPath %))
       vec))

(defn- globs-have-files? [profile globs]
  (let [rels (repo-relative-files (:root-path profile))]
    (boolean (some #(profile/any-glob-match? globs %) rels))))

(defn existing-evidence-classes [profile subsystems]
  (let [artifact-root (get-in profile [:artifact-paths :root])
        artifacts-by-subject (if artifact-root
                               (group-by artifacts/subject-of (artifacts/find-artifacts artifact-root))
                               {})]
    (->> subsystems
         (mapcat (fn [subsystem]
                   (let [artifact-kinds (set (map :artifact (get artifacts-by-subject (:name subsystem))))]
                     (concat
                      (when (globs-have-files? profile (:test-globs subsystem)) [:unit-tests :property-tests])
                      (when (globs-have-files? profile (:docs-globs subsystem)) [:docs-or-nl-spec])
                      (when (globs-have-files? profile (:formal-globs subsystem)) [:formal-spec :trace-validation])
                      (when (contains? artifact-kinds "differential-evidence-card") [:differential-tests])
                      (when (contains? artifact-kinds "observable-contract") [:runtime-assertions])
                      (when (contains? artifact-kinds "evaluation-report") [:benchmarks])))))
         set)))

(defn- obligation-string [{:keys [kind required-evidence role artifact]}]
  (case kind
    "policy-evidence-forbidden" (str "Forbidden evidence present: " (first required-evidence))
    "policy-evidence-rerun" (str "Revalidate required evidence: " (first required-evidence))
    "policy-evidence-missing" (str "Missing required evidence: " (first required-evidence))
    "policy-evidence-recommended" (str "Recommended evidence: " (first required-evidence))
    "missing-role-coverage" (str "Missing evidence role coverage: " role)
    "evidence-rerun" (str "Rerun evidence: " (first required-evidence))
    "artifact-refresh" (str "Refresh artifact: " artifact)
    "artifact-review" (str "Review stale artifact: " artifact)
    "generated-artifact-refresh" (str "Refresh generated artifact: " artifact)
    "ledger-review" (str "Refresh artifact: " artifact)
    "review-required" (str "Review required: " artifact)
    (or artifact (first required-evidence) role kind)))

(defn- dedupe-obligations [obligations]
  (->> obligations
       (remove nil?)
       distinct
       vec))

(defn- policy-obligations-structured [policy-view change-kinds existing-evidence]
  (let [changed-evidence (policy/present-evidence-classes change-kinds)
        required-evidence (:required-evidence policy-view)]
    (->> policy/evidence-keys
         (keep (fn [k]
                 (let [level (policy/normalize-policy-value (get required-evidence k))]
                   (cond
                     (= "required" level)
                     {:kind (if (contains? existing-evidence k)
                              "policy-evidence-rerun"
                              "policy-evidence-missing")
                      :required-evidence [(name k)]
                      :reason (if (contains? existing-evidence k)
                                "Policy requires existing evidence to be refreshed for this change class."
                                "Policy requires evidence coverage for this change class.")}

                     (and (= "forbidden" level)
                          (or (contains? changed-evidence k)
                              (contains? existing-evidence k)))
                     {:kind "policy-evidence-forbidden"
                      :required-evidence [(name k)]
                      :reason "Policy forbids this evidence class for the matched change context."}))))
         vec)))

(defn- policy-recommendations-structured [policy-view change-kinds existing-evidence]
  (let [required-evidence (:required-evidence policy-view)]
    (->> policy/evidence-keys
         (keep (fn [k]
                 (let [level (policy/normalize-policy-value (get required-evidence k))]
                   (when (and (= "recommended" level)
                              (not (contains? existing-evidence k)))
                     {:kind "policy-evidence-recommended"
                      :required-evidence [(name k)]
                      :reason "Policy recommends evidence coverage for this change class."}))))
         vec)))

(defn- role-obligations-structured [profile policy-view]
  (when policy-view
    (->> (evidence/missing-roles profile (keys (:required-roles policy-view)))
         (mapv (fn [role]
                 {:kind "missing-role-coverage"
                  :role role
                  :reason "No evidence command is registered for required policy role coverage."})))))

(defn- family-name [rule]
  (some-> (:mechanism-family rule) str str/lower-case))

(defn- supports-evidence? [available kind]
  (contains? available (normalize-evidence-kind kind)))

(defn- family-obligations [available subject mechanism-family]
  (let [family (some-> mechanism-family str str/lower-case)]
    (vec
     (concat
      (when (= family "trace-conversion")
        (keep identity
              [{:kind "evidence-rerun"
                :subject subject
                :mechanism-family mechanism-family
                :required-evidence ["trace-validation"]
                :reason "Trace conversion drift can invalidate trace semantics."}
               {:kind "artifact-refresh"
                :subject subject
                :mechanism-family mechanism-family
                :artifact "observable-contract"
                :reason "Observed trace shape may have changed."}
               {:kind "ledger-review"
                :subject subject
                :mechanism-family mechanism-family
                :artifact "assumption-ledger"
                :reason "Trace mapping assumptions may have changed."}]))
      (when (= family "verification-harness")
        (keep identity
              [{:kind "evidence-rerun"
                :subject subject
                :mechanism-family mechanism-family
                :required-evidence ["trace-validation"]
                :reason "Harness changes require executable conformance rerun."}
               {:kind "artifact-review"
                :subject subject
                :mechanism-family mechanism-family
                :artifact "generated-configs-and-wrappers"
                :reason "Harness changes can stale generated configs and wrappers."}
               {:kind "ledger-review"
                :subject subject
                :mechanism-family mechanism-family
                :artifact "assumption-ledger"
                :reason "Harness execution assumptions may have changed."}]))
      (when (= family "spectrace-generation")
        (keep identity
              [{:kind "generated-artifact-refresh"
                :subject subject
                :mechanism-family mechanism-family
                :artifact "specTrace.*"
                :reason "Generated formal wrappers must be regenerated and validated."}
               (when (supports-evidence? available "trace-validation")
                 {:kind "evidence-rerun"
                  :subject subject
                  :mechanism-family mechanism-family
                  :required-evidence ["trace-validation"]
                  :reason "Generated wrapper changes affect downstream trace validation."})
               (when (supports-evidence? available "model-check")
                 {:kind "evidence-rerun"
                  :subject subject
                  :mechanism-family mechanism-family
                  :required-evidence ["model-check"]
                  :reason "Generated wrapper changes affect formal verification results."})
               {:kind "artifact-refresh"
                :subject subject
                :mechanism-family mechanism-family
                :artifact "verification-brief"
                :reason "Verification plan should reflect generator changes."}]))
      (when (= family "verification-orchestration")
        (keep identity
              [(when (supports-evidence? available "trace-validation")
                 {:kind "evidence-rerun"
                  :subject subject
                  :mechanism-family mechanism-family
                  :required-evidence ["trace-validation"]
                  :reason "Orchestration changes can invalidate end-to-end trace validation runs."})
               (when (supports-evidence? available "model-check")
                 {:kind "evidence-rerun"
                  :subject subject
                  :mechanism-family mechanism-family
                  :required-evidence ["model-check"]
                  :reason "Orchestration changes can invalidate end-to-end invariant runs."})
               {:kind "artifact-refresh"
                :subject subject
                :mechanism-family mechanism-family
                :artifact "verification-changelog"
                :reason "Execution workflow changes should be recorded."}
               {:kind "artifact-refresh"
                :subject subject
                :mechanism-family mechanism-family
                :artifact "verification-scope-ledger"
                :reason "Workflow scope may have changed."}
               {:kind "artifact-refresh"
                :subject subject
                :mechanism-family mechanism-family
                :artifact "completeness-ledger"
                :reason "Workflow coverage may have changed."}]))
      (when (= family "tooling-config")
        (keep identity
              [(when (supports-evidence? available "trace-validation")
                 {:kind "evidence-rerun"
                  :subject subject
                  :mechanism-family mechanism-family
                  :required-evidence ["trace-validation"]
                  :reason "Tooling config changes affect verification helper execution."})
               (when (supports-evidence? available "model-check")
                 {:kind "evidence-rerun"
                  :subject subject
                  :mechanism-family mechanism-family
                  :required-evidence ["model-check"]
                  :reason "Tooling config changes affect formal verification helper execution."})
               {:kind "ledger-review"
                :subject subject
                :mechanism-family mechanism-family
                :artifact "assumption-ledger"
                :reason "Tooling alias, endpoint, or credential assumptions may have changed."}]))))))

(defn inferred-obligations [profile policy changed-files subsystems matched-rules]
  (let [available (available-evidence-kinds profile subsystems)
        default-subject (some-> subsystems first :name)]
    (->> matched-rules
         (mapcat (fn [rule]
                   (family-obligations available
                                       (or (:subsystem rule) default-subject)
                                       (:mechanism-family rule))))
         dedupe-obligations)))

(defn- artifact-stale-details [profile changed-files subsystem-names]
  (let [artifact-root (get-in profile [:artifact-paths :root])
        changed-paths (->> changed-files
                           (map #(bio/resolve-path (:root-path profile) %))
                           (filter bio/exists?)
                           vec)
        latest-change (bio/newest-mtime changed-paths)]
    (if (or (nil? artifact-root) (empty? changed-paths))
      []
      (->> (artifacts/find-artifacts artifact-root)
           (filter #(contains? (set subsystem-names) (artifacts/subject-of %)))
           (filter #(bio/exists? (:_path %)))
           (filter #(< (bio/last-modified-ms (:_path %)) latest-change))
           (mapv (fn [artifact]
                   {:path (:_path artifact)
                    :kind "artifact"
                    :subject (artifacts/subject-of artifact)
                    :stale-because (mapv #(bio/relativize-path (:root-path profile) %)
                                         changed-paths)}))))))

(defn- output-pattern-matches [repo-files output-pattern]
  (->> repo-files
       (filter #(profile/path-match? output-pattern %))
       vec))

(defn- derived-artifact-statuses [profile changed-files]
  (let [root (:root-path profile)
        repo-files (repo-absolute-files root)
        changed-paths (->> changed-files
                           (map #(bio/resolve-path root %))
                           (filter bio/exists?)
                           vec)]
    (->> (:derived-artifacts profile)
         (mapcat (fn [artifact]
                   (let [triggered-inputs (->> changed-paths
                                               (filter (fn [path]
                                                         (some #(profile/path-match? % path) (:inputs artifact))))
                                               vec)]
                     (if (empty? triggered-inputs)
                       []
                       (let [latest-input (bio/newest-mtime triggered-inputs)
                             stale-because (mapv #(bio/relativize-path root %) triggered-inputs)]
                         (mapcat (fn [output-pattern]
                                   (let [matches (output-pattern-matches repo-files output-pattern)]
                                     (if (empty? matches)
                                       [{:path output-pattern
                                         :kind (or (:kind artifact) "derived-artifact")
                                         :derived-artifact (:name artifact)
                                         :stale-because stale-because
                                         :status "missing"}]
                                       (->> matches
                                            (filter #(< (bio/last-modified-ms %) latest-input))
                                            (map (fn [path]
                                                   {:path path
                                                    :kind (or (:kind artifact) "derived-artifact")
                                                    :derived-artifact (:name artifact)
                                                    :stale-because stale-because
                                                    :status "stale"}))
                                            vec))))
                                 (:outputs artifact)))))))
         dedupe-obligations)))

(defn stale-artifacts-detailed [profile changed-files subsystem-names]
  (->> (concat
        (artifact-stale-details profile changed-files subsystem-names)
        (derived-artifact-statuses profile changed-files))
       dedupe-obligations))

(defn stale-artifacts [profile changed-files subsystem-names]
  (->> (stale-artifacts-detailed profile changed-files subsystem-names)
       (mapv :path)))

(defn initial-change-intent [profile policy changed-files change-id]
  (let [config-change? (verification-config-change? profile changed-files)
        subsystems (if config-change?
                     (vec (:subsystems profile))
                     (profile/match-subsystems profile changed-files))
        matched-rules (profile/match-glob-rules profile changed-files)
        subsystem-names (mapv :name subsystems)
        matched-files (matched-change-files profile changed-files subsystems matched-rules)
        classified-files (if (seq matched-files) matched-files changed-files)
        change-kinds (categorize-change-kinds profile classified-files)
        categories (infer-categories change-kinds)
        concern-classes (->> matched-rules
                             (keep :concern-class)
                             (map #(if (keyword? %) (name %) (str %)))
                             distinct
                             vec)
        system-categories (->> subsystems (keep :system-category) (map #(if (keyword? %) (name %) (str %))) distinct vec)
        context {:subsystems subsystem-names
                 :change-categories categories
                 :system-categories system-categories
                 :risk-class (risk-class subsystems)
                 :concern-classes concern-classes}
        policy-view (when policy (policy/derive-policy policy context))
        existing-evidence (existing-evidence-classes profile subsystems)
        structured-obligations (->> (concat
                                     (when policy-view
                                       (policy-obligations-structured policy-view change-kinds existing-evidence))
                                     (role-obligations-structured profile policy-view)
                                     (inferred-obligations profile policy changed-files subsystems matched-rules))
                                    dedupe-obligations)
        missing-obligations (->> structured-obligations
                                 (map obligation-string)
                                 distinct
                                 vec)
        structured-recommendations (if policy-view
                                     (policy-recommendations-structured policy-view change-kinds existing-evidence)
                                     [])
        recommended-obligations (->> structured-recommendations
                                     (map obligation-string)
                                     distinct
                                     vec)
        stale-details (stale-artifacts-detailed profile changed-files subsystem-names)
        requirement-sources (requirements/matched-source-summaries profile changed-files)]
    (cond-> {:artifact "change-intent-card"
             :change-id change-id
             :change-surface (mapv #(bio/relativize-path (:root-path profile)
                                                         (bio/resolve-path (:root-path profile) %))
                                   changed-files)
             :change-sources (change-sources change-kinds)
             :inferred-intent {:summary (str "Change touches " (str/join ", " subsystem-names)
                                             " across " (str/join ", " (map name change-kinds)) " surfaces.")
                               :categories categories
                               :mechanism-families (mechanism-families profile changed-files)
                               :concern-classes concern-classes}
             :accepted-intent {:status "draft"
                               :summary "Human review pending."}
             :semantic-scope {:contracts []
                              :subsystems subsystem-names}
             :risk-class (risk-class subsystems)
             :workflow-state "draft"
             :missing-obligations missing-obligations
             :missing-obligations-structured structured-obligations
             :recommended-obligations recommended-obligations
             :recommended-obligations-structured structured-recommendations
             :stale-artifacts (mapv :path stale-details)
             :stale-artifacts-detailed stale-details
             :open-questions (vec (concat
                                   (when (empty? subsystem-names)
                                     ["No subsystem matched changed files. Update project profile globs or assign subsystem manually."])
                                   (when (and policy (empty? missing-obligations))
                                     ["Confirm inferred intent categories before marking workflow active."])))}
      (seq requirement-sources) (assoc :requirement-sources requirement-sources))))

(defn change-impact-reports [profile changed-files]
  (let [matched-rules (profile/match-glob-rules profile changed-files)]
    (mapv (fn [path]
            {:artifact "change-impact-report"
             :change-surface [(bio/relativize-path (:root-path profile)
                                                   (bio/resolve-path (:root-path profile) path))]
             :affected-contracts (->> matched-rules (keep :contract) distinct vec)
             :affected-formal-modules (->> matched-rules (keep :formal-module) distinct vec)
             :affected-tests (->> matched-rules (keep :test-path) distinct vec)
             :revalidation-plan (mapv (fn [idx cmd]
                                        {:step (inc idx)
                                         :action (or (:description cmd) (:id cmd))
                                         :command (:command cmd)})
                                      (range)
                                      (:canonical-commands profile))
             :open-risks (->> matched-rules (keep :risk-note) distinct vec)})
          changed-files)))
