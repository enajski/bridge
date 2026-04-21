(ns bridge.observe-test
  (:require [bridge.io :as bio]
            [bridge.observe :as observe]
            [bridge.profile :as profile]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(defn temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory "bridge-observe-test" (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest generates-observable-contract-from-profile-context
  (let [dir (temp-dir)
        profile-path (str (io/file dir "profile.edn"))]
    (bio/write-data profile-path
                    {:kind "project-profile"
                     :project-name "demo"
                     :root-path "."
                     :code-paths ["src"] :docs-paths ["docs"] :formal-paths ["specs"] :test-paths ["test"]
                     :artifact-paths {:root "artifacts" :phases "artifacts/phases" :evidence "artifacts/evidence" :evaluations "artifacts/evaluations"}
                     :canonical-commands [{:id "trace" :kind "trace-validation" :role "conformance" :command "echo trace"}]
                     :subsystems [{:name "runtime"
                                   :code-globs ["src/runtime/**/*.clj"]
                                   :docs-globs [] :formal-globs [] :test-globs []
                                   :expected-artifacts ["observable-contract"]
                                   :system-category "async-runtime"}]
                     :file-glob-rules [{:glob "src/runtime/**/*.clj"
                                        :subsystem "runtime"
                                        :mechanism-family "wait-wake"
                                        :concern-class "liveness"
                                        :contract "waiters must be re-registered"}]
                     :phases []})
    (let [profile (profile/load-profile profile-path)
          artifact (observe/generate-observable-contract profile "runtime")]
      (is (= "observable-contract" (:artifact artifact)))
      (is (= "runtime" (:subject artifact)))
      (is (seq (:observables artifact)))
      (is (some (fn [note]
                  (= "Trace/conformance semantics may differ from base semantics; keep both explicit."
                     note))
                (:notes artifact))))))
