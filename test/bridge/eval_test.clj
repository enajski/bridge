(ns bridge.eval-test
  (:require [bridge.eval :as beval]
            [bridge.io :as bio]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(defn temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory "bridge-eval-test" (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest eval-preserves-truth-for-exit-zero-but-failed-output
  (let [dir (temp-dir)
        profile-path (str (io/file dir "eval.yaml"))
        _ (bio/write-data profile-path
                          {:artifact "evaluation-profile"
                           :evaluation-id "eval-1"
                           :subject "demo"
                           :target-suite "local"
                           :suite-kind "workflow"
                           :working-directory (.getPath dir)
                           :task-selector ["smoke"]
                           :command-steps [{:id "trace"
                                            :kind "trace-validation"
                                            :command "printf 'Trace validation: ✗ FAIL\nPostcondition TraceAccepted is false.'"
                                            :result-parser {:type "regex-status"
                                                            :rules [{:stream "stdout" :regex "Trace validation: ✓ PASS" :status "passed"}
                                                                    {:stream "stdout" :regex "Trace validation: ✗ FAIL" :status "failed"}]
                                                            :captures [{:stream "stdout" :regex "Postcondition TraceAccepted[^\\n]*false\\." :as "primary-failure" :failure-signal? true}]}}
                                           {:id "inv"
                                            :kind "model-check"
                                            :command "printf 'Semantics Evaluation Results: ✓ PASS'"
                                            :result-parser {:type "regex-status"
                                                            :rules [{:stream "stdout" :regex "Semantics Evaluation Results: ✓ PASS" :status "passed"}
                                                                    {:stream "stdout" :regex "Semantics Evaluation Results: ✗ FAIL" :status "failed"}]}}]
                           :metric-mappings []
                           :bridge-metrics ["artifact-valid-count"]
                           :output-dir "artifacts/evaluations"})
        {:keys [report path]} (beval/run-evaluation (beval/load-evaluation-profile profile-path) {})]
    (is (bio/exists? path))
    (is (= "evaluation-report" (:artifact report)))
    (is (= "executed" (:execution-status report)))
    (is (= "failed" (:verification-status report)))
    (is (= "regressed" (:status report)))
    (is (= "failed" (get-in report [:task-results 0 :evidence-status])))
    (is (= "passed" (get-in report [:task-results 1 :evidence-status])))
    (is (= "Postcondition TraceAccepted is false."
           (get-in report [:failure-summary :primary-signal])))))

(deftest eval-marks-parserless-success-as-unknown-not-converged
  (let [dir (temp-dir)
        profile-path (str (io/file dir "eval.yaml"))
        _ (bio/write-data profile-path
                          {:artifact "evaluation-profile"
                           :evaluation-id "eval-2"
                           :subject "demo"
                           :target-suite "local"
                           :suite-kind "workflow"
                           :working-directory (.getPath dir)
                           :task-selector ["smoke"]
                           :command-steps [{:id "metrics"
                                            :command "echo '{\"compilation_check\":1}'"
                                            :parse-json-stdout? true}]
                           :metric-mappings [{:external-key "metrics.compilation_check"
                                              :bridge-key "compilation-check"
                                              :kind "external"}]
                           :bridge-metrics ["artifact-valid-count"]
                           :output-dir "artifacts/evaluations"})
        {:keys [report]} (beval/run-evaluation (beval/load-evaluation-profile profile-path) {})]
    (is (= "executed" (:execution-status report)))
    (is (= "partial" (:verification-status report)))
    (is (= "active" (:status report)))
    (is (= "unknown" (get-in report [:task-results 0 :evidence-status])))
    (is (= 1 (get-in report [:external-metrics :metrics.compilation_check])))))

(deftest eval-auto-consumes-bridge-run-evidence-output
  (let [dir (temp-dir)
        profile-path (str (io/file dir "eval.yaml"))
        _ (bio/write-data profile-path
                          {:artifact "evaluation-profile"
                           :evaluation-id "eval-3"
                           :subject "demo"
                           :target-suite "local"
                           :suite-kind "workflow"
                           :working-directory (.getPath dir)
                           :task-selector ["smoke"]
                           :command-steps [{:id "wrapped-evidence"
                                            :command "printf '%s' '{:result {:kind \"trace-validation\" :role \"conformance\" :evidence-status \"failed\" :failure-signals [\"TraceAccepted false\"]}}'"}]
                           :metric-mappings []
                           :bridge-metrics ["artifact-valid-count"]
                           :output-dir "artifacts/evaluations"})
        {:keys [report]} (beval/run-evaluation (beval/load-evaluation-profile profile-path) {})]
    (is (= "failed" (:verification-status report)))
    (is (= "regressed" (:status report)))
    (is (= "trace-validation" (get-in report [:task-results 0 :kind])))
    (is (= "conformance" (get-in report [:task-results 0 :role])))
    (is (= "failed" (get-in report [:task-results 0 :evidence-status])))
    (is (= "TraceAccepted false" (get-in report [:failure-summary :primary-signal])))))

(deftest load-evaluation-profile-normalizes-relative-working-directory
  (let [dir (temp-dir)
        profiles-dir (io/file dir "profiles")
        work-dir (io/file dir "work")
        profile-path (str (io/file profiles-dir "eval.yaml"))]
    (.mkdirs profiles-dir)
    (.mkdirs work-dir)
    (bio/write-data profile-path
                    {:artifact "evaluation-profile"
                     :evaluation-id "eval-4"
                     :subject "demo"
                     :target-suite "local"
                     :suite-kind "workflow"
                     :working-directory "../work"
                     :task-selector ["smoke"]
                     :command-steps [{:id "pwd" :command "pwd"}]
                     :metric-mappings []
                     :bridge-metrics ["artifact-valid-count"]
                     :output-dir "artifacts/evaluations"})
    (let [profile (beval/load-evaluation-profile profile-path)]
      (is (= (.getCanonicalPath work-dir) (:working-directory profile)))
      (is (.startsWith (:output-dir profile) (.getCanonicalPath work-dir))))))
