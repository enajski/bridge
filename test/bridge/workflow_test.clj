(ns bridge.workflow-test
  (:require [bridge.io :as bio]
            [bridge.policy :as policy]
            [bridge.profile :as profile]
            [bridge.workflow :as workflow]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

(defn temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory "bridge-workflow-test" (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest scripted-phase-writes-handoff-artifact
  (let [dir (temp-dir)
        _ (.mkdirs (io/file dir "src/core"))
        _ (spit (io/file dir "src/core/main.clj") "(ns core.main)")
        profile-path (str (io/file dir "profile.edn"))
        policy-path (str (io/file dir "policy.yaml"))
        _ (bio/write-data profile-path
                          {:kind "project-profile"
                           :project-name "demo"
                           :root-path "."
                           :code-paths ["src"]
                           :docs-paths ["docs"]
                           :formal-paths ["specs"]
                           :test-paths ["test"]
                           :artifact-paths {:root "artifacts" :phases "artifacts/phases" :evidence "artifacts/evidence" :evaluations "artifacts/evaluations"}
                           :canonical-commands []
                           :subsystems [{:name "core"
                                         :code-globs ["src/core/**/*.clj"]
                                         :docs-globs []
                                         :formal-globs []
                                         :test-globs []
                                         :expected-artifacts ["change-intent-card"]
                                         :system-category "api"}]
                           :phases [{:id :analyze :action "analyze-change" :output-path "artifacts/phases/analyze.yaml"}]})
        _ (bio/write-data policy-path
                          {:artifact "verification-policy"
                           :policy-id "demo"
                           :rules [{:scope {:subsystems ["core"]}
                                    :required-evidence {:unit-tests "optional"
                                                        :property-tests "optional"
                                                        :runtime-assertions "optional"
                                                        :docs-or-nl-spec "optional"
                                                        :formal-spec "optional"
                                                        :differential-tests "optional"
                                                        :trace-validation "optional"
                                                        :benchmarks "optional"
                                                        :confirmation-evidence "optional"}}]})
        profile (profile/load-profile profile-path)
        policy (policy/load-policy policy-path)
        result (workflow/run-phase! profile :analyze {:changed-files ["src/core/main.clj"]
                                                      :policy policy
                                                      :subject "demo-change"})]
    (is (bio/exists? (:path result)))
    (is (= "change-intent-card" (:artifact (:artifact result))))))

(deftest scripted-multi-phase-run-preserves-handoffs
  (let [dir (temp-dir)
        _ (.mkdirs (io/file dir "src/core"))
        _ (.mkdirs (io/file dir "docs"))
        _ (spit (io/file dir "src/core/main.clj") "(ns core.main)")
        _ (spit (io/file dir "docs/spec.md") "spec")
        profile-path (str (io/file dir "profile.edn"))
        policy-path (str (io/file dir "policy.yaml"))]
    (bio/write-data profile-path
                    {:kind "project-profile"
                     :project-name "demo"
                     :root-path "."
                     :code-paths ["src"] :docs-paths ["docs"] :formal-paths ["specs"] :test-paths ["test"]
                     :artifact-paths {:root "artifacts" :phases "artifacts/phases" :evidence "artifacts/evidence" :evaluations "artifacts/evaluations"}
                     :canonical-commands [{:id "unit" :kind "unit" :command "echo ok"}]
                     :subsystems [{:name "core"
                                   :code-globs ["src/core/**/*.clj"]
                                   :docs-globs ["docs/**/*.md"]
                                   :formal-globs []
                                   :test-globs []
                                   :expected-artifacts ["change-intent-card" "verification-brief" "observable-contract"]
                                   :system-category "api"}]
                     :file-glob-rules [{:glob "src/core/**/*.clj" :subsystem "core" :mechanism-family "core-change" :concern-class "safety"}]
                     :phases [{:id :analyze :action "analyze-change" :output-path "artifacts/phases/analyze.yaml"}
                              {:id :brief :action "generate-brief" :output-path "artifacts/phases/brief.yaml"}
                              {:id :observe :action "generate-observable" :output-path "artifacts/phases/observe.yaml"}]})
    (bio/write-data policy-path
                    {:artifact "verification-policy"
                     :policy-id "demo"
                     :rules [{:scope {:subsystems ["core"]}
                              :required-evidence {:unit-tests "optional"
                                                  :property-tests "optional"
                                                  :runtime-assertions "recommended"
                                                  :docs-or-nl-spec "required"
                                                  :formal-spec "optional"
                                                  :differential-tests "optional"
                                                  :trace-validation "optional"
                                                  :benchmarks "optional"
                                                  :confirmation-evidence "optional"}}]})
    (let [profile (profile/load-profile profile-path)
          pol (policy/load-policy policy-path)
          result (workflow/run-phases! profile nil {:changed-files ["src/core/main.clj"]
                                                    :policy pol
                                                    :subject "demo-change"})]
      (is (= 3 (count (:results result))))
      (is (true? (get-in result [:handoff :complete?])))
      (is (bio/exists? (get-in result [:results 1 :path])))
      (is (bio/exists? (get-in result [:results 2 :path]))))))
