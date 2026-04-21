(ns bridge.artifact-roundtrip-test
  (:require [bridge.io :as bio]
            [bridge.schema :as schema]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

(defn temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory "bridge-artifact-rt" (make-array java.nio.file.attribute.FileAttribute 0))))

(def verification-brief
  {:artifact "verification-brief"
   :subject "planner-runtime"
   :category "distributed"
   :summary ["Stable stage order matters."]
   :concern-classes ["safety" "liveness"]
   :environment-assumptions ["scheduler"]
   :mechanism-families [{:name "stage-order"
                         :description "Stage order and barrier placement."
                         :concern-class "safety"
                         :verification-method "model-checkable"
                         :affected-paths ["src/planner.clj"]
                         :planned-evidence ["model-check" "trace-validation"]}]
   :co-model-with ["trace-runtime"]
   :non-goals ["UI behavior"]
   :handoff-outputs ["artifacts/planner-runtime-observable.yaml"]
   :open-questions ["Need stronger liveness policy split?"]})

(def observable-contract
  {:artifact "observable-contract"
   :subject "planner-runtime"
   :observables [{:name "stage-program"
                  :source {:path "src/planner.clj" :lines "10-50"}
                  :trigger-point "after"
                  :capture-timing "post-state"
                  :snapshot-strength "strong"
                  :collection-mode "replay"
                  :semantics-scope "both"
                  :captures ["stage-id" "op"]
                  :maps-to ["planner-order"]
                  :used-by ["trace validation"]
                  :requirement-ids ["planner.ORDER.1"]}]
   :constraints ["Trace wrapper may differ from base semantics."]
   :notes ["Generated for round-trip test."]})

(def completeness-ledger
  {:artifact "completeness-ledger"
   :subject "planner-runtime"
   :workflow-state "active"
   :areas {:contract {:status "present" :artifacts ["docs/contract.md"] :requirement-ids ["planner.ORDER.1"]}
           :implementation {:status "present" :artifacts ["src/planner.clj"]}
           :unit-tests {:status "present" :artifacts ["test/planner_test.clj"] :requirement-ids ["planner.ORDER.1"]}
           :property-tests {:status "waived" :artifacts []}
           :runtime-assertions {:status "deferred" :artifacts []}
           :formal-spec {:status "present" :artifacts ["specs/planner.tla"] :requirement-ids ["planner.ORDER.1"]}
           :differential-evidence {:status "missing" :artifacts []}
           :docs {:status "present" :artifacts ["docs/contract.md"] :requirement-ids ["planner.ORDER.1"]}}
   :notes []})

(def omission-record
  {:artifact "omission-decision-record"
   :decision-id "omit-1"
   :subject "planner-runtime"
   :omitted-item "property-tests"
   :status "deferred"
   :rationale ["Not yet in legacy rollout."]
   :compensating-controls ["TLA + unit tests"]
   :owner "verification"
   :review-trigger ["When planner DSL changes."]
   :related-artifacts ["artifacts/planner-runtime-completeness.yaml"]
   :requirement-ids ["planner.ORDER.1"]})

(def scope-ledger
  {:artifact "verification-scope-ledger"
   :subject "planner-runtime"
   :workflow-state "active"
   :coverage {:proved []
              :model-checked ["planner order"]
              :differential-tested ["comparison path"]
              :property-tested []
              :runtime-asserted []
              :documented-only ["error wording"]
              :intentionally-uncovered ["performance"]}
   :requirement-coverage {:model-checked ["planner.ORDER.1"]
                          :documented-only ["planner.ERROR.1"]}
   :boundaries ["Trace validation remains structural."]
   :follow-up ["Add confirmation evidence."]})

(def changelog
  {:artifact "verification-changelog"
   :subject "planner-runtime"
   :entries [{:phase "trace-validation"
              :kind "fix-trace"
              :summary "Adjusted trace wrapper."
              :status-effect "regressed"
              :related-artifacts ["artifacts/trace.yaml"]}]})

(def evaluation-profile
  {:artifact "evaluation-profile"
   :evaluation-id "eval-1"
   :subject "demo"
   :target-suite "local"
   :suite-kind "workflow"
   :working-directory "."
   :task-selector ["smoke"]
   :command-steps [{:id "metrics" :command "echo '{\"ok\":1}'" :parse-json-stdout? true}]
   :metric-mappings [{:external-key "metrics.ok" :bridge-key "ok" :kind "external"}]
   :bridge-metrics ["artifact-valid-count"]
   :output-dir "artifacts/evaluations"})

(def plan-seed
  {:artifact "plan-seed"
   :plan-seed/id "seed-1"
   :subject "planner-runtime"
   :workflow-state "draft"
   :steps [{:step 1 :kind "review-intent" :summary "Confirm inferred intent."}]
   :artifacts-to-update ["artifacts/brief.yaml"]
   :commands ["clojure -M:test"]})

(deftest round-trip-yaml-and-edn-artifacts
  (let [dir (temp-dir)
        brief-path (str (io/file dir "brief.yaml"))
        observe-path (str (io/file dir "observe.edn"))]
    (bio/write-data brief-path verification-brief)
    (bio/write-data observe-path observable-contract)
    (is (:valid? (schema/validate-file brief-path)))
    (is (:valid? (schema/validate-file observe-path)))
    (is (= verification-brief (bio/read-data brief-path)))
    (is (= observable-contract (bio/read-data observe-path)))))

(deftest validates-ledgers-changelog-and-eval-profile
  (doseq [artifact [verification-brief observable-contract completeness-ledger omission-record scope-ledger changelog evaluation-profile plan-seed]]
    (is (:valid? (schema/validate-artifact-data artifact)) (pr-str (:artifact artifact)))))
