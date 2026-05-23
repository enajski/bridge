(ns bridge.feasibility-test
  (:require [bridge.feasibility :as feasibility]
            [clojure.test :refer [deftest is]]))

(deftest renders-feasibility-markdown
  (let [md (feasibility/study->markdown
            {:artifact "feasibility-study"
             :study-id "fit-1"
             :subject "demo"
             :business-domain-fit "strong"
             :computational-core-fit "partial"
             :full-platform-fit "weak"
             :summary ["Good computational core candidate."]
             :requirements [{:id "R1" :requirement "Fast path" :assessment "partial" :notes ["PoC needed."]}]
             :blockers ["Latency proof missing."]
             :architecture-boundaries ["Service layer owns mutable reservations."]
             :required-pocs ["Warm JVM latency PoC"]
             :escalations ["Architecture review"]
             :recommendation "conditional-go"})]
    (is (.contains md "Feasibility Study"))
    (is (.contains md "conditional-go"))
    (is (.contains md "Latency proof missing."))))
