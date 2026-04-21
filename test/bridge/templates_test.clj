(ns bridge.templates-test
  (:require [bridge.templates :as templates]
            [clojure.test :refer [deftest is]]))

(def sample-profile
  {:project-name "demo"
   :root-path "/tmp/demo"
   :code-paths ["src"]
   :docs-paths ["docs"]
   :formal-paths ["specs"]
   :test-paths ["test"]
   :canonical-commands [{:id "test" :command "clojure -M:test"}]})

(deftest renders-template-from-profile
  (let [rendered (templates/render-from-profile "verification-brief-synthesis"
                                                sample-profile
                                                {:subsystem nil
                                                 :files ["src/demo/core.clj"]
                                                 :extra {}})]
    (is (.contains rendered "verification-brief"))
    (is (.contains rendered "src/demo/core.clj"))
    (is (.contains rendered "demo"))))

(deftest renders-conditionals-and-loops
  (let [rendered (templates/render-template "test-control"
                                            {:files ["a.clj" "b.clj"]
                                             :extra {:enabled true}})]
    (is (.contains rendered "ENABLED"))
    (is (.contains rendered "a.clj;b.clj;"))))
