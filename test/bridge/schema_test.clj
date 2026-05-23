(ns bridge.schema-test
  (:require [bridge.schema :as schema]
            [clojure.test :refer [deftest is testing]]))

(def good-brief
  {:artifact "verification-brief"
   :subject "planner"
   :category "distributed"
   :summary ["Plan-driven stage execution."]
   :concern-classes ["safety"]
   :environment-assumptions ["scheduler"]
   :mechanism-families [{:name "stage-order"
                         :description "Stage order must be stable."
                         :concern-class "safety"
                         :verification-method "model-checkable"
                         :affected-paths ["src/planner.clj"]
                         :planned-evidence ["model-check" "unit"]}]
   :co-model-with []
   :non-goals []
   :handoff-outputs ["artifacts/planner-brief.yaml"]
   :open-questions []})

(deftest validates-known-artifact
  (let [result (schema/validate-artifact-data good-brief)]
    (is (:valid? result))
    (is (empty? (:errors result)))))

(deftest detects-missing-required-unknown-and-bad-enum
  (let [result (schema/validate-artifact-data
                 (-> good-brief
                     (dissoc :subject)
                     (assoc :category "weird")
                     (assoc :surprise true)))]
    (is (false? (:valid? result)))
    (is (some #(= :missing-required (:type %)) (:errors result)))
    (is (some #(= :unknown-field (:type %)) (:errors result)))
    (is (some #(= :invalid-enum (:type %)) (:errors result)))))

(deftest can-generate-stubs
  (let [stub (schema/stub-artifact "change-intent-card")]
    (is (= "change-intent-card" (:artifact stub)))
    (is (contains? stub :change-id))))

(def sandboxed-policy
  {:artifact "verification-policy"
   :policy-id "sandboxed"
   :bridge-path-sandbox {:enforce? true
                         :default-access "read-write"
                         :rules [{:path-pattern ".bridge/verification-policy.yaml"
                                  :access "read-only"
                                  :reason "Policy changes require human approval."}
                                 {:path-pattern ".bridge/ephemeral/evidence-runs/**"
                                  :access "read-only"}]}
   :rules [{:scope {:subsystems ["runtime"]}
            :required-evidence {:unit-tests "required"}}]})

(deftest validates-verification-policy-bridge-path-sandbox
  (let [result (schema/validate-artifact-data sandboxed-policy)]
    (is (:valid? result))
    (is (empty? (:errors result)))))

(deftest rejects-invalid-bridge-path-sandbox-access
  (let [result (schema/validate-artifact-data
                 (assoc-in sandboxed-policy
                           [:bridge-path-sandbox :rules 0 :access]
                           "admin"))]
    (is (false? (:valid? result)))
    (is (some #(= :invalid-enum (:type %)) (:errors result)))))
