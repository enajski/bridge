(ns bridge.artifacts-test
  (:require [bridge.artifacts :as artifacts]
            [bridge.io :as bio]
            [bridge.profile :as profile]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(defn temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory "bridge-artifacts-test" (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest requirement-aware-coverage-summarizes-ledgers-observables-and-omissions
  (let [dir (temp-dir)
        profile-path (str (io/file dir "profile.edn"))
        artifact-root (io/file dir "artifacts")]
    (.mkdirs (io/file dir "features"))
    (.mkdirs artifact-root)
    (bio/write-data (str (io/file dir "features" "planner.feature.yaml"))
                    {:feature {:name "planner" :product "demo"}
                     :components {:ORDER {:requirements {1 "Preserves execution order"
                                                         :1-1 "Maintains deterministic sort"}}
                                  :FAILURE {:requirements {1 "Reports overflow failure"}}}})
    (bio/write-data profile-path
                    {:kind "project-profile"
                     :project-name "demo"
                     :root-path "."
                     :code-paths ["src"]
                     :docs-paths ["docs"]
                     :formal-paths ["specs"]
                     :test-paths ["test"]
                     :artifact-paths {:root "artifacts"
                                      :phases "artifacts/phases"
                                      :evidence "artifacts/evidence"
                                      :evaluations "artifacts/evaluations"}
                     :canonical-commands []
                     :subsystems [{:name "planner"
                                   :code-globs ["src/planner/**/*.clj"]
                                   :docs-globs ["features/**/*.feature.yaml"]
                                   :formal-globs []
                                   :test-globs []
                                   :expected-artifacts ["completeness-ledger" "verification-scope-ledger"]}]
                     :phases []
                     :requirement-sources [{:kind :acai-feature-yaml
                                            :globs ["features/**/*.feature.yaml"]
                                            :id-scheme :acid}]})
    (bio/write-data (str (io/file artifact-root "planner-completeness.yaml"))
                    {:artifact "completeness-ledger"
                     :subject "planner"
                     :workflow-state "active"
                     :areas {:contract {:status "present"
                                        :artifacts ["features/planner.feature.yaml"]
                                        :requirement-ids ["planner.ORDER.1" "planner.ORDER.1-1" "planner.FAILURE.1"]}
                             :implementation {:status "missing" :artifacts []}
                             :unit-tests {:status "deferred"
                                          :artifacts []
                                          :requirement-ids ["planner.ORDER.1"]}
                             :property-tests {:status "waived" :artifacts []}
                             :runtime-assertions {:status "missing" :artifacts []}
                             :formal-spec {:status "missing" :artifacts []}
                             :differential-evidence {:status "missing" :artifacts []}
                             :docs {:status "present"
                                    :artifacts ["features/planner.feature.yaml"]
                                    :requirement-ids ["planner.ORDER.1" "planner.ORDER.1-1" "planner.FAILURE.1"]}}
                     :notes []})
    (bio/write-data (str (io/file artifact-root "planner-scope.yaml"))
                    {:artifact "verification-scope-ledger"
                     :subject "planner"
                     :workflow-state "active"
                     :coverage {:proved []
                                :model-checked ["ordering proof sketch"]
                                :differential-tested []
                                :property-tested []
                                :runtime-asserted []
                                :documented-only ["overflow wording"]
                                :intentionally-uncovered []}
                     :requirement-coverage {:model-checked ["planner.ORDER.1"]
                                            :documented-only ["planner.FAILURE.1"]}
                     :boundaries []
                     :follow-up []})
    (bio/write-data (str (io/file artifact-root "planner-observable.yaml"))
                    {:artifact "observable-contract"
                     :subject "planner"
                     :observables [{:name "planner-order"
                                    :source {:path "src/planner/core.clj" :lines "TBD"}
                                    :trigger-point "after"
                                    :capture-timing "post-state"
                                    :snapshot-strength "strong"
                                    :collection-mode "other"
                                    :semantics-scope "base"
                                    :captures ["stage-id"]
                                    :maps-to ["planner-order"]
                                    :used-by ["review"]
                                    :requirement-ids ["planner.ORDER.1" "planner.ORDER.1-1"]}]
                     :constraints []
                     :notes []})
    (bio/write-data (str (io/file artifact-root "planner-omit.yaml"))
                    {:artifact "omission-decision-record"
                     :decision-id "omit-1"
                     :subject "planner"
                     :omitted-item "runtime-assertions"
                     :status "deferred"
                     :rationale ["not implemented yet"]
                     :compensating-controls ["docs review"]
                     :owner "team"
                     :review-trigger ["implementation starts"]
                     :related-artifacts ["artifacts/planner-completeness.yaml"]
                     :requirement-ids ["planner.ORDER.1-1"]})
    (let [loaded (profile/load-profile profile-path)
          coverage (artifacts/coverage loaded)
          summary (artifacts/summarize-ledgers (get-in loaded [:artifact-paths :root]) loaded)]
      (is (= 3 (get-in coverage [:requirements :total-count])))
      (is (= 3 (get-in coverage [:requirements :linked-count])))
      (is (= [] (get-in coverage [:requirements :unlinked-ids])))
      (is (= ["planner.FAILURE.1" "planner.ORDER.1" "planner.ORDER.1-1"]
             (get-in coverage [:requirements :by-completeness-status "present"])))
      (is (= ["planner.ORDER.1"]
             (get-in coverage [:requirements :by-scope-class "model-checked"])))
      (is (= ["planner.ORDER.1" "planner.ORDER.1-1"]
             (get-in coverage [:requirements :observable-mapped])))
      (is (= ["planner.ORDER.1-1"]
             (get-in coverage [:requirements :omitted])))
      (is (= (get-in coverage [:requirements])
             (get-in summary [:requirements]))))))
