(ns bridge.api-test
  (:require [bridge.api :as api]
            [bridge.io :as bio]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

(defn temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory "bridge-api-test" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn base-profile [root-path]
  {:kind "project-profile"
   :project-name "demo"
   :root-path root-path
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
                 :expected-artifacts ["verification-brief"]
                 :expected-evidence ["unit"]
                 :system-category "api"}]
   :phases [{:id :analyze :action "analyze-change"}]})

(defn write-demo-project! []
  (let [dir (temp-dir)
        profile-path (str (io/file dir "profile.edn"))]
    (.mkdirs (io/file dir "src/foo"))
    (.mkdirs (io/file dir "docs"))
    (.mkdirs (io/file dir "test/foo"))
    (spit (io/file dir "src/foo/core.clj") "(ns foo.core)")
    (bio/write-data profile-path (base-profile "."))
    {:dir dir :profile-path profile-path}))

(deftest every-public-var-declares-the-contract
  (doseq [[sym v] (ns-publics 'bridge.api)]
    (testing (str sym)
      (is (contains? #{:stable :experimental} (:bridge.api/stability (meta v)))
          "every bridge.api var must carry a stability tag")
      (is (string? (:doc (meta v)))
          "every bridge.api var must have a docstring"))))

(deftest contract-matches-namespace
  (let [entries (api/contract)
        names (set (map :name entries))]
    (is (= names (set (keys (ns-publics 'bridge.api)))))
    (is (every? #(contains? #{:stable :experimental} (:stability %)) entries))
    (testing "stable entries sort before experimental ones"
      (let [stabilities (map :stability entries)]
        (is (= stabilities
               (concat (filter #(= :stable %) stabilities)
                       (filter #(= :experimental %) stabilities))))))))

(deftest stable-surface-is-explicit
  (let [by-stability (group-by :stability (api/contract))
        stable-names (set (map :name (:stable by-stability)))
        experimental-names (set (map :name (:experimental by-stability)))]
    (is (= '#{load-profile profile-summary load-policy init!
              normalize-evidence-kind list-commands run-command
              find-artifacts check status-summary
              resolve-path relativize-path exists?
              read-data contract}
           stable-names)
        "adding or removing a stable var is a contract change — update this test, docs/api.md, and the CHANGELOG together")
    (is (= '#{build-status planned-actions next-action}
           experimental-names))))

(deftest profile-evidence-and-status-roundtrip
  (let [{:keys [profile-path]} (write-demo-project!)
        profile (api/load-profile profile-path)]
    (testing "load-profile + profile-summary"
      (is (= "demo" (:project-name profile)))
      (is (= {:project-name "demo"
              :subsystem-count 1
              :command-count 1
              :phase-count 1
              :derived-artifact-count 0
              :requirement-source-count 0}
             (dissoc (api/profile-summary profile) :root-path))))
    (testing "list-commands"
      (let [commands (api/list-commands profile)]
        (is (= ["unit"] (mapv :id commands)))
        (is (= "echo ok" (:command (first commands))))))
    (testing "run-command dry run returns the plan without executing"
      (let [plan (api/run-command profile "unit" {:dry-run? true})]
        (is (true? (:dry-run? plan)))
        (is (= "echo ok" (:command plan)))))
    (testing "run-command writes a receipt"
      (let [receipt (api/run-command profile "unit" {})]
        (is (= "evidence-run" (:artifact receipt)))
        (is (= "executed" (:execution-status receipt)))
        (is (api/exists? (get-in (api/run-command profile "unit" {:dry-run? true})
                                 [:artifact-path])))))
    (testing "find-artifacts sees the receipt"
      (let [artifacts (api/find-artifacts (get-in profile [:artifact-paths :root]))]
        (is (some #(= "evidence-run" (:artifact %)) artifacts))))
    (testing "build-status and planned actions"
      (let [status (api/build-status profile {:changed-files ["src/foo/core.clj"]})]
        (is (contains? #{"clear" "attention-required"} (:status status)))
        (is (vector? (api/planned-actions status)))
        (is (= (first (api/planned-actions status)) (api/next-action status)))))
    (testing "check returns the canonical summary and matches the projection"
      (let [opts {:changed-files ["src/foo/core.clj"]}
            summary (api/check profile opts)]
        (is (= 1 (:summary-version summary)))
        (is (= summary (api/status-summary (api/build-status profile opts))))))))

(deftest init-bootstraps-a-bridge-layout
  (let [dir (temp-dir)
        result (api/init! {:root (str dir)})]
    (is (= [".bridge/profile.edn" ".bridge/verification-policy.yaml"] (:created result)))
    (is (api/exists? (str (io/file dir ".bridge/profile.edn"))))
    (is (api/exists? (str (io/file dir ".bridge/verification-policy.yaml"))))
    (testing "generated profile loads through the api and policy validates"
      (let [profile (api/load-profile (str (io/file dir ".bridge/profile.edn")))]
        (is (string? (:project-name profile)))
        (is (map? (api/load-policy (:verification-policy-path profile))))))))

(deftest utility-functions
  (is (= "missing" (api/normalize-evidence-kind :MISSING)))
  (is (= "unit-tests" (api/normalize-evidence-kind "unit")))
  (is (= "benchmarks" (api/normalize-evidence-kind :benchmark)))
  (let [dir (str (temp-dir))]
    (is (= (bio/absolute-path (str (io/file dir "x.edn")))
           (api/resolve-path dir "x.edn")))
    (is (= "x.edn" (api/relativize-path dir (str (io/file dir "x.edn")))))
    (spit (io/file dir "data.edn") "{:a 1}")
    (is (= {:a 1} (api/read-data (str (io/file dir "data.edn")))))))
