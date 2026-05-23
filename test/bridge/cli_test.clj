(ns bridge.cli-test
  (:require [bridge.cli :as cli]
            [bridge.io :as bio]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory "bridge-cli-test" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn run-git!
  [dir & args]
  (let [{:keys [exit err]} (bio/run-process {:argv (into ["git"] args)
                                             :cwd (str dir)
                                             :timeout-ms 10000})]
    (when-not (zero? exit)
      (throw (ex-info "git command failed" {:args args :stderr err}))))
  dir)

(defn init-git-repo! [dir]
  (run-git! dir "init")
  (run-git! dir "config" "user.name" "Bridge Tests")
  (run-git! dir "config" "user.email" "bridge-tests@example.com")
  dir)

(deftest cli-dispatches-help-and-template-list
  (is (string? (:help (cli/dispatch []))))
  (is (str/includes? (:help (cli/dispatch [])) "Stable commands:"))
  (is (str/includes? (:help (cli/dispatch [])) "Experimental commands:"))
  (is (str/includes? (:help (cli/dispatch [])) "Create .bridge/profile.edn"))
  (is (str/includes? (:help (cli/dispatch [])) "Show the next verification work"))
  (is (seq (:templates (cli/dispatch ["list-templates"])))))

(deftest cli-main-prints-help-as-text
  (let [help-output (with-out-str (cli/-main "help"))
        default-output (with-out-str (cli/-main))]
    (is (str/includes? help-output "Stable commands:"))
    (is (str/includes? help-output "Experimental commands:"))
    (is (not (str/includes? help-output "{:help")))
    (is (= help-output default-output))))

(deftest cli-main-prints-help-for-unknown-command
  (let [sw (java.io.StringWriter.)]
    (binding [cli/*exit-fn* (fn [_] nil)
              *err* sw]
      (cli/-main "wat"))
    (let [output (str sw)]
      (is (str/includes? output "Unknown command"))
      (is (str/includes? output "Stable commands:"))
      (is (str/includes? output "Experimental commands:")))))

(deftest cli-happy-path-init-stub-validate
  (let [dir (temp-dir)
        profile-path (str (io/file dir "profile.edn"))
        artifact-path (str (io/file dir "brief.yaml"))]
    (is (= profile-path (:written (cli/dispatch ["init-profile" "--path" profile-path]))))
    (is (= artifact-path (:written (cli/dispatch ["stub-artifact" "--kind" "verification-brief" "--out" artifact-path]))))
    (is (true? (get-in (cli/dispatch ["validate-artifact" artifact-path]) [:validation :valid?])))))

(deftest cli-init-profile-uses-ephemeral-bootstrap-layout
  (let [dir (temp-dir)
        profile-path (str (io/file dir "profile.edn"))]
    (is (= profile-path (:written (cli/dispatch ["init-profile" "--path" profile-path]))))
    (let [profile-data (bio/read-data profile-path)]
      (is (= ["core"] (mapv :name (:subsystems profile-data))))
      (is (= ".bridge/ephemeral" (get-in profile-data [:artifact-paths :root])))
      (is (= ".bridge/ephemeral/phases" (get-in profile-data [:artifact-paths :phases])))
      (is (= ".bridge/ephemeral/evidence" (get-in profile-data [:artifact-paths :evidence])))
      (is (= ".bridge/ephemeral/evaluations" (get-in profile-data [:artifact-paths :evaluations]))))))

(deftest cli-init-bootstraps-project-and-installs-hook
  (let [dir (temp-dir)
        profile-path (str (io/file dir ".bridge/profile.edn"))
        policy-path (str (io/file dir ".bridge/verification-policy.yaml"))
        gitignore-path (str (io/file dir ".gitignore"))
        hook-path (str (io/file dir ".git/hooks/pre-push"))]
    (.mkdirs (io/file dir ".git"))
    (let [init-result (cli/dispatch ["init" "--root" (str dir)])]
      (is (= [".bridge/profile.edn" ".bridge/verification-policy.yaml"]
             (:created init-result)))
      (is (= [{:op "bridge/next" :args {}}] (:next-actions init-result)))
      (is (not (contains? init-result :next-command))))
    (is (bio/exists? profile-path))
    (is (bio/exists? policy-path))
    (let [profile-data (bio/read-data profile-path)
          policy-data (bio/read-data policy-path)]
      (is (not (contains? profile-data :formal-paths)))
      (is (not (contains? (first (:subsystems profile-data)) :formal-globs)))
      (is (not (contains? (get-in policy-data [:rules 0 :required-evidence]) :formal-spec))))
    (is (str/includes? (bio/read-text gitignore-path) "/.bridge/ephemeral/"))
    (is (true? (get-in (cli/dispatch ["validate-artifact" policy-path]) [:validation :valid?])))
    (let [debug (cli/dispatch ["debug-profile" "--profile" profile-path])]
      (is (= (bio/absolute-path dir)
             (get-in debug [:debug-profile :normalized-paths :root-path]))))
    (let [intent (cli/dispatch ["analyze-change"
                                "--profile" profile-path
                                "--changed-file" ".bridge/profile.edn"
                                "--changed-file" ".gitignore"])]
      (is (= ["core"]
             (get-in intent [:change-intent :semantic-scope :subsystems])))
      (is (some #(str/includes? % "unit-tests")
                (get-in intent [:change-intent :missing-obligations]))))
    (is (= hook-path (:installed (cli/dispatch ["install-hooks" "--root" (str dir)]))))
    (is (str/includes? (bio/read-text hook-path) "bb bridge check --profile .bridge/profile.edn"))
    (is (.canExecute (io/file hook-path)))))

(deftest cli-main-prints-friendly-bootstrap-output
  (let [dir (temp-dir)
        _ (.mkdirs (io/file dir ".git"))
        init-output (with-out-str (cli/-main "init" "--root" (str dir)))
        hook-output (with-out-str (cli/-main "install-hooks" "--root" (str dir)))]
    (is (str/includes? init-output "🌉 Initializing Bridge in this repository..."))
    (is (str/includes? init-output "Created .bridge/profile.edn"))
    (is (str/includes? init-output "Run `bb bridge next`"))
    (is (not (str/includes? init-output "{:created")))
    (is (str/includes? hook-output "✅ Installed `bb bridge check --profile .bridge/profile.edn`"))))

(deftest cli-next-defaults-to-plain-text
  (let [dir (temp-dir)
        profile-path (str (io/file dir "profile.edn"))]
    (bio/write-data profile-path
                    {:kind "project-profile"
                     :project-name "demo"
                     :root-path "."
                     :code-paths ["src"] :docs-paths ["docs"] :formal-paths ["specs"] :test-paths ["test"]
                     :artifact-paths {:root "artifacts" :phases "artifacts/phases" :evidence "artifacts/evidence" :evaluations "artifacts/evaluations"}
                     :canonical-commands []
                     :subsystems []
                     :phases []})
    (let [output (with-out-str (cli/-main "next" "--profile" profile-path "--no-color"))]
      (is (str/includes? output "Bridge Status: All Clear"))
      (is (not (str/includes? output "{:bridge-next"))))))

(deftest cli-generates-brief-observable-and-debug-profile
  (let [dir (temp-dir)
        _ (.mkdirs (io/file dir "src/runtime"))
        _ (.mkdirs (io/file dir "docs"))
        _ (.mkdirs (io/file dir "artifacts"))
        _ (spit (io/file dir "src/runtime/core.clj") "(ns runtime.core)")
        _ (spit (io/file dir "docs/spec.md") "spec")
        profile-path (str (io/file dir "profile.edn"))
        policy-path (str (io/file dir "policy.yaml"))
        brief-path (str (io/file dir "out" "brief.yaml"))
        observe-path (str (io/file dir "out" "observe.yaml"))]
    (bio/write-data profile-path
                    {:kind "project-profile"
                     :project-name "demo"
                     :root-path "."
                     :code-paths ["src"] :docs-paths ["docs"] :formal-paths ["specs"] :test-paths ["test"]
                     :artifact-paths {:root "artifacts" :phases "artifacts/phases" :evidence "artifacts/evidence" :evaluations "artifacts/evaluations"}
                     :verification-policy-path "policy.yaml"
                     :canonical-commands [{:id "trace" :kind "trace-validation" :role "conformance" :command "echo trace"}]
                     :subsystems [{:name "runtime"
                                   :code-globs ["src/runtime/**/*.clj"]
                                   :docs-globs ["docs/**/*.md"]
                                   :formal-globs []
                                   :test-globs []
                                   :expected-artifacts ["verification-brief" "observable-contract"]
                                   :expected-evidence ["trace-validation"]
                                   :system-category "async-runtime"}]
                     :file-glob-rules [{:glob "src/runtime/**/*.clj" :subsystem "runtime" :mechanism-family "verification-harness" :concern-class "liveness"}]
                     :phases []})
    (bio/write-data policy-path
                    {:artifact "verification-policy"
                     :policy-id "demo"
                     :rules [{:scope {:subsystems ["runtime"] :concern-classes ["liveness"]}
                              :required-evidence {:unit-tests "optional"
                                                  :property-tests "optional"
                                                  :runtime-assertions "required"
                                                  :docs-or-nl-spec "required"
                                                  :formal-spec "optional"
                                                  :differential-tests "optional"
                                                  :trace-validation "recommended"
                                                  :benchmarks "optional"
                                                  :confirmation-evidence "optional"}
                              :evidence-roles {:conformance ["trace-validation"]}}]})
    (is (= brief-path (:written (cli/dispatch ["generate-brief" "--profile" profile-path "--changed-file" "src/runtime/core.clj" "--out" brief-path]))))
    (is (= observe-path (:written (cli/dispatch ["generate-observable" "--profile" profile-path "--subsystem" "runtime" "--out" observe-path]))))
    (let [debug (cli/dispatch ["debug-profile" "--profile" profile-path "--changed-file" "src/runtime/core.clj"])]
      (is (bio/absolute? (get-in debug [:debug-profile :normalized-paths :root-path])))
      (is (= ["runtime"]
             (mapv :name (get-in debug [:debug-profile :change-debug :matched-subsystems])))))
    (bio/write-data (str (io/file dir "artifacts" "runtime-change.yaml"))
                    {:artifact "change-intent-card"
                     :change-id "c1"
                     :change-surface ["src/runtime/core.clj"]
                     :change-sources ["code-diff"]
                     :inferred-intent {:summary "runtime" :categories ["code-only"] :mechanism-families ["wait-wake"] :concern-classes ["liveness"]}
                     :accepted-intent {:status "draft" :summary "pending"}
                     :semantic-scope {:contracts [] :subsystems ["runtime"]}
                     :risk-class "medium"
                     :workflow-state "active"
                     :missing-obligations []
                     :stale-artifacts []
                     :open-questions []})
    (is (true? (get-in (cli/dispatch ["validate-artifact" brief-path]) [:validation :valid?])))
    (is (true? (get-in (cli/dispatch ["validate-artifact" observe-path]) [:validation :valid?])))
    (is (= "demo" (get-in (cli/dispatch ["summary" "--profile" profile-path]) [:summary :project])))
    (is (= 1 (get-in (cli/dispatch ["query" "--profile" profile-path "runtime" "semantic-scope.subsystems"]) [:query :match-count])))))

(deftest cli-run-evidence-dry-run-shows-normalized-plan
  (let [dir (temp-dir)
        _ (.mkdirs (io/file dir "src/runtime"))
        profile-path (str (io/file dir "profile.edn"))]
    (bio/write-data profile-path
                    {:kind "project-profile"
                     :project-name "demo"
                     :root-path "."
                     :code-paths ["src"] :docs-paths ["docs"] :formal-paths ["specs"] :test-paths ["test"]
                     :artifact-paths {:root "artifacts" :phases "artifacts/phases" :evidence "artifacts/evidence" :evaluations "artifacts/evaluations"}
                     :canonical-commands [{:id "trace" :kind "trace-validation" :role "conformance" :cwd "src/runtime" :command "echo trace"}]
                     :subsystems [{:name "runtime"
                                   :code-globs ["src/runtime/**/*.clj"]
                                   :docs-globs []
                                   :formal-globs []
                                   :test-globs []
                                   :expected-artifacts ["verification-brief"]
                                   :system-category "async-runtime"}]
                     :phases []})
    (let [result (cli/dispatch ["run-evidence" "--profile" profile-path "--id" "trace" "--dry-run"])]
      (is (true? (get-in result [:result :dry-run?])))
      (is (bio/absolute? (get-in result [:result :cwd])))
      (is (bio/absolute? (get-in result [:result :output-root])))
      (is (= ["bash" "-lc"] (get-in result [:result :shell]))))))

(deftest cli-dispatches-check-and-next-status
  (let [dir (temp-dir)
        _ (.mkdirs (io/file dir "src/runtime"))
        _ (.mkdirs (io/file dir "artifacts"))
        _ (spit (io/file dir "src/runtime/core.clj") "(ns runtime.core)")
        profile-path (str (io/file dir "profile.edn"))]
    (bio/write-data profile-path
                    {:kind "project-profile"
                     :project-name "demo"
                     :root-path "."
                     :code-paths ["src"] :docs-paths ["docs"] :formal-paths ["specs"] :test-paths ["test"]
                     :artifact-paths {:root "artifacts" :phases "artifacts/phases" :evidence "artifacts/evidence" :evaluations "artifacts/evaluations"}
                     :canonical-commands [{:id "trace" :kind "trace-validation" :role "conformance" :command "echo trace"}]
                     :subsystems [{:name "runtime"
                                   :code-globs ["src/runtime/**/*.clj"]
                                   :docs-globs []
                                   :formal-globs []
                                   :test-globs []
                                   :expected-artifacts ["verification-brief"]
                                   :expected-evidence ["trace-validation"]
                                   :system-category "async-runtime"}]
                     :file-glob-rules [{:glob "src/runtime/**/*.clj"
                                        :subsystem "runtime"
                                        :mechanism-family "verification-harness"
                                        :concern-class "liveness"}]
                     :phases []})
    (let [check (cli/dispatch ["check" "--profile" profile-path "--changed-file" "src/runtime/core.clj"])
          next (cli/dispatch ["next" "--profile" profile-path "--changed-file" "src/runtime/core.clj"])]
      (is (= "attention-required" (get-in check [:bridge-check :status])))
      (is (= "attention-required" (get-in next [:bridge-next :status])))
      (is (seq (get-in check [:bridge-check :open-obligations])))
      (is (= {:op "bridge/run-evidence" :args {:id "trace"}}
             (select-keys (get-in next [:bridge-next :open-obligations 0 :actions 0])
                          [:op :args]))))))

(deftest cli-auto-dry-run-plans-without-writing-and-auto-runs-evidence
  (let [dir (temp-dir)
        _ (.mkdirs (io/file dir "src/runtime"))
        _ (.mkdirs (io/file dir "artifacts"))
        _ (spit (io/file dir "src/runtime/core.clj") "(ns runtime.core)")
        profile-path (str (io/file dir "profile.edn"))
        evidence-path (str (io/file dir "artifacts" "evidence" "trace.yaml"))]
    (bio/write-data profile-path
                    {:kind "project-profile"
                     :project-name "demo"
                     :root-path "."
                     :code-paths ["src"] :docs-paths ["docs"] :formal-paths ["specs"] :test-paths ["test"]
                     :artifact-paths {:root "artifacts" :phases "artifacts/phases" :evidence "artifacts/evidence" :evaluations "artifacts/evaluations"}
                     :canonical-commands [{:id "trace"
                                           :kind "trace-validation"
                                           :role "conformance"
                                           :command "printf '{:result {:kind \"trace-validation\" :role \"conformance\" :evidence-status \"passed\"}}'"
                                           :result-parser {:type "bridge-result"}}]
                     :subsystems [{:name "runtime"
                                   :code-globs ["src/runtime/**/*.clj"]
                                   :docs-globs []
                                   :formal-globs []
                                   :test-globs []
                                   :expected-artifacts ["verification-brief"]
                                   :expected-evidence ["trace-validation"]
                                   :system-category "async-runtime"}]
                     :file-glob-rules [{:glob "src/runtime/**/*.clj"
                                        :subsystem "runtime"
                                        :mechanism-family "verification-harness"
                                        :concern-class "liveness"}]
                     :phases []})
    (let [dry-run (cli/dispatch ["auto" "--profile" profile-path "--changed-file" "src/runtime/core.clj" "--dry-run"])]
      (is (= true (get-in dry-run [:auto :dry-run?])))
      (is (= "trace" (get-in dry-run [:auto :planned-actions 0 :evidence-id])))
      (is (= {:op "bridge/run-evidence" :args {:id "trace"}}
             (select-keys (get-in dry-run [:auto :planned-actions 0]) [:op :args])))
      (is (not (contains? (get-in dry-run [:auto :planned-actions 0]) :command)))
      (is (false? (bio/exists? evidence-path))))
    (let [result (cli/dispatch ["auto" "--profile" profile-path "--changed-file" "src/runtime/core.clj"])]
      (is (= false (get-in result [:auto :dry-run?])))
      (is (= false (get-in result [:auto :stopped?])))
      (is (= "trace" (get-in result [:auto :results 0 :result :evidence-id])))
      (is (= "passed" (get-in result [:auto :results 0 :result :evidence-status])))
      (is (true? (bio/exists? evidence-path))))))

(deftest cli-dispatches-check-with-policy-obligations
  (let [dir (temp-dir)
        _ (.mkdirs (io/file dir "src/bridge"))
        _ (.mkdirs (io/file dir "artifacts"))
        profile-path (str (io/file dir "profile.edn"))
        policy-path (str (io/file dir "policy.yaml"))]
    (spit (io/file dir "src/bridge/citation.clj") "(ns bridge.citation)")
    (bio/write-data profile-path
                    {:kind "project-profile"
                     :project-name "demo"
                     :root-path "."
                     :code-paths ["src"] :docs-paths ["docs"] :formal-paths ["specs"] :test-paths ["test"]
                     :artifact-paths {:root "artifacts" :phases "artifacts/phases" :evidence "artifacts/evidence" :evaluations "artifacts/evaluations"}
                     :verification-policy-path "policy.yaml"
                     :canonical-commands [{:id "unit" :kind "unit" :role "regression" :command "bb test"}]
                     :subsystems [{:name "core"
                                   :code-globs ["src/**/*"]
                                   :docs-globs ["docs/**/*"]
                                   :formal-globs []
                                   :test-globs ["test/**/*"]
                                   :expected-artifacts ["change-intent-card" "verification-brief" "completeness-ledger"]
                                   :expected-evidence ["unit"]
                                   :system-category "api"
                                   :risk-class "medium"}]
                     :phases []})
    (bio/write-data policy-path
                    {:artifact "verification-policy"
                     :policy-id "demo"
                     :rules [{:scope {:subsystems ["core"]
                                      :change-categories ["code-only" "mixed"]
                                      :risk-classes ["medium" "high"]}
                              :required-evidence {:unit-tests "required"
                                                  :property-tests "optional"
                                                  :runtime-assertions "optional"
                                                  :docs-or-nl-spec "recommended"}
                              :evidence-roles {:regression ["unit"]}}]})
    (let [check (cli/dispatch ["check" "--profile" profile-path "--changed-file" "src/bridge/citation.clj"])]
      (is (= "attention-required" (get-in check [:bridge-check :status])))
      (is (some #(str/includes? (:summary %) "unit-tests")
                (get-in check [:bridge-check :open-obligations]))))))

(deftest cli-check-treats-policy-change-as-staling-existing-evidence
  (let [dir (temp-dir)
        _ (.mkdirs (io/file dir "src/bridge"))
        _ (.mkdirs (io/file dir "test/bridge"))
        _ (.mkdirs (io/file dir "artifacts"))
        profile-path (str (io/file dir "profile.edn"))
        policy-path (str (io/file dir "policy.yaml"))]
    (spit (io/file dir "src/bridge/core.clj") "(ns bridge.core)")
    (spit (io/file dir "test/bridge/core_test.clj") "(ns bridge.core-test)")
    (bio/write-data profile-path
                    {:kind "project-profile"
                     :project-name "demo"
                     :root-path "."
                     :code-paths ["src"] :docs-paths ["docs"] :formal-paths ["specs"] :test-paths ["test"]
                     :artifact-paths {:root "artifacts" :phases "artifacts/phases" :evidence "artifacts/evidence" :evaluations "artifacts/evaluations"}
                     :verification-policy-path "policy.yaml"
                     :canonical-commands [{:id "unit" :kind "unit" :role "regression" :command "true"}]
                     :subsystems [{:name "core"
                                   :code-globs ["src/**/*"]
                                   :docs-globs []
                                   :formal-globs []
                                   :test-globs ["test/**/*"]
                                   :expected-artifacts ["change-intent-card"]
                                   :expected-evidence ["unit"]
                                   :system-category "api"
                                   :risk-class "medium"}]
                     :phases []})
    (bio/write-data policy-path
                    {:artifact "verification-policy"
                     :policy-id "demo"
                     :rules [{:scope {:subsystems ["core"]
                                      :change-categories ["mixed"]
                                      :risk-classes ["medium"]}
                              :required-evidence {:unit-tests "required"}
                              :evidence-roles {:regression ["unit"]}}]})
    (is (= "evidence-run"
           (get-in (cli/dispatch ["run-evidence" "--profile" profile-path "--id" "unit"])
                   [:result :artifact])))
    (Thread/sleep 10)
    (bio/write-data policy-path
                    {:artifact "verification-policy"
                     :policy-id "demo"
                     :rules [{:scope {:subsystems ["core"]
                                      :change-categories ["mixed"]
                                      :risk-classes ["medium"]}
                              :required-evidence {:unit-tests "required"
                                                  :docs-or-nl-spec "required"}
                              :evidence-roles {:regression ["unit"]}}]})
    (let [check (cli/dispatch ["check" "--profile" profile-path "--changed-file" "policy.yaml"])]
      (is (= "attention-required" (get-in check [:bridge-check :status])))
      (is (some #(str/includes? (:summary %) "unit-tests")
                (get-in check [:bridge-check :open-obligations])))
      (is (empty? (get-in check [:bridge-check :completed-evidence]))))))

(deftest cli-check-discovers-default-profile
  (let [dir (temp-dir)
        _ (.mkdirs (io/file dir "src/runtime"))
        _ (.mkdirs (io/file dir "artifacts"))
        _ (spit (io/file dir "src/runtime/core.clj") "(ns runtime.core)")
        profile-path (str (io/file dir "profile.edn"))]
    (bio/write-data profile-path
                    {:kind "project-profile"
                     :project-name "demo"
                     :root-path "."
                     :code-paths ["src"] :docs-paths ["docs"] :formal-paths ["specs"] :test-paths ["test"]
                     :artifact-paths {:root "artifacts" :phases "artifacts/phases" :evidence "artifacts/evidence" :evaluations "artifacts/evaluations"}
                     :canonical-commands [{:id "trace" :kind "trace-validation" :role "conformance" :command "echo trace"}]
                     :subsystems [{:name "runtime"
                                   :code-globs ["src/runtime/**/*.clj"]
                                   :docs-globs []
                                   :formal-globs []
                                   :test-globs []
                                   :expected-artifacts ["verification-brief"]
                                   :expected-evidence ["trace-validation"]
                                   :system-category "async-runtime"}]
                     :file-glob-rules [{:glob "src/runtime/**/*.clj"
                                        :subsystem "runtime"
                                        :mechanism-family "verification-harness"
                                        :concern-class "liveness"}]
                     :phases []})
    (with-redefs [cli/discover-profile-path (fn [] profile-path)]
      (is (= "attention-required"
             (get-in (cli/dispatch ["check" "--changed-file" "src/runtime/core.clj"])
                     [:bridge-check :status]))))))

(deftest cli-check-supports-git-diff-spec
  (let [dir (temp-dir)
        _ (init-git-repo! dir)
        _ (.mkdirs (io/file dir "src/runtime"))
        _ (.mkdirs (io/file dir "artifacts"))
        _ (spit (io/file dir "src/runtime/core.clj") "(ns runtime.core)")
        profile-path (str (io/file dir "profile.edn"))]
    (bio/write-data profile-path
                    {:kind "project-profile"
                     :project-name "demo"
                     :root-path "."
                     :code-paths ["src"] :docs-paths ["docs"] :formal-paths ["specs"] :test-paths ["test"]
                     :artifact-paths {:root "artifacts" :phases "artifacts/phases" :evidence "artifacts/evidence" :evaluations "artifacts/evaluations"}
                     :canonical-commands [{:id "trace" :kind "trace-validation" :role "conformance" :command "echo trace"}]
                     :subsystems [{:name "runtime"
                                   :code-globs ["src/runtime/**/*.clj"]
                                   :docs-globs []
                                   :formal-globs []
                                   :test-globs []
                                   :expected-artifacts ["verification-brief"]
                                   :expected-evidence ["trace-validation"]
                                   :system-category "async-runtime"}]
                     :file-glob-rules [{:glob "src/runtime/**/*.clj"
                                        :subsystem "runtime"
                                        :mechanism-family "verification-harness"
                                        :concern-class "liveness"}]
                     :phases []})
    (run-git! dir "add" ".")
    (run-git! dir "commit" "-m" "initial")
    (spit (io/file dir "src/runtime/core.clj") "(ns runtime.core)\n(defn step [] :ok)\n")
    (run-git! dir "add" "src/runtime/core.clj")
    (run-git! dir "commit" "-m" "patch")
    (let [check (cli/dispatch ["check" "--profile" profile-path "--git-diff" "HEAD^1"])]
      (is (= "attention-required" (get-in check [:bridge-check :status])))
      (is (= ["src/runtime/core.clj"] (get-in check [:bridge-check :changed-files])))
      (is (= {:mode "git-diff" :spec "HEAD^1"}
             (get-in check [:bridge-check :change-detection]))))))

(deftest cli-rejects-mixed-change-selectors
  (let [dir (temp-dir)
        profile-path (str (io/file dir "profile.edn"))]
    (bio/write-data profile-path
                    {:kind "project-profile"
                     :project-name "demo"
                     :root-path "."
                     :code-paths ["src"] :docs-paths ["docs"] :formal-paths ["specs"] :test-paths ["test"]
                     :artifact-paths {:root "artifacts" :phases "artifacts/phases" :evidence "artifacts/evidence" :evaluations "artifacts/evaluations"}
                     :canonical-commands []
                     :subsystems []
                     :phases []})
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Use either --changed-file or --git-diff"
         (cli/dispatch ["check" "--profile" profile-path "--changed-file" "src/foo.clj" "--git-diff" "HEAD^1"])))))

(deftest cli-coverage-as-evidence-reports-unlinked-requirements
  (let [dir (temp-dir)
        _ (.mkdirs (io/file dir "features"))
        _ (.mkdirs (io/file dir "artifacts"))
        profile-path (str (io/file dir "profile.edn"))]
    (bio/write-data (str (io/file dir "features" "demo.feature.yaml"))
                    {:feature {:name "demo" :product "demo"}
                     :components {:CORE {:requirements {1 "Maps requirement to implementation"}}}})
    (bio/write-data profile-path
                    {:kind "project-profile"
                     :project-name "demo"
                     :root-path "."
                     :code-paths ["src"] :docs-paths ["docs"] :formal-paths ["specs"] :test-paths ["test"]
                     :artifact-paths {:root "artifacts" :phases "artifacts/phases" :evidence "artifacts/evidence" :evaluations "artifacts/evaluations"}
                     :canonical-commands [{:id "nl-spec-trace"
                                           :kind "docs-or-nl-spec"
                                           :role "spec-traceability"
                                           :command (str "cd " (bio/absolute-path ".")
                                                         " && bb bridge coverage-as-evidence --profile " profile-path)
                                           :result-parser {:type "bridge-result"}}]
                     :subsystems []
                     :phases []
                     :requirement-sources [{:kind :acai-feature-yaml
                                            :globs ["features/**/*.feature.yaml"]
                                            :id-scheme :acid}]})
    (let [direct (cli/dispatch ["coverage-as-evidence" "--profile" profile-path])
          evidence (cli/dispatch ["run-evidence" "--profile" profile-path "--id" "nl-spec-trace"])]
      (is (= "docs-or-nl-spec" (get-in direct [:result :kind])))
      (is (= "failed" (get-in direct [:result :evidence-status])))
      (is (= 1 (get-in direct [:result :parsed-metrics :requirements-unlinked])))
      (is (= ["demo.CORE.1"] (get-in direct [:result :related-paths :unlinked-ids])))
      (is (= "docs-or-nl-spec" (get-in evidence [:result :kind])))
      (is (= "spec-traceability" (get-in evidence [:result :role])))
      (is (= "failed" (get-in evidence [:result :evidence-status])))
      (is (= 1 (get-in evidence [:result :parsed-metrics :requirements-unlinked]))))))

(deftest cli-init-profile-infers-commands
  (let [dir (temp-dir)
        bb-path (io/file dir "bb.edn")
        deps-path (io/file dir "deps.edn")
        profile-path (str (io/file dir "profile.edn"))]
    ;; Case 1: no files present
    (cli/dispatch ["init-profile" "--path" profile-path])
    (is (= [] (:canonical-commands (bio/read-data profile-path))))

    ;; Case 2: bb.edn present with test task
    (spit bb-path "{:tasks {test {:task (runner/-main)}}}")
    (cli/dispatch ["init-profile" "--path" profile-path])
    (is (= [{:id "unit" :kind "unit" :role "regression" :command "bb test" :description "Run unit tests"}]
           (:canonical-commands (bio/read-data profile-path))))

    ;; Case 3: bb.edn present without test task
    (spit bb-path "{:tasks {other {:task (runner/-main)}}}")
    (cli/dispatch ["init-profile" "--path" profile-path])
    (is (= [] (:canonical-commands (bio/read-data profile-path))))

    ;; Case 4: deps.edn present with :test alias
    (spit bb-path "{}") ;; disable bb.edn test task
    (spit deps-path "{:aliases {:test {:extra-paths [\"test\"]}}}")
    (cli/dispatch ["init-profile" "--path" profile-path])
    (is (= [{:id "unit" :kind "unit" :role "regression" :command "clojure -M:test" :description "Run unit tests"}]
           (:canonical-commands (bio/read-data profile-path))))

    ;; Case 5: deps.edn present without :test alias
    (spit deps-path "{:aliases {:other {}}}")
    (cli/dispatch ["init-profile" "--path" profile-path])
    (is (= [] (:canonical-commands (bio/read-data profile-path))))))
