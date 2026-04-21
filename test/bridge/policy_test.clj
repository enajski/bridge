(ns bridge.policy-test
  (:require [bridge.policy :as policy]
            [clojure.test :refer [deftest is]]))

(def sample-policy
  {:artifact "verification-policy"
   :policy-id "demo"
   :rules [{:scope {:subsystems ["runtime"]
                    :concern-classes ["liveness"]}
            :required-evidence {:unit-tests "optional"
                                :property-tests "required"
                                :runtime-assertions "required"
                                :docs-or-nl-spec "optional"
                                :formal-spec "optional"
                                :differential-tests "optional"
                                :trace-validation "optional"
                                :benchmarks "optional"
                                :confirmation-evidence "optional"}}
           {:scope {:subsystems ["runtime"]
                    :concern-classes ["safety"]}
            :required-evidence {:unit-tests "required"
                                :property-tests "optional"
                                :runtime-assertions "optional"
                                :docs-or-nl-spec "required"
                                :formal-spec "required"
                                :differential-tests "optional"
                                :trace-validation "optional"
                                :benchmarks "optional"
                                :confirmation-evidence "optional"}}]})

(deftest concern-class-scoping-changes-obligations
  (let [liveness-view (policy/derive-policy sample-policy {:subsystems ["runtime"]
                                                           :change-categories ["code-only"]
                                                           :system-categories ["async-runtime"]
                                                           :risk-class "high"
                                                           :concern-classes ["liveness"]})
        safety-view (policy/derive-policy sample-policy {:subsystems ["runtime"]
                                                         :change-categories ["code-only"]
                                                         :system-categories ["async-runtime"]
                                                         :risk-class "high"
                                                         :concern-classes ["safety"]})]
    (is (= "required" (get-in liveness-view [:required-evidence :property-tests])))
    (is (= "required" (get-in safety-view [:required-evidence :unit-tests])))
    (is (some #(= "Revalidate required evidence: unit-tests" %)
              (policy/missing-obligations safety-view #{:code} #{:unit-tests :docs-or-nl-spec :formal-spec})))
    (is (some #(= "Missing required evidence: property-tests" %)
              (policy/missing-obligations liveness-view #{:code} #{})))))

(deftest normalizes-keyword-enum-values-and-forbidden-evidence
  (let [policy {:artifact "verification-policy"
                :policy-id "keywords"
                :rules [{:scope {:subsystems [:runtime]
                                 :change-categories [:code-only]
                                 :system-categories [:async-runtime]
                                 :risk-classes [:high]
                                 :concern-classes [:safety]}
                         :required-evidence {:unit-tests :forbidden
                                             :property-tests :optional
                                             :runtime-assertions :optional
                                             :docs-or-nl-spec :optional
                                             :formal-spec :optional
                                             :differential-tests :optional
                                             :trace-validation :optional
                                             :benchmarks :optional
                                             :confirmation-evidence :optional}}]}
        view (policy/derive-policy policy {:subsystems [:runtime]
                                           :change-categories [:code-only]
                                           :system-categories [:async-runtime]
                                           :risk-class :high
                                           :concern-classes [:safety]})]
    (is (= :forbidden (get-in view [:required-evidence :unit-tests])))
    (is (some #(= "Forbidden evidence present: unit-tests" %)
              (policy/missing-obligations view #{:unit-tests} #{})))))
