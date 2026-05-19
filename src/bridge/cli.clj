(ns bridge.cli
  (:require [bridge.adapters.sysmobench :as sysmobench]
            [bridge.artifacts :as artifacts]
            [bridge.brief :as brief]
            [bridge.change :as change]
            [bridge.eval :as beval]
            [bridge.evidence :as evidence]
            [bridge.feasibility :as feasibility]
            [bridge.io :as bio]
            [bridge.next :as next-guide]
            [bridge.observe :as observe]
            [bridge.policy :as policy]
            [bridge.profile :as profile]
            [bridge.requirements :as requirements]
            [bridge.schema :as schema]
            [bridge.status :as status]
            [bridge.templates :as templates]
            [bridge.workflow :as workflow]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

(defn parse-value [s]
  (if (re-matches #"(:.*|\[.*|\{.*|true|false|nil|-?\d+(?:\.\d+)?)" s)
    (try
      (edn/read-string s)
      (catch Exception _ s))
    s))

(defn parse-args [args]
  (loop [xs args
         opts {}
         pos []]
    (if-let [x (first xs)]
      (if (str/starts-with? x "--")
        (let [k (keyword (subs x 2))
              v (second xs)]
          (if (or (nil? v) (str/starts-with? v "--"))
            (recur (rest xs) (assoc opts k true) pos)
            (recur (nnext xs)
                   (update opts k (fn [curr]
                                    (cond
                                      (nil? curr) (parse-value v)
                                      (vector? curr) (conj curr (parse-value v))
                                      :else [curr (parse-value v)])))
                   pos)))
        (recur (rest xs) opts (conj pos x)))
      {:options opts :positionals pos})))

(defn println-data [data]
  (pprint/pprint data))

(def ^:dynamic *exit-fn*
  (fn [status]
    (System/exit status)))

(defn require-option [opts k]
  (or (get opts k)
      (throw (ex-info "Missing required option" {:option k}))))

(def default-profile-paths
  [".bridge/profile.edn"
   ".bridge/persistent/profile.edn"])

(defn discover-profile-path []
  (first (filter bio/exists? default-profile-paths)))

(defn profile-option [opts]
  (or (:profile opts)
      (discover-profile-path)
      (throw (ex-info "Missing required option" {:option :profile
                                                 :searched default-profile-paths}))))

(defn ensure-vector [x]
  (cond
    (nil? x) []
    (vector? x) x
    :else [x]))

(defn load-profile+policy [opts]
  (let [profile (profile/load-profile (profile-option opts))
        policy-path (or (:policy opts)
                        (:verification-policy-path profile))
        policy (when (and policy-path (bio/exists? policy-path))
                 (policy/load-policy policy-path))]
    {:profile profile :policy policy}))

(def help-text
  (str/join
    "\n"
    ["bridge CLI"
     ""
     "Stable commands:"
     "  init [--root DIR]"
     "      Create .bridge/profile.edn and verification-policy.yaml."
     "  init-profile --path FILE"
     "      Write a starter project profile."
     "  install-hooks [--profile FILE] [--root DIR]"
     "      Install a Bridge pre-push check hook."
     "  validate-artifact FILE"
     "      Validate one Bridge artifact or profile."
     "  validate-dir DIR"
     "      Validate all artifacts in a directory."
     "  summary --profile FILE"
     "      Summarize configured subjects and artifacts."
     "  debug-profile --profile FILE [--changed-file PATH]..."
     "      Show normalized profile paths, globs, and matches."
     "  analyze-change --profile FILE --changed-file PATH [--changed-file PATH]... [--out FILE]"
     "      Build a change-intent card from changed files."
     "  check [--profile FILE] [--changed-file PATH]... [--format text|edn] [--no-color]"
     "      Print status for automation and exit nonzero on issues."
     "  next [--profile FILE] [--changed-file PATH]... [--tui auto|always|never] [--no-color]"
     "      Show the next verification work for a repo."
     "  list-evidence --profile FILE"
     "      List runnable evidence commands from the profile."
     "  run-evidence --profile FILE --id ID [--subject SUBJECT] [--out FILE] [--out-dir DIR] [--timeout-seconds N] [--dry-run]"
     "      Run one evidence command and record a receipt."
     ""
     "Experimental commands:"
     "  list-templates"
     "      List available prompt templates."
     "  render-prompt --template ID --profile FILE [--out FILE] [--changed-file PATH]..."
     "      Render an agent prompt from a profile."
     "  query --profile FILE SUBJECT [FIELD]"
     "      Query existing artifacts by subject and field."
     "  coverage --profile FILE"
     "      Summarize artifact coverage against profile expectations."
     "  stub-artifact --kind KIND --out FILE"
     "      Write a schema-shaped artifact stub."
     "  missing-artifacts --profile FILE"
     "      List expected artifacts that are not present."
     "  generate-brief --profile FILE [--subsystem NAME] [--changed-file PATH]... [--out FILE]"
     "      Generate a verification brief draft."
     "  generate-observable --profile FILE [--subsystem NAME] [--changed-file PATH]... [--out FILE]"
     "      Generate an observable-contract draft."
     "  plan-seed --profile FILE [--changed-file PATH]... [--out FILE]"
     "      Generate a phase handoff seed."
     "  convergence --profile FILE"
     "      Report workflow convergence from artifacts."
     "  completeness --profile FILE"
     "      Summarize completeness ledgers."
     "  init-phase --profile FILE --phase ID [--out FILE]"
     "      Initialize a configured workflow phase output."
     "  run-phase --profile FILE --phase ID [--changed-file PATH]... [--out FILE]"
     "      Run one configured workflow phase."
     "  run-phases --profile FILE [--phase ID]... [--changed-file PATH]... [--continue-on-error]"
     "      Run configured workflow phases."
     "  eval --profile FILE [--out FILE]"
     "      Run an evaluation profile."
     "  feasibility-report --artifact FILE --out FILE"
     "      Render a feasibility-study report."
     "  sysmobench-adapt --task-yaml FILE --out-dir DIR [--invariants FILE]"
     "      Convert a SysMoBench task into Bridge artifacts."
     ""
     "Formal verification, evaluation adapters, and generated formal linkage are experimental and opt-in."]))

(defn default-policy [project-name]
  {:artifact "verification-policy"
   :policy-id (str project-name "-bridge-v1")
   :rules [{:scope {:subsystems ["core"]
                    :change-categories ["code-only" "mixed"]
                    :risk-classes ["medium" "high"]}
            :required-evidence {:unit-tests "required"
                                :property-tests "optional"
                                :runtime-assertions "optional"
                                :docs-or-nl-spec "recommended"}
            :evidence-roles {:regression ["unit"]}
            :omission-rules {:allowed? true
                             :requires-record? true
                             :required-fields ["rationale" "owner"]}
            :notes ["Default bootstrap policy. Customize scope and evidence levels for this project."]}]})

(defn init-profile-data [project-name root-path]
  (merge (schema/stub-profile)
         {:kind "project-profile"
          :project-name project-name
          :root-path root-path
          :code-paths ["src"]
          :docs-paths ["docs"]
          :test-paths ["test"]
          :artifact-paths {:root ".bridge/ephemeral"
                           :phases ".bridge/ephemeral/phases"
                           :evidence ".bridge/ephemeral/evidence"
                           :evaluations ".bridge/ephemeral/evaluations"}
          :canonical-commands []
          :subsystems [{:name "core"
                        :code-globs ["src/**/*"]
                        :docs-globs ["docs/**/*"]
                        :test-globs ["test/**/*"]
                        :expected-artifacts ["change-intent-card" "verification-brief" "completeness-ledger"]
                        :expected-evidence ["unit"]
                        :system-category "api"
                        :risk-class "medium"}]
          :phases []}))

(defn append-gitignore-entry! [path entry]
  (let [existing (if (bio/exists? path) (bio/read-text path) "")
        lines (set (str/split-lines existing))]
    (when-not (contains? lines entry)
      (bio/write-text path (str existing
                                (when (and (seq existing) (not (str/ends-with? existing "\n"))) "\n")
                                entry
                                "\n"))))
  path)

(defn command-init-profile [opts]
  (let [path (require-option opts :path)
        project-name (or (:project-name opts) "example-project")
        root-path (or (:root-path opts) ".")
        data (init-profile-data project-name root-path)]
    (bio/write-data path data)
    {:written path}))

(defn command-init [opts]
  (let [root (or (:root opts) ".")
        project-name (.getName (io/file (bio/absolute-path root)))
        profile-path (str (io/file root ".bridge/profile.edn"))
        policy-path (str (io/file root ".bridge/verification-policy.yaml"))]
    (doseq [dir [".bridge"
                 ".bridge/ephemeral"
                 ".bridge/ephemeral/phases"
                 ".bridge/ephemeral/evidence"
                 ".bridge/ephemeral/evaluations"
                 ".bridge/ephemeral/prompts"]]
      (.mkdirs (io/file root dir)))
    (when (bio/exists? profile-path)
      (throw (ex-info "Bridge profile already exists" {:path profile-path})))
    (when (bio/exists? policy-path)
      (throw (ex-info "Bridge verification policy already exists" {:path policy-path})))
    (command-init-profile {:path profile-path :project-name project-name :root-path ".."})
    (bio/write-data policy-path (default-policy project-name))
    (append-gitignore-entry! (str (io/file root ".gitignore")) "/.bridge/ephemeral/")
    {:created [".bridge/profile.edn" ".bridge/verification-policy.yaml"]
     :updated [".gitignore"]
     :next-command "bb bridge next"}))

(def hook-marker-start "# BEGIN Bridge pre-push hook")
(def hook-marker-end "# END Bridge pre-push hook")

(defn hook-block [profile-path]
  (str hook-marker-start "\n"
       "bb bridge check --profile " profile-path "\n"
       hook-marker-end "\n"))

(defn command-install-hooks [opts]
  (let [root (or (:root opts) ".")
        git-dir (str (io/file root ".git"))
        hooks-dir (str (io/file root ".git/hooks"))
        hook-path (str (io/file root ".git/hooks/pre-push"))
        root-profile-path (str (io/file root ".bridge/profile.edn"))
        profile-path (or (:profile opts)
                         (when (bio/exists? root-profile-path) ".bridge/profile.edn")
                         (discover-profile-path)
                         ".bridge/profile.edn")]
    (when-not (bio/directory? git-dir)
      (throw (ex-info "Cannot install hooks outside a Git repository" {:git-dir git-dir})))
    (.mkdirs (io/file hooks-dir))
    (let [existing (if (bio/exists? hook-path) (bio/read-text hook-path) "")
          block (hook-block profile-path)
          marker-pattern (re-pattern (str "(?s)"
                                          (java.util.regex.Pattern/quote hook-marker-start)
                                          ".*?"
                                          (java.util.regex.Pattern/quote hook-marker-end)
                                          "\\n?"))
          content (cond
                    (str/includes? existing hook-marker-start)
                    (str/replace existing marker-pattern block)

                    (seq existing)
                    (str existing
                         (when-not (str/ends-with? existing "\n") "\n")
                         "\n"
                         block)

                    :else
                    (str "#!/usr/bin/env bash\n"
                         "set -euo pipefail\n\n"
                         block))]
      (bio/write-text hook-path content)
      (.setExecutable (io/file hook-path) true))
    {:installed hook-path
     :profile profile-path}))

(defn command-list-templates [_]
  {:templates (templates/list-templates)})

(defn command-render-prompt [opts]
  (let [{:keys [profile]} (load-profile+policy opts)
        files (ensure-vector (:changed-file opts))
        rendered (templates/render-from-profile (require-option opts :template)
                                                profile
                                                {:subsystem (when-let [s (:subsystem opts)]
                                                              (profile/subsystem-by-name profile s))
                                                 :files files
                                                 :extra {:context (:context opts)}})]
    (if-let [out (:out opts)]
      (do (bio/write-text out rendered) {:written out})
      {:rendered rendered})))

(defn command-validate-artifact [opts pos]
  (let [path (or (first pos) (:path opts))]
    {:path path
     :validation (schema/validate-file path)}))

(defn command-validate-dir [opts pos]
  {:results (artifacts/validate-dir (or (first pos) (:dir opts)))})

(defn command-summary [opts]
  {:summary (status/project-summary (:profile (load-profile+policy opts)))})

(defn command-debug-profile [opts]
  (let [profile (:profile (load-profile+policy opts))
        changed-files (ensure-vector (:changed-file opts))
        requirement-summary (requirements/catalog-summary profile)
        matched-requirement-summaries (requirements/matched-source-summaries profile changed-files)]
    {:debug-profile (assoc (profile/debug-profile-data profile changed-files)
                           :requirement-debug (assoc requirement-summary
                                                     :matched-source-files matched-requirement-summaries))}))

(defn command-query [opts pos]
  (let [profile (:profile (load-profile+policy opts))
        subject (or (first pos) (:subject opts))
        field (or (second pos) (:field opts))]
    {:query (artifacts/query-artifacts profile (get-in profile [:artifact-paths :root]) subject field)}))

(defn command-coverage [opts]
  {:coverage (artifacts/coverage (:profile (load-profile+policy opts)))})

(defn command-stub-artifact [opts]
  (let [kind (require-option opts :kind)
        out (require-option opts :out)
        artifact (schema/stub-artifact kind)]
    (bio/write-data out artifact)
    {:written out}))

(defn command-missing-artifacts [opts]
  {:missing (artifacts/missing-artifacts (:profile (load-profile+policy opts)))})

(defn command-analyze-change [opts]
  (let [{:keys [profile policy]} (load-profile+policy opts)
        files (ensure-vector (:changed-file opts))
        change-card (change/initial-change-intent profile policy files (or (:change-id opts) "change-1"))
        reports (change/change-impact-reports profile files)]
    (if-let [out (:out opts)]
      (do
        (bio/write-data out change-card)
        (doseq [[idx report] (map-indexed vector reports)]
          (bio/write-data (str (str/replace out #"\.(yaml|yml|edn)$" "") "-impact-" (inc idx) ".yaml") report))
        {:written out :impact-count (count reports)})
      {:change-intent change-card :impact-reports reports})))

(defn command-generate-brief [opts]
  (let [{:keys [profile policy]} (load-profile+policy opts)
        artifact (brief/generate-brief profile policy {:changed-files (ensure-vector (:changed-file opts))
                                                       :subsystem (when-let [s (:subsystem opts)]
                                                                    (profile/subsystem-by-name profile s))
                                                       :subject (:subject opts)})]
    (if-let [out (:out opts)]
      (do (bio/write-data out artifact) {:written out})
      {:artifact artifact})))

(defn command-generate-observable [opts]
  (let [{:keys [profile]} (load-profile+policy opts)
        subsystem-name (or (:subsystem opts)
                           (some-> (first (profile/match-subsystems profile (ensure-vector (:changed-file opts)))) :name))
        artifact (observe/generate-observable-contract profile {:subsystem-name (some-> subsystem-name str)
                                                                :changed-files (ensure-vector (:changed-file opts))
                                                                :subject (:subject opts)})]
    (if-let [out (:out opts)]
      (do (bio/write-data out artifact) {:written out})
      {:artifact artifact})))

(defn command-plan-seed [opts]
  (let [{:keys [profile policy]} (load-profile+policy opts)
        seed (brief/plan-seed profile policy {:changed-files (ensure-vector (:changed-file opts))
                                              :change-id (:change-id opts)})]
    (if-let [out (:out opts)]
      (do (bio/write-data out seed) {:written out})
      {:plan-seed seed})))

(defn command-check [opts]
  (let [profile (:profile (load-profile+policy opts))]
    {:bridge-check (next-guide/build-status profile {:changed-files (ensure-vector (:changed-file opts))})}))

(defn command-next [opts]
  (let [profile (:profile (load-profile+policy opts))]
    {:bridge-next (next-guide/build-status profile {:changed-files (ensure-vector (:changed-file opts))})}))

(defn command-convergence [opts]
  (let [profile (:profile (load-profile+policy opts))]
    {:convergence (status/convergence-report profile (get-in profile [:artifact-paths :root]))
     :handoff (status/handoff-completeness profile)}))

(defn command-completeness [opts]
  (let [profile (:profile (load-profile+policy opts))]
    {:summary (artifacts/summarize-ledgers (get-in profile [:artifact-paths :root]) profile)}))

(defn command-init-phase [opts]
  (let [{:keys [profile]} (load-profile+policy opts)]
    {:written (workflow/init-phase! profile (require-option opts :phase) (:out opts))}))

(defn command-run-phase [opts]
  (let [{:keys [profile policy]} (load-profile+policy opts)]
    (workflow/run-phase! profile
                         (require-option opts :phase)
                         {:changed-files (ensure-vector (:changed-file opts))
                          :policy policy
                          :out-path (:out opts)
                          :subject (:subject opts)
                          :subsystem (when-let [s (:subsystem opts)]
                                       (profile/subsystem-by-name profile s))})))

(defn command-run-phases [opts]
  (let [{:keys [profile policy]} (load-profile+policy opts)]
    (workflow/run-phases! profile
                          (ensure-vector (:phase opts))
                          {:changed-files (ensure-vector (:changed-file opts))
                           :policy policy
                           :subject (:subject opts)
                           :continue-on-error? (boolean (:continue-on-error opts))
                           :subsystem (when-let [s (:subsystem opts)]
                                        (profile/subsystem-by-name profile s))})))

(defn command-list-evidence [opts]
  {:commands (evidence/list-commands (:profile (load-profile+policy opts)))})

(defn command-run-evidence [opts]
  (let [{:keys [profile]} (load-profile+policy opts)]
    {:result (evidence/run-command profile
                                   (require-option opts :id)
                                   {:out-dir (:out-dir opts)
                                    :out-path (:out opts)
                                    :subject (:subject opts)
                                    :timeout-seconds (:timeout-seconds opts)
                                    :dry-run? (boolean (:dry-run opts))})}))

(defn command-eval [opts]
  (beval/run-evaluation (beval/load-evaluation-profile (require-option opts :profile)) {:out-path (:out opts)}))

(defn command-feasibility-report [opts]
  {:written (feasibility/render-study! (require-option opts :artifact)
                                       (require-option opts :out))})

(defn dispatch [args]
  (let [{:keys [options positionals]} (parse-args args)
        command (or (first positionals) (:command options))
        pos (rest positionals)]
    (case command
      nil {:help help-text}
      "help" {:help help-text}
      "init" (command-init options)
      "init-profile" (command-init-profile options)
      "install-hooks" (command-install-hooks options)
      "list-templates" (command-list-templates options)
      "render-prompt" (command-render-prompt options)
      "validate-artifact" (command-validate-artifact options pos)
      "validate-dir" (command-validate-dir options pos)
      "summary" (command-summary options)
      "debug-profile" (command-debug-profile options)
      "query" (command-query options pos)
      "coverage" (command-coverage options)
      "stub-artifact" (command-stub-artifact options)
      "missing-artifacts" (command-missing-artifacts options)
      "analyze-change" (command-analyze-change options)
      "generate-brief" (command-generate-brief options)
      "generate-observable" (command-generate-observable options)
      "plan-seed" (command-plan-seed options)
      "check" (command-check options)
      "next" (command-next options)
      "convergence" (command-convergence options)
      "completeness" (command-completeness options)
      "init-phase" (command-init-phase options)
      "run-phase" (command-run-phase options)
      "run-phases" (command-run-phases options)
      "list-evidence" (command-list-evidence options)
      "run-evidence" (command-run-evidence options)
      "eval" (command-eval options)
      "feasibility-report" (command-feasibility-report options)
      "sysmobench-adapt" (let [task-yaml (:task-yaml options)
                               out-dir (:out-dir options)
                               invariants (:invariants options)]
                           (when-not task-yaml (throw (ex-info "--task-yaml required" {})))
                           (when-not out-dir (throw (ex-info "--out-dir required" {})))
                           (sysmobench/write-artifacts task-yaml out-dir :invariants-path invariants))
      (throw (ex-info "Unknown command" {:command command :help help-text})))) )

(defn- parsed-command [args]
  (let [{:keys [options positionals]} (parse-args args)]
    {:options options
     :command (or (first positionals) (:command options))}))

(defn- print-status-command! [command opts status]
  (let [format (or (:format opts) "text")
        color? (and (not (:no-color opts))
                    (not= "never" (:color opts)))]
    (if (= "edn" format)
      (println-data status)
      (print (next-guide/render-plain status {:color? color?})))
    (flush)
    (when (= "check" command)
      (System/exit (next-guide/exit-code status)))))

(defn- tty? [stream]
  (try
    (require 'babashka.terminal)
    ((requiring-resolve 'babashka.terminal/tty?) stream)
    (catch Throwable _
      false)))

(defn- tui? [opts]
  (let [mode (or (:tui opts) "auto")]
    (case mode
      "never" false
      "always" true
      "auto" (and (tty? :stdin) (tty? :stdout))
      false)))

(defn- print-next-command! [opts status]
  (if (and (not= "edn" (:format opts))
           (tui? opts))
    (try
      (require 'bridge.tui)
      ((requiring-resolve 'bridge.tui/render-status!) status)
      (catch Throwable e
        (binding [*out* *err*]
          (println "TUI unavailable, falling back to plain output:" (ex-message e)))
        (print-status-command! "next" (assoc opts :tui "never") status)))
    (print-status-command! "next" opts status)))

(defn- print-init-command! [result]
  (println "🌉 Initializing Bridge in this repository...")
  (doseq [path (:created result)]
    (println "Created" path))
  (doseq [path (:updated result)]
    (println "Updated" path "to exclude Bridge artifact receipts"))
  (println)
  (println "Run `bb bridge next` to see your current verification status!"))

(defn- print-install-hooks-command! [result]
  (println (str "✅ Installed `bb bridge check --profile " (:profile result) "` into " (:installed result))))

(defn- print-help! []
  (println help-text))

(defn -main [& args]
  (try
    (let [{:keys [command options]} (parsed-command args)]
      (case command
        nil (print-help!)
        "help" (print-help!)
        "check" (print-status-command! command options (:bridge-check (dispatch args)))
        "next" (print-next-command! options (:bridge-next (dispatch args)))
        "init" (print-init-command! (dispatch args))
        "install-hooks" (print-install-hooks-command! (dispatch args))
        (println-data (dispatch args))))
    (catch Exception e
      (binding [*out* *err*]
        (println (ex-message e))
        (when-let [data (ex-data e)]
          (pprint/pprint data)
          (when (:help data)
            (println)
            (print-help!))))
      (*exit-fn* 1))))
