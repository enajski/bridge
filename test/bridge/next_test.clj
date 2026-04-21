(ns bridge.next-test
  (:require [bridge.io :as bio]
            [bridge.next :as next]
            [bridge.profile :as profile]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(defn temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory "bridge-next-test" (make-array java.nio.file.attribute.FileAttribute 0))))

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
    (is (= "trace" (get-in status [:open-obligations 0 :commands 0 :id])))
    (is (= 1 (next/exit-code status)))
    (is (re-find #"Pending Obligations" (next/render-plain status)))))

(deftest status-model-reports-clear-when-no-change-or_existing-problems
  (let [dir (temp-dir)
        _ (.mkdirs (io/file dir "artifacts"))
        loaded (profile/normalize-profile (profile dir) (str (io/file dir "profile.edn")))
        status (next/build-status loaded {:changed-files []})]
    (is (= "clear" (:status status)))
    (is (= 0 (next/exit-code status)))
    (is (re-find #"All Clear" (next/render-plain status)))))

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
