(ns bridge.next-test
  (:require [bridge.io :as bio]
            [bridge.next :as next]
            [bridge.profile :as profile]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory "bridge-next-test" (make-array java.nio.file.attribute.FileAttribute 0))))

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

(defn profile [dir]
  {:kind "project-profile"
   :project-name "demo"
   :root-path (str dir)
   :code-paths ["src"]
   :docs-paths ["docs"]
   :formal-paths ["specs"]
   :test-paths ["test"]
   :artifact-paths {:root "artifacts"
                    :phases "artifacts/phases"
                    :evidence "artifacts/evidence"
                    :evaluations "artifacts/evaluations"}
   :canonical-commands [{:id "trace"
                         :kind "trace-validation"
                         :role "conformance"
                         :command "echo trace"}]
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

(deftest status-model-reports-open-obligations-and-suggestions
  (let [dir (temp-dir)
        _ (.mkdirs (io/file dir "src/runtime"))
        _ (.mkdirs (io/file dir "artifacts"))
        _ (spit (io/file dir "src/runtime/core.clj") "(ns runtime.core)")
        loaded (profile/normalize-profile (profile dir) (str (io/file dir "profile.edn")))
        status (next/build-status loaded {:changed-files ["src/runtime/core.clj"]})]
    (is (= "attention-required" (:status status)))
    (is (= ["src/runtime/core.clj"] (:changed-files status)))
    (is (seq (:open-obligations status)))
    (is (= {:op "bridge/run-evidence" :args {:id "trace"}}
           (select-keys (get-in status [:open-obligations 0 :actions 0])
                        [:op :args])))
    (is (= 1 (next/exit-code status)))
    (is (= "trace" (:evidence-id (next/next-action status))))
    (is (re-find #"Next Action:" (next/render-plain status)))
    (is (re-find #"Pending Obligations" (next/render-plain status)))))

(deftest status-model-reports-clear-when-no-change-or_existing-problems
  (let [dir (temp-dir)
        _ (.mkdirs (io/file dir "artifacts"))
        loaded (profile/normalize-profile (profile dir) (str (io/file dir "profile.edn")))
        status (next/build-status loaded {:changed-files []})]
    (is (= "clear" (:status status)))
    (is (= 0 (next/exit-code status)))
    (is (re-find #"All Clear" (next/render-plain status)))
    (is (re-find #"tracked subsystems/artifact subjects" (next/render-plain status)))))

(deftest status-model-includes-existing-regressions
  (let [dir (temp-dir)
        artifact-dir (io/file dir "artifacts")
        _ (.mkdirs artifact-dir)
        loaded (profile/normalize-profile (profile dir) (str (io/file dir "profile.edn")))]
    (bio/write-data (str (io/file artifact-dir "change.yaml"))
                    {:artifact "change-intent-card"
                     :change-id "c1"
                     :change-surface ["src/runtime/core.clj"]
                     :change-sources ["code-diff"]
                     :inferred-intent {:summary "x" :categories ["code-only"] :mechanism-families [] :concern-classes ["liveness"]}
                     :accepted-intent {:status "draft" :summary "pending"}
                     :semantic-scope {:contracts [] :subsystems ["runtime"]}
                     :risk-class "medium"
                     :workflow-state "active"
                     :missing-obligations ["Revalidate required evidence: trace-validation"]
                     :missing-obligations-structured [{:kind "evidence-rerun"
                                                       :subject "runtime"
                                                       :required-evidence ["trace-validation"]
                                                       :reason "Need fresh trace validation."}]
                     :stale-artifacts []
                     :open-questions []})
    (let [status (next/build-status loaded {:changed-files []})]
      (is (= "attention-required" (:status status)))
      (is (= "runtime" (get-in status [:subject-problems 0 :subject]))))))

(deftest status-model-renders-direct-failed-evidence-count
  (let [dir (temp-dir)
        artifact-dir (io/file dir "artifacts")
        _ (.mkdirs artifact-dir)
        loaded (profile/normalize-profile (profile dir) (str (io/file dir "profile.edn")))]
    (bio/write-data (str (io/file artifact-dir "trace.yaml"))
                    {:artifact "evidence-run"
                     :evidence-id "trace"
                     :subject "runtime"
                     :kind "trace-validation"
                     :role "conformance"
                     :execution-status "executed"
                     :evidence-status "failed"
                     :exit-code 0
                     :stdout-path "trace.stdout.log"
                     :stderr-path "trace.stderr.log"
                     :started-at "2026-04-23T00:00:00Z"
                     :finished-at "2026-04-23T00:00:01Z"
                     :duration-ms 1
                     :timeout-ms 300000
                     :timed-out? false
                     :command "echo FAIL"
                     :cwd "."
                     :failure-signals ["TraceAccepted false"]
                     :parsed-metrics {}
                     :subsystem-fingerprint (profile/subsystem-fingerprint loaded
                                                                           (profile/subsystem-by-name loaded "runtime"))})
    (let [status (next/build-status loaded {:changed-files []})
          rendered (next/render-plain status)]
      (is (= "attention-required" (:status status)))
      (is (= 1 (get-in status [:subject-problems 0 :failed-evidence-count])))
      (is (re-find #"evidence-failed=1" rendered)))))

(deftest resolve-changed-files-supports-git-diff-spec
  (let [dir (temp-dir)
        _ (init-git-repo! dir)
        _ (.mkdirs (io/file dir "src/runtime"))
        _ (spit (io/file dir "src/runtime/core.clj") "(ns runtime.core)")
        _ (run-git! dir "add" ".")
        _ (run-git! dir "commit" "-m" "initial")
        _ (spit (io/file dir "src/runtime/core.clj") "(ns runtime.core)\n(defn step [] :ok)\n")
        _ (run-git! dir "add" "src/runtime/core.clj")
        _ (run-git! dir "commit" "-m" "patch")
        loaded (profile/normalize-profile (profile dir) (str (io/file dir "profile.edn")))
        status (next/build-status loaded {:git-diff-spec "HEAD^1"})]
    (is (= ["src/runtime/core.clj"] (next/resolve-changed-files loaded {:git-diff-spec "HEAD^1"})))
    (is (= {:mode "git-diff" :spec "HEAD^1"} (:change-detection status)))
    (is (= "git-diff:HEAD^1" (get-in status [:intent :change-id])))
    (is (= "attention-required" (:status status)))))

(deftest git-diff-errors-on-invalid-revspec
  (let [dir (temp-dir)
        _ (init-git-repo! dir)
        loaded (profile/normalize-profile (profile dir) (str (io/file dir "profile.edn")))]
    (try
      (next/resolve-changed-files loaded {:git-diff-spec "not-a-ref"})
      (is false "expected git diff to fail")
      (catch clojure.lang.ExceptionInfo ex
        (is (= "Git diff failed" (ex-message ex)))
        (is (str/includes? (str (get-in (ex-data ex) [:argv])) "not-a-ref"))))))
