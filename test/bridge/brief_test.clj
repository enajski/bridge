(ns bridge.brief-test
  (:require [bridge.brief :as brief]
            [bridge.io :as bio]
            [bridge.policy :as policy]
            [bridge.profile :as profile]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(defn temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory "bridge-brief-test" (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest generates-brief-and-plan-seed
  (let [dir (temp-dir)
        _ (.mkdirs (io/file dir "src/planner"))
        _ (.mkdirs (io/file dir "docs"))
        _ (.mkdirs (io/file dir "test/planner"))
        _ (spit (io/file dir "src/planner/core.clj") "(ns planner.core)")
        _ (spit (io/file dir "docs/spec.md") "spec")
        _ (spit (io/file dir "test/planner/core_test.clj") "(ns planner.core-test)")
        profile-path (str (io/file dir "profile.edn"))
        policy-path (str (io/file dir "policy.yaml"))]
    (bio/write-data profile-path
                    {:kind "project-profile"
                     :project-name "demo"
                     :root-path "."
                     :code-paths ["src"] :docs-paths ["docs"] :formal-paths ["specs"] :test-paths ["test"]
                     :artifact-paths {:root "artifacts" :phases "artifacts/phases" :evidence "artifacts/evidence" :evaluations "artifacts/evaluations"}
                     :canonical-commands [{:id "trace" :kind "trace-validation" :command "echo trace"}
                                          {:id "mc" :kind "model-check" :command "echo mc"}]
                     :subsystems [{:name "planner-runtime"
                                   :code-globs ["src/planner/**/*.clj"]
                                   :docs-globs ["docs/**/*.md"]
                                   :formal-globs ["specs/**/*.tla"]
                                   :test-globs ["test/planner/**/*.clj"]
                                   :expected-artifacts ["verification-brief"]
                                   :expected-evidence ["trace-validation" "model-check"]
                                   :system-category "distributed"
                                   :risk-class "high"
                                   :environment-assumptions ["scheduler"]}]
                     :file-glob-rules [{:glob "src/planner/**/*.clj"
                                        :subsystem "planner-runtime"
                                        :mechanism-family "trace-conversion"
                                        :concern-class "safety"
                                        :contract "planner-order"
                                        :formal-module "specs/planner.tla"
                                        :test-path "test/planner/core_test.clj"}]
                     :phases []})
    (bio/write-data policy-path
                    {:artifact "verification-policy"
                     :policy-id "demo"
                     :rules [{:scope {:subsystems ["planner-runtime"]
                                      :concern-classes ["safety"]}
                              :required-evidence {:unit-tests "required"
                                                  :property-tests "optional"
                                                  :runtime-assertions "optional"
                                                  :docs-or-nl-spec "required"
                                                  :formal-spec "optional"
                                                  :differential-tests "optional"
                                                  :trace-validation "optional"
                                                  :benchmarks "optional"
                                                  :confirmation-evidence "optional"}}]})
    (let [profile (profile/load-profile profile-path)
          pol (policy/load-policy policy-path)
          generated (brief/generate-brief profile pol {:changed-files ["src/planner/core.clj"]})
          seed (brief/plan-seed profile pol {:changed-files ["src/planner/core.clj"] :change-id "chg-1"})]
      (is (= "verification-brief" (:artifact generated)))
      (is (= "planner-runtime" (:subject generated)))
      (is (= "distributed" (:category generated)))
      (is (seq (:mechanism-families generated)))
      (is (some #(not= "code-review-only" (:verification-method %)) (:mechanism-families generated)))
      (is (some #(some #{"trace-validation" "model-check"} (:planned-evidence %))
                (:mechanism-families generated)))
      (is (= "chg-1" (:plan-seed/id seed)))
      (is (seq (:steps seed))))))

(deftest finiteness-hints-detect-distributed-patterns
  (let [detect-fn @(resolve 'bridge.brief/detect-finiteness-hints)]
    ;; Distributed system with channels, terms, logs
    (let [hints (detect-fn "distributed"
                  ["Network uses Bag channels with bounded capacity"
                   "currentTerm increments each election"
                   "Log entries appended to sequence"
                   "commitIndex advances monotonically"])]
      (is (>= (count hints) 3) "Should detect channel, term, log, and commit patterns")
      (is (some #(= "BufferSize" (:suggested-constant %)) hints))
      (is (some #(= "MaxTerm" (:suggested-constant %)) hints))
      (is (some #(= "MaxLogLen" (:suggested-constant %)) hints)))
    ;; Simple system with no patterns
    (let [hints (detect-fn "shared-memory" ["Scheduler is preemptive"])]
      (is (empty? hints) "No finiteness hints for simple shared-memory system"))
    ;; Counter pattern
    (let [hints (detect-fn "other" ["Stream counter increments monotonically"])]
      (is (some #(= "MaxCounter" (:suggested-constant %)) hints)))))
