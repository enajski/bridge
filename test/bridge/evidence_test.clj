(ns bridge.evidence-test
  (:require [bridge.evidence :as evidence]
            [bridge.io :as bio]
            [bridge.profile :as profile]
            [bridge.schema :as schema]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(defn temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory "bridge-evidence-test" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn write-profile! [dir command]
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
                     :canonical-commands [command]
                     :subsystems [{:name "core"
                                   :code-globs ["src/**/*.clj"]
                                   :docs-globs []
                                   :formal-globs []
                                   :test-globs []
                                   :expected-artifacts ["verification-brief"]
                                   :system-category "api"}]
                     :phases []})
    path))

(deftest parses-passing-and-failing-evidence-independent-of-exit-code
  (let [dir (temp-dir)
        parser {:type "regex-status"
                :rules [{:stream :stdout :regex "PASS" :status "passed"}
                        {:stream :stdout :regex "FAIL" :status "failed"}]
                :captures [{:stream :stdout :regex "RootCause: ([^\n]+)" :as "primary-failure" :failure-signal? true}]
                :path-extractors [{:stream :stdout :regex "OutputDir: (.+)" :as "output-dir"}]}
        pass-profile (profile/load-profile (write-profile! dir {:id "pass"
                                                                :kind "trace-validation"
                                                                :command "printf 'PASS\nOutputDir: /tmp/out\n'"
                                                                :result-parser parser}))
        fail-profile (profile/load-profile (write-profile! dir {:id "fail"
                                                                :kind "trace-validation"
                                                                :command "printf 'FAIL\nRootCause: TraceAccepted false\nOutputDir: /tmp/out\n'"
                                                                :result-parser parser}))
        passed (evidence/run-command pass-profile "pass" {})
        failed (evidence/run-command fail-profile "fail" {})]
    (is (= "executed" (:execution-status passed)))
    (is (= "evidence-run" (:artifact passed)))
    (is (true? (:valid? (schema/validate-artifact-data passed))))
    (is (bio/exists? (str (io/file dir "artifacts/evidence/pass.yaml"))))
    (is (= "passed" (:evidence-status passed)))
    (is (= "executed" (:execution-status failed)))
    (is (= "failed" (:evidence-status failed)))
    (is (some #{"TraceAccepted false"} (:failure-signals failed)))
    (is (= "/tmp/out" (get-in failed [:related-paths "output-dir"])))
    (is (= "TraceAccepted false" (get-in failed [:failure-summary :primary-signal])))))

(deftest parserless-evidence-stays-unknown
  (let [dir (temp-dir)
        profile (profile/load-profile (write-profile! dir {:id "unknown"
                                                           :kind "trace-validation"
                                                           :command "echo ok"}))
        result (evidence/run-command profile "unknown" {})]
    (is (= "executed" (:execution-status result)))
    (is (= "unknown" (:evidence-status result)))))

(deftest evidence-command-times-out
  (let [dir (temp-dir)
        profile (profile/load-profile (write-profile! dir {:id "slow"
                                                           :kind "trace-validation"
                                                           :command "sleep 2"
                                                           :timeout-seconds 1}))
        result (evidence/run-command profile "slow" {})]
    (is (= "execution-failed" (:execution-status result)))
    (is (true? (:timed-out? result)))
    (is (= 124 (:exit-code result)))))

(deftest metadata-fingerprint-behavior
  (let [dir (temp-dir)
        sub-dir (io/file dir "src")
        _ (.mkdirs sub-dir)
        f1 (io/file sub-dir "a.clj")
        f2 (io/file sub-dir "b.clj")]
    (spit f1 "content-a")
    (spit f2 "content-b")
    (let [profile {:root-path (.getAbsolutePath dir)
                   :source-path (str (io/file dir "profile.edn"))
                   :code-paths ["src"]
                   :docs-paths []
                   :formal-paths []
                   :test-paths []
                   :subsystems [{:name "core"
                                 :code-globs ["src/**/*.clj"]
                                 :docs-globs []
                                 :formal-globs []
                                 :test-globs []}]}
          subsystem (profile/subsystem-by-name profile "core")
          fp-1 (profile/subsystem-fingerprint profile subsystem)
          _ (Thread/sleep 10)
          _ (spit f1 "content-a-edited")
          fp-2 (profile/subsystem-fingerprint profile subsystem)
          _ (io/delete-file f2)
          fp-3 (profile/subsystem-fingerprint profile subsystem)
          f3 (io/file sub-dir "c.clj")
          _ (spit f3 "content-c")
          fp-4 (profile/subsystem-fingerprint profile subsystem)]
      (is (string? fp-1))
      (is (not= fp-1 fp-2))
      (is (not= fp-2 fp-3))
      (is (not= fp-3 fp-4)))))

(deftest staleness-check
  (let [dir (temp-dir)
        sub-dir (io/file dir "src")
        _ (.mkdirs sub-dir)
        f1 (io/file sub-dir "a.clj")]
    (spit f1 "content-a")
    (let [profile {:root-path (.getAbsolutePath dir)
                   :source-path (str (io/file dir "profile.edn"))
                   :code-paths ["src"]
                   :docs-paths []
                   :formal-paths []
                   :test-paths []
                   :subsystems [{:name "core"
                                 :code-globs ["src/**/*.clj"]
                                 :docs-globs []
                                 :formal-globs []
                                 :test-globs []}]}
          subsystem (profile/subsystem-by-name profile "core")
          fp (profile/subsystem-fingerprint profile subsystem)
          ev-run {:artifact "evidence-run"
                  :evidence-id "test"
                  :subject "core"
                  :kind "unit-tests"
                  :subsystem-fingerprint fp}]
      (is (false? (evidence/stale-evidence-run? profile ev-run)))
      (Thread/sleep 10)
      (spit f1 "content-a-edited")
      (is (true? (evidence/stale-evidence-run? profile ev-run)))
      (is (true? (evidence/stale-evidence-run? profile (dissoc ev-run :subsystem-fingerprint)))))))

(deftest virtual-subsystem-fallback-staleness
  (let [dir (temp-dir)
        sub-dir (io/file dir "src")
        _ (.mkdirs sub-dir)
        f1 (io/file sub-dir "a.clj")]
    (spit f1 "content-a")
    (let [root-path (.getAbsolutePath dir)
          profile {:root-path root-path
                   :source-path (str (io/file dir "profile.edn"))
                   :code-paths [(str (io/file root-path "src"))]
                   :docs-paths []
                   :formal-paths []
                   :test-paths []
                   :subsystems []}
          subsystem (profile/subsystem-by-name profile "bridge")
          fp (profile/subsystem-fingerprint profile subsystem)
          ev-run {:artifact "evidence-run"
                  :evidence-id "test"
                  :subject "bridge"
                  :kind "unit-tests"
                  :subsystem-fingerprint fp}]
      (is (not= "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" fp))
      (is (false? (evidence/stale-evidence-run? profile ev-run)))
      (Thread/sleep 10)
      (spit f1 "content-a-edited")
      (is (true? (evidence/stale-evidence-run? profile ev-run))))))
