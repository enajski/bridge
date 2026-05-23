(ns bridge.profile-test
  (:require [bridge.cli :as cli]
            [bridge.io :as bio]
            [bridge.profile :as profile]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

(defn temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory "bridge-profile-test" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn base-profile [root-path]
  {:kind "project-profile"
   :project-name "demo"
   :root-path root-path
   :code-paths ["src"]
   :docs-paths ["docs"]
   :formal-paths ["specs"]
   :test-paths ["test"]
   :artifact-paths {:root "artifacts"
                    :phases "artifacts/phases"
                    :evidence "artifacts/evidence"
                    :evaluations "artifacts/evaluations"}
   :canonical-commands [{:id "unit"
                         :kind "unit"
                         :cwd "src"
                         :command "echo ok"}]
   :subsystems [{:name "core"
                 :code-globs ["src/foo/**/*.clj"]
                 :docs-globs ["docs/**/*.md"]
                 :formal-globs ["specs/**/*"]
                 :test-globs ["test/foo/**/*.clj"]
                 :expected-artifacts ["verification-brief"]
                 :expected-evidence ["unit"]
                 :system-category "api"}]
   :phases [{:id :analyze :action "analyze-change"}]
   :derived-artifacts [{:name "generated-wrapper"
                        :kind "generated-formal-wrapper"
                        :inputs ["src/foo/**/*.clj"]
                        :outputs ["output/**/*.tla"]}]
   :requirement-sources [{:kind :acai-feature-yaml
                          :globs ["features/**/*.feature.yaml"]
                          :id-scheme :acid}]})

(deftest loads-and-matches-profile-globs
  (let [dir (temp-dir)
        profile-path (str (io/file dir "profile.edn"))
        _ (.mkdirs (io/file dir "src/foo"))
        _ (.mkdirs (io/file dir "docs"))
        _ (.mkdirs (io/file dir "test/foo"))
        _ (spit (io/file dir "src/foo/core.clj") "(ns foo.core)")
        data (base-profile ".")]
    (bio/write-data profile-path data)
    (let [loaded (profile/load-profile profile-path)
          matched (profile/match-subsystems loaded ["src/foo/core.clj"])]
      (is (= "demo" (:project-name loaded)))
      (is (= ["core"] (mapv :name matched)))
      (is (bio/absolute? (:root-path loaded)))
      (is (every? bio/absolute? (vals (:artifact-paths loaded))))
      (is (bio/absolute? (get-in loaded [:canonical-commands 0 :cwd])))
      (is (bio/absolute? (get-in loaded [:phases 0 :output-path])))
      (is (bio/absolute? (get-in loaded [:derived-artifacts 0 :inputs 0])))
      (is (bio/absolute? (get-in loaded [:derived-artifacts 0 :outputs 0]))))))

(deftest loads-profile-without-formal-surface
  (let [dir (temp-dir)
        profile-path (str (io/file dir "profile.edn"))
        _ (.mkdirs (io/file dir "src/foo"))
        _ (.mkdirs (io/file dir "docs"))
        _ (.mkdirs (io/file dir "test/foo"))
        _ (spit (io/file dir "src/foo/core.clj") "(ns foo.core)")
        data (-> (base-profile ".")
                 (dissoc :formal-paths)
                 (update :subsystems #(mapv (fn [subsystem]
                                              (dissoc subsystem :formal-globs))
                                            %)))]
    (bio/write-data profile-path data)
    (let [loaded (profile/load-profile profile-path)]
      (is (= [] (:formal-paths loaded)))
      (is (= ["core"] (mapv :name (profile/match-subsystems loaded ["src/foo/core.clj"]))))
      (is (= :code (profile/change-surface-kind loaded "src/foo/core.clj"))))))

(deftest normalizes-nested-profile-and-sibling-root
  (let [workspace (temp-dir)
        repo-dir (io/file workspace "target-repo")
        bridge-dir (io/file workspace "bridge-side/.bridge")
        profile-path (str (io/file bridge-dir "profile.edn"))]
    (.mkdirs (io/file repo-dir "src/foo"))
    (.mkdirs (io/file repo-dir "docs"))
    (.mkdirs (io/file repo-dir "test/foo"))
    (spit (io/file repo-dir "src/foo/core.clj") "(ns foo.core)")
    (bio/write-data profile-path
                    (assoc (base-profile "../../target-repo")
                           :artifact-paths {:root ".bridge/artifacts"
                                            :phases ".bridge/artifacts/phases"
                                            :evidence ".bridge/artifacts/evidence"
                                            :evaluations ".bridge/artifacts/evaluations"}))
    (let [loaded (profile/load-profile profile-path)]
      (is (= (bio/absolute-path (.getPath repo-dir)) (:root-path loaded)))
      (is (= (bio/absolute-path (.getPath (io/file repo-dir ".bridge/artifacts")))
             (get-in loaded [:artifact-paths :root])))
      (is (= ["core"] (mapv :name (profile/match-subsystems loaded ["src/foo/core.clj"])))))))

(deftest preserves-absolute-artifact-paths-and-command-cwd
  (let [dir (temp-dir)
        artifact-root (str (io/file dir "abs-artifacts"))
        abs-cwd (str (io/file dir "src"))
        profile-path (str (io/file dir "profile.edn"))]
    (.mkdirs (io/file dir "src/foo"))
    (.mkdirs (io/file dir "docs"))
    (.mkdirs (io/file dir "test/foo"))
    (spit (io/file dir "src/foo/core.clj") "(ns foo.core)")
    (bio/write-data profile-path
                    (-> (base-profile ".")
                        (assoc :artifact-paths {:root artifact-root
                                                :phases (str (io/file artifact-root "phases"))
                                                :evidence (str (io/file artifact-root "evidence"))
                                                :evaluations (str (io/file artifact-root "evaluations"))})
                        (assoc-in [:canonical-commands 0 :cwd] abs-cwd)))
    (let [loaded (profile/load-profile profile-path)]
      (is (= (bio/absolute-path artifact-root) (get-in loaded [:artifact-paths :root])))
      (is (= (bio/absolute-path abs-cwd) (get-in loaded [:canonical-commands 0 :cwd]))))))

(deftest classifies-paths-with-directory-boundaries
  (let [dir (temp-dir)
        profile-path (str (io/file dir "profile.edn"))]
    (.mkdirs (io/file dir "src"))
    (.mkdirs (io/file dir "src-old"))
    (.mkdirs (io/file dir "docs"))
    (.mkdirs (io/file dir "test"))
    (spit (io/file dir "src/core.clj") "(ns core)")
    (spit (io/file dir "src-old/core.clj") "(ns old)")
    (bio/write-data profile-path (base-profile "."))
    (let [loaded (profile/load-profile profile-path)]
      (is (= :code (profile/change-surface-kind loaded "src/core.clj")))
      (is (= :other (profile/change-surface-kind loaded "src-old/core.clj"))))))

(deftest cli-debug-profile-returns-normalized-paths
  (let [dir (temp-dir)
        profile-path (str (io/file dir "profile.edn"))]
    (.mkdirs (io/file dir "src/foo"))
    (.mkdirs (io/file dir "docs"))
    (.mkdirs (io/file dir "test/foo"))
    (spit (io/file dir "src/foo/core.clj") "(ns foo.core)")
    (bio/write-data profile-path (base-profile "."))
    (bio/write-data (str (io/file dir "features" "demo.feature.yaml"))
                    {:feature {:name "demo" :product "demo"}
                     :components {:CORE {:requirements {1 "Does thing"}}}})
    (let [result (cli/dispatch ["debug-profile" "--profile" profile-path "--changed-file" "src/foo/core.clj"])]
      (is (bio/absolute? (get-in result [:debug-profile :normalized-paths :root-path])))
      (is (= ["core"]
             (mapv :name (get-in result [:debug-profile :change-debug :matched-subsystems]))))
      (is (= 1 (get-in result [:debug-profile :requirement-debug :source-count])))
      (is (= 1 (get-in result [:debug-profile :requirement-debug :file-count]))))))
