(ns bridge.summary-test
  (:require [bridge.api :as api]
            [bridge.io :as bio]
            [bridge.summary :as summary]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

(defn temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory "bridge-summary-test" (make-array java.nio.file.attribute.FileAttribute 0))))

(def failed-obligation
  {:kind "evidence"
   :subject "core"
   :summary "Revalidate unit tests"
   :reason "stale"
   :state "failed"
   :required-evidence ["unit-tests"]
   :actions [{:evidence-id "unit"}]})

(def open-obligation
  {:kind "evidence"
   :subject "core"
   :summary "Add property tests"
   :state "open"
   :required-evidence ["unit-tests" "property-tests"]
   :actions []})

(def synthetic-status
  {:project "demo"
   :status "attention-required"
   :issue-count 2
   :changed-files ["src/foo/core.clj"]
   :change-detection "explicit"
   :failed-obligations [failed-obligation]
   :open-obligations [open-obligation]
   :recommended-obligations [open-obligation]
   :stale-artifacts []
   :subject-problems []
   :convergence-summary {:converged-subject-count 0 :regressed-subject-count 0}
   :artifact-root nil})

(deftest flatten-obligation-rules
  (testing "single evidence kind is also exposed as :evidence-kind"
    (let [flat (summary/flatten-obligation failed-obligation)]
      (is (= ["unit-tests"] (:evidence-kinds flat)))
      (is (= "unit-tests" (:evidence-kind flat)))
      (is (= {:evidence-id "unit"} (:command flat)))))
  (testing "multiple kinds get no :evidence-kind, empty actions no :command"
    (let [flat (summary/flatten-obligation open-obligation)]
      (is (= ["unit-tests" "property-tests"] (:evidence-kinds flat)))
      (is (not (contains? flat :evidence-kind)))
      (is (not (contains? flat :command))))))

(deftest status-summary-projection
  (let [result (summary/status-summary synthetic-status)]
    (testing "canonical header"
      (is (= 1 (:summary-version result)))
      (is (= "demo" (:project result)))
      (is (= "attention-required" (:status result)))
      (is (= 2 (:issue-count result))))
    (testing "required obligations are failed first, then open"
      (is (= ["failed" "open"] (mapv :state (:required-obligations result)))))
    (testing "counts agree with the lists"
      (is (= {:required-obligations 2
              :recommended-obligations 1
              :stale-artifacts 0
              :subject-problems 0
              :receipts 0
              :passed-receipts 0}
             (:counts result))))
    (testing "next-action is the first failed obligation's action, enriched"
      (is (= "unit" (get-in result [:next-action :evidence-id])))
      (is (= "failed" (get-in result [:next-action :state]))))
    (testing "no artifact root means no receipts"
      (is (= [] (:evidence-receipts result))))
    (testing "summary is JSON-serializable round-trip"
      (is (map? (json/parse-string (json/generate-string result) keyword))))))

(deftest receipts-appear-after-running-evidence
  (let [dir (temp-dir)
        profile-path (str (io/file dir "profile.edn"))]
    (.mkdirs (io/file dir "src/foo"))
    (.mkdirs (io/file dir "docs"))
    (.mkdirs (io/file dir "test/foo"))
    (spit (io/file dir "src/foo/core.clj") "(ns foo.core)")
    (bio/write-data profile-path
                    {:kind "project-profile"
                     :project-name "demo"
                     :root-path "."
                     :code-paths ["src"]
                     :docs-paths ["docs"]
                     :formal-paths []
                     :test-paths ["test"]
                     :artifact-paths {:root "artifacts"
                                      :phases "artifacts/phases"
                                      :evidence "artifacts/evidence"
                                      :evaluations "artifacts/evaluations"}
                     :canonical-commands [{:id "unit"
                                           :kind "unit"
                                           :role "regression"
                                           :cwd "src"
                                           :command "echo ok"}]
                     :subsystems [{:name "core"
                                   :code-globs ["src/foo/**/*.clj"]
                                   :docs-globs ["docs/**/*.md"]
                                   :test-globs ["test/foo/**/*.clj"]
                                   :expected-artifacts []
                                   :expected-evidence ["unit"]
                                   :system-category "api"}]
                     :phases [{:id :analyze :action "analyze-change"}]})
    (let [profile (api/load-profile profile-path)]
      (api/run-command profile "unit" {})
      (let [result (api/check profile {:changed-files ["src/foo/core.clj"]})]
        (is (= 1 (:summary-version result)))
        (is (pos? (get-in result [:counts :receipts])))
        (is (= ["unit"] (mapv :id (:evidence-receipts result))))
        (is (= "unit-tests" (-> result :evidence-receipts first :kind)))
        (is (= "executed" (-> result :evidence-receipts first :execution-status)))))))
