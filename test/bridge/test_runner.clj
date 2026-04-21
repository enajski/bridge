(ns bridge.test-runner
  (:require [clojure.test :as t]
            bridge.artifact-roundtrip-test
            bridge.artifacts-test
            bridge.brief-test
            bridge.citation-test
            bridge.policy-test
            bridge.requirements-test
            bridge.schema-test
            bridge.profile-test
            bridge.templates-test
            bridge.change-test
            bridge.workflow-test
            bridge.feasibility-test
            bridge.eval-test
            bridge.cli-test
            bridge.evidence-test
            bridge.next-test
            bridge.observe-test
            bridge.status-test))

(defn -main []
  (let [{:keys [fail error]} (t/run-all-tests #"bridge\..*")]
    (when (pos? (+ fail error))
      (System/exit 1))))
