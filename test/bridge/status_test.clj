(ns bridge.status-test
  (:require [bridge.io :as bio]
            [bridge.status :as status]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(defn temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory "bridge-status-test" (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest computes-convergence-from-artifacts
  (let [dir (temp-dir)
        artifact-dir (str (io/file dir "artifacts"))
        _ (.mkdirs (io/file artifact-dir))]
    (bio/write-data (str (io/file artifact-dir "change.yaml"))
                    {:artifact "change-intent-card"
                     :change-id "c1"
                     :change-surface ["src/foo.clj"]
                     :change-sources ["code-diff"]
                     :inferred-intent {:summary "x" :categories ["code-only"] :mechanism-families [] :concern-classes ["safety"]}
                     :accepted-intent {:status "draft" :summary "pending"}
                     :semantic-scope {:contracts [] :subsystems ["planner-runtime"]}
                     :risk-class "high"
                     :workflow-state "active"
                     :missing-obligations ["Revalidate required evidence: trace-validation"]
                     :missing-obligations-structured [{:kind "evidence-rerun"
                                                       :subject "planner-runtime"
                                                       :required-evidence ["trace-validation"]
                                                       :reason "Need fresh trace validation."}]
                     :stale-artifacts []
                     :open-questions []})
    (bio/write-data (str (io/file artifact-dir "eval.yaml"))
                    {:artifact "evaluation-report"
                     :evaluation-id "e1"
                     :subject "planner-runtime"
                     :status "regressed"
                     :execution-status "executed"
                     :verification-status "failed"
                     :started-at "2026-04-23T00:00:00Z"
                     :finished-at "2026-04-23T00:00:01Z"
                     :task-results [{:id "trace"
                                     :kind "trace-validation"
                                     :exit-code 0
                                     :status "ok"
                                     :execution-status "executed"
                                     :evidence-status "failed"
                                     :failure-signals ["TraceAccepted false"]}]
                     :external-metrics {}
                     :bridge-metrics {}
                     :outcome-labels ["suspected"]
                     :related-artifacts []
                     :notes []})
    (let [report (status/convergence-report artifact-dir)]
      (is (= 1 (:regressed-subject-count report)))
      (is (= "regressed" (get-in report [:subjects "planner-runtime" :workflow-state])))
      (is (= 1 (get-in report [:subjects "planner-runtime" :failed-obligation-count])))
      (is (= "failed" (get-in report [:subjects "planner-runtime" :verification-status]))))))

(deftest plain-missing-obligations-keep-subject-active
  (let [dir (temp-dir)
        artifact-dir (str (io/file dir "artifacts"))
        _ (.mkdirs (io/file artifact-dir))]
    (bio/write-data (str (io/file artifact-dir "legacy-change.yaml"))
                    {:artifact "change-intent-card"
                     :change-id "c1"
                     :change-surface ["src/foo.clj"]
                     :change-sources ["code-diff"]
                     :inferred-intent {:summary "x" :categories ["code-only"] :mechanism-families [] :concern-classes ["safety"]}
                     :accepted-intent {:status "draft" :summary "pending"}
                     :semantic-scope {:contracts [] :subsystems ["planner-runtime"]}
                     :risk-class "high"
                     :workflow-state "draft"
                     :missing-obligations ["Revalidate required evidence: trace-validation"]
                     :stale-artifacts []
                     :open-questions []})
    (let [report (status/convergence-report artifact-dir)]
      (is (= "active" (get-in report [:subjects "planner-runtime" :workflow-state])))
      (is (= 1 (get-in report [:subjects "planner-runtime" :open-obligation-count]))))))

(deftest direct-evidence-runs-close-and-fail-obligations
  (let [dir (temp-dir)
        passing-dir (str (io/file dir "passing"))
        failing-dir (str (io/file dir "failing"))]
    (.mkdirs (io/file passing-dir))
    (.mkdirs (io/file failing-dir))
    (doseq [artifact-dir [passing-dir failing-dir]]
      (bio/write-data (str (io/file artifact-dir "change.yaml"))
                      {:artifact "change-intent-card"
                       :change-id "c1"
                       :change-surface ["src/foo.clj"]
                       :change-sources ["code-diff"]
                       :inferred-intent {:summary "x" :categories ["code-only"] :mechanism-families [] :concern-classes ["safety"]}
                       :accepted-intent {:status "draft" :summary "pending"}
                       :semantic-scope {:contracts [] :subsystems ["planner-runtime"]}
                       :risk-class "high"
                       :workflow-state "active"
                       :missing-obligations ["Revalidate required evidence: trace-validation"]
                       :missing-obligations-structured [{:kind "evidence-rerun"
                                                         :subject "planner-runtime"
                                                         :required-evidence ["trace-validation"]
                                                         :reason "Need fresh trace validation."}]
                       :stale-artifacts []
                       :open-questions []}))
    (bio/write-data (str (io/file passing-dir "trace.yaml"))
                    {:artifact "evidence-run"
                     :evidence-id "trace"
                     :subject "planner-runtime"
                     :kind "trace-validation"
                     :execution-status "executed"
                     :evidence-status "passed"
                     :exit-code 0
                     :stdout-path "trace.stdout.log"
                     :stderr-path "trace.stderr.log"
                     :started-at "2026-04-23T00:00:00Z"
                     :finished-at "2026-04-23T00:00:01Z"
                     :duration-ms 1
                     :timeout-ms 300000
                     :timed-out? false
                     :command "echo PASS"
                     :cwd "."
                     :failure-signals []
                     :parsed-metrics {}
                     :subsystem-fingerprint "mockfp123"})
    (bio/write-data (str (io/file failing-dir "trace.yaml"))
                    {:artifact "evidence-run"
                     :evidence-id "trace"
                     :subject "planner-runtime"
                     :kind "trace-validation"
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
                     :subsystem-fingerprint "mockfp123"})
    (is (= "converged" (get-in (status/convergence-report passing-dir)
                               [:subjects "planner-runtime" :workflow-state])))
    (is (= "regressed" (get-in (status/convergence-report failing-dir)
                               [:subjects "planner-runtime" :workflow-state])))
    (is (= 1 (get-in (status/convergence-report failing-dir)
                     [:subjects "planner-runtime" :failed-obligation-count])))
    (is (= 1 (get-in (status/convergence-report failing-dir)
                     [:subjects "planner-runtime" :failed-evidence-count])))))

(deftest direct-failed-evidence-regresses-subject-without-obligation
  (let [dir (temp-dir)
        artifact-dir (str (io/file dir "artifacts"))]
    (.mkdirs (io/file artifact-dir))
    (bio/write-data (str (io/file artifact-dir "nl-spec-trace.yaml"))
                    {:artifact "evidence-run"
                     :evidence-id "nl-spec-trace"
                     :subject "bridge"
                     :kind "docs-or-nl-spec"
                     :role "spec-traceability"
                     :execution-status "executed"
                     :evidence-status "failed"
                     :exit-code 0
                     :stdout-path "nl-spec-trace.stdout.log"
                     :stderr-path "nl-spec-trace.stderr.log"
                     :started-at "2026-04-23T00:00:00Z"
                     :finished-at "2026-04-23T00:00:01Z"
                     :duration-ms 1
                     :timeout-ms 300000
                     :timed-out? false
                     :command "bb bridge coverage-as-evidence"
                     :cwd "."
                     :failure-signals ["11 unlinked requirements"]
                     :parsed-metrics {:requirements-unlinked 11}
                     :subsystem-fingerprint "mockfp123"})
    (let [report (status/convergence-report artifact-dir)]
      (is (= "regressed" (get-in report [:subjects "bridge" :workflow-state])))
      (is (= 0 (get-in report [:subjects "bridge" :failed-obligation-count])))
      (is (= 1 (get-in report [:subjects "bridge" :failed-evidence-count]))))))
