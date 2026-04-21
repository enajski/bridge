(ns bridge.change-test
  (:require [bridge.change :as change]
            [bridge.io :as bio]
            [bridge.policy :as policy]
            [bridge.profile :as profile]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(defn temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory "bridge-change-test" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn write-profile! [dir]
  (let [path (str (io/file dir "profile.edn"))]
    (bio/write-data path
                    {:kind "project-profile"
                     :project-name "demo"
                     :root-path "."
                     :code-paths ["src"]
                     :docs-paths ["docs"]
                     :formal-paths ["specs"]
                     :test-paths ["test"]
                     :artifact-paths {:root "artifacts" :phases "artifacts/phases" :evidence "artifacts/evidence" :evaluations "artifacts/evaluations"}
                     :canonical-commands [{:id "trace" :kind "trace-validation" :role "conformance" :command "echo trace"}
                                          {:id "mc" :kind "model-check" :role "exploration" :command "echo model-check"}]
                     :subsystems [{:name "planner"
                                   :code-globs ["src/planner/**/*.clj"]
                                   :docs-globs ["docs/**/*.md"]
                                   :formal-globs ["specs/**/*.tla"]
                                   :test-globs ["test/planner/**/*.clj"]
                                   :expected-artifacts ["change-intent-card" "verification-brief"]
                                   :expected-evidence ["trace-validation" "model-check"]
                                   :system-category "distributed"
                                   :risk-class "high"}]
                     :file-glob-rules [{:glob "src/planner/**/*.clj"
                                        :subsystem "planner"
                                        :mechanism-family "trace-conversion"
                                        :concern-class "safety"
                                        :contract "planner-order"
                                        :formal-module "specs/planner.tla"
                                        :test-path "test/planner/core_test.clj"
                                        :risk-note "Planner drift impacts execution order."}]
                     :derived-artifacts [{:name "planner-specTrace"
                                          :kind "generated-formal-wrapper"
                                          :inputs ["src/planner/**/*.clj"]
                                          :outputs ["output/specTrace.tla"]}]
                     :requirement-sources [{:kind :acai-feature-yaml
                                            :globs ["features/**/*.feature.yaml"]
                                            :id-scheme :acid}]
                     :phases []})
    path))

(defn write-policy! [dir]
  (let [path (str (io/file dir "policy.yaml"))]
    (bio/write-data path
                    {:artifact "verification-policy"
                     :policy-id "demo"
                     :rules [{:scope {:subsystems ["planner"]
                                      :change-categories ["code-only" "mixed"]
                                      :system-categories ["distributed"]
                                      :risk-classes ["high"]}
                              :required-evidence {:unit-tests "required"
                                                  :property-tests "optional"
                                                  :runtime-assertions "recommended"
                                                  :docs-or-nl-spec "required"
                                                  :formal-spec "required"
                                                  :differential-tests "required"
                                                  :trace-validation "recommended"
                                                  :benchmarks "optional"
                                                  :confirmation-evidence "optional"}
                              :evidence-roles {:conformance ["trace-validation"]}}]})
    path))

(deftest infers-change-intent-and-obligations
  (let [dir (temp-dir)
        _ (.mkdirs (io/file dir "src/planner"))
        _ (.mkdirs (io/file dir "docs"))
        _ (.mkdirs (io/file dir "test/planner"))
        _ (.mkdirs (io/file dir "artifacts"))
        _ (.mkdirs (io/file dir "features"))
        _ (spit (io/file dir "src/planner/core.clj") "(ns planner.core)")
        _ (spit (io/file dir "docs/spec.md") "spec")
        _ (bio/write-data (str (io/file dir "features" "planner.feature.yaml"))
                          {:feature {:name "planner" :product "demo"}
                           :components {:ORDER {:requirements {1 "Preserves execution order"
                                                               :1-1 "Maintains deterministic sort"}}}})
        _ (spit (io/file dir "test/planner/core_test.clj") "(ns planner.core-test)")
        profile (profile/load-profile (write-profile! dir))
        policy (policy/load-policy (write-policy! dir))
        card (change/initial-change-intent profile policy ["src/planner/core.clj"] "chg-1")]
    (is (= "change-intent-card" (:artifact card)))
    (is (= ["planner"] (get-in card [:semantic-scope :subsystems])))
    (is (= "high" (:risk-class card)))
    (is (some #(= "Revalidate required evidence: unit-tests" %) (:missing-obligations card)))
    (is (some #(= "Revalidate required evidence: docs-or-nl-spec" %) (:missing-obligations card)))
    (is (some #(= "Rerun evidence: trace-validation" %) (:missing-obligations card)))
    (is (some #(= "Refresh artifact: observable-contract" %) (:missing-obligations card)))
    (is (some #(= "trace-conversion" %) (get-in card [:inferred-intent :mechanism-families])))
    (is (not (some #(= "Missing evidence role coverage: conformance" %) (:missing-obligations card))))
    (is (seq (:missing-obligations-structured card)))
    (is (some #(= "evidence-rerun" (:kind %)) (:missing-obligations-structured card)))
    (is (= [{:kind "acai-feature-yaml"
             :source-path (bio/absolute-path (str (io/file dir "features" "planner.feature.yaml")))
             :feature "planner"
             :product "demo"
             :requirement-count 2
             :requirement-ids ["planner.ORDER.1" "planner.ORDER.1-1"]}]
           (:requirement-sources (change/initial-change-intent profile policy ["features/planner.feature.yaml"] "chg-2"))))))

(deftest unmatched-files-do-not-contaminate-matched-change-classification
  (let [dir (temp-dir)
        _ (.mkdirs (io/file dir "src/planner"))
        _ (.mkdirs (io/file dir ".bridge"))
        _ (spit (io/file dir "src/planner/core.clj") "(ns planner.core)")
        _ (spit (io/file dir ".bridge/profile.edn") "{}")
        _ (spit (io/file dir ".gitignore") "/.bridge/artifacts/")
        profile (profile/load-profile (write-profile! dir))
        policy (policy/load-policy (write-policy! dir))
        card (change/initial-change-intent profile policy ["src/planner/core.clj"
                                                           ".bridge/profile.edn"
                                                           ".gitignore"]
                                           "chg-bootstrap-noise")]
    (is (= ["planner"] (get-in card [:semantic-scope :subsystems])))
    (is (= ["code-only"] (get-in card [:inferred-intent :categories])))
    (is (= ["code-diff"] (:change-sources card)))))

(deftest detects-stale-artifacts-by-mtime
  (let [dir (temp-dir)
        _ (.mkdirs (io/file dir "src/planner"))
        _ (.mkdirs (io/file dir "artifacts"))
        _ (spit (io/file dir "src/planner/core.clj") "(ns planner.core)")
        profile (profile/load-profile (write-profile! dir))
        stale-path (str (io/file dir "artifacts" "planner-brief.yaml"))]
    (bio/write-data stale-path {:artifact "verification-brief"
                                :subject "planner"
                                :category "distributed"
                                :summary ["x"]
                                :concern-classes ["safety"]
                                :environment-assumptions []
                                :mechanism-families []
                                :co-model-with []
                                :non-goals []
                                :handoff-outputs []
                                :open-questions []})
    (Thread/sleep 15)
    (spit (io/file dir "src/planner/core.clj") "(ns planner.core) ;; changed")
    (let [stale (change/stale-artifacts profile ["src/planner/core.clj"] ["planner"])]
      (is (= [(bio/absolute-path stale-path)
              (bio/absolute-path (str (io/file dir "output/specTrace.tla")))]
             stale)))))

(deftest detects-stale-derived-artifacts-with-reasons
  (let [dir (temp-dir)
        _ (.mkdirs (io/file dir "src/planner"))
        _ (.mkdirs (io/file dir "output"))
        source-path (io/file dir "src/planner/core.clj")
        output-path (io/file dir "output/specTrace.tla")
        _ (spit source-path "(ns planner.core)")
        _ (spit output-path "---- MODULE specTrace ----")
        profile (profile/load-profile (write-profile! dir))]
    (Thread/sleep 15)
    (spit source-path "(ns planner.core) ;; changed")
    (let [details (change/stale-artifacts-detailed profile ["src/planner/core.clj"] ["planner"])]
      (is (some #(= (bio/absolute-path (str output-path)) (:path %)) details))
      (is (some #(= "generated-formal-wrapper" (:kind %)) details))
      (is (some #(some #{"src/planner/core.clj"} (:stale-because %)) details)))))

(deftest forbidden-evidence-rules-are-visible
  (let [dir (temp-dir)
        _ (.mkdirs (io/file dir "test/planner"))
        _ (spit (io/file dir "test/planner/core_test.clj") "(ns planner.core-test)")
        profile (profile/load-profile (write-profile! dir))
        policy {:artifact "verification-policy"
                :policy-id "forbid-tests"
                :rules [{:scope {:subsystems ["planner"]
                                 :change-categories ["tests-only"]
                                 :system-categories ["distributed"]
                                 :risk-classes ["high"]}
                         :required-evidence {:unit-tests "forbidden"
                                             :property-tests "optional"
                                             :runtime-assertions "optional"
                                             :docs-or-nl-spec "optional"
                                             :formal-spec "optional"
                                             :differential-tests "optional"
                                             :trace-validation "optional"
                                             :benchmarks "optional"
                                             :confirmation-evidence "optional"}}]}
        card (change/initial-change-intent profile policy ["test/planner/core_test.clj"] "chg-forbidden")]
    (is (some #(= "Forbidden evidence present: unit-tests" %) (:missing-obligations card)))
    (is (some #(= "policy-evidence-forbidden" (:kind %)) (:missing-obligations-structured card)))))
