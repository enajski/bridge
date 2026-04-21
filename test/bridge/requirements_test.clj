(ns bridge.requirements-test
  (:require [bridge.io :as bio]
            [bridge.profile :as profile]
            [bridge.requirements :as requirements]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(defn temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory "bridge-requirements-test" (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest parses-acai-feature-yaml-into-stable-requirements
  (let [dir (temp-dir)
        feature-path (str (io/file dir "features" "animated-terminal.feature.yaml"))]
    (.mkdirs (io/file dir "features"))
    (bio/write-data feature-path
                    {:feature {:name "animated-terminal"
                               :product "docs"
                               :description "demo"}
                     :components {:FRAME {:name "Terminal frame"
                                          :requirements {1 "Renders scrollable mock chat history"
                                                         :1-1 "Does not auto-scroll unless user is already at the bottom"
                                                         :1-note "Keep viewport stable"
                                                         2 {:requirement "Renders mock text input"
                                                            :note "Display only"
                                                            :deprecated true
                                                            :replaced_by ["animated-terminal.FRAME.3"]}}}}
                     :constraints {:DEV {:requirements {1 "Must be standalone"}}}})
    (let [{:keys [feature product requirements]} (requirements/parse-feature-file feature-path)
          ids (mapv :id requirements)
          first-entry (first requirements)
          object-entry (first (filter #(= "animated-terminal.FRAME.2" (:id %)) requirements))
          constraint-entry (first (filter #(= "animated-terminal.DEV.1" (:id %)) requirements))]
      (is (= "animated-terminal" feature))
      (is (= "docs" product))
      (is (= ["animated-terminal.FRAME.1"
              "animated-terminal.FRAME.1-1"
              "animated-terminal.FRAME.2"
              "animated-terminal.DEV.1"]
             ids))
      (is (= "Keep viewport stable" (:note first-entry)))
      (is (= "Display only" (:note object-entry)))
      (is (true? (:deprecated object-entry)))
      (is (= ["animated-terminal.FRAME.3"] (:replaced-by object-entry)))
      (is (= :constraint (:group-kind constraint-entry))))))

(deftest loads-catalog-from-profile-requirement-sources
  (let [dir (temp-dir)
        profile-path (str (io/file dir "profile.edn"))]
    (.mkdirs (io/file dir "features" "auth"))
    (bio/write-data (str (io/file dir "features" "auth" "user-auth.feature.yaml"))
                    {:feature {:name "user-auth" :product "demo"}
                     :components {:LOGIN {:requirements {1 "Shows login form" 2 "Creates session"}}}})
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
                     :subsystems []
                     :phases []
                     :requirement-sources [{:kind :acai-feature-yaml
                                            :globs ["features/**/*.feature.yaml"]
                                            :id-scheme :acid}]})
    (let [loaded (profile/load-profile profile-path)
          summary (requirements/catalog-summary loaded)
          catalog (requirements/load-catalog loaded)]
      (is (= 1 (:source-count summary)))
      (is (= 1 (:file-count summary)))
      (is (= 2 (:requirement-count summary)))
      (is (= ["user-auth"] (:features summary)))
      (is (= ["user-auth.LOGIN.1" "user-auth.LOGIN.2"] (mapv :id catalog))))))
