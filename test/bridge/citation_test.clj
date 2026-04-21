(ns bridge.citation-test
  (:require [bridge.citation :as citation]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(defn temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory "bridge-citation-test" (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest parses-and-validates-citations
  (let [dir (temp-dir)
        file (io/file dir "doc.md")]
    (spit file "a\nb\nc\n")
    (is (= {:path "doc.md" :line-start 1 :line-end 3}
           (citation/parse-citation "doc.md:1-3")))
    (is (empty? (citation/citation-warnings (.getPath dir) {:note "doc.md:1-3"})))
    (is (= 1 (count (citation/citation-warnings (.getPath dir) {:note "missing.md:1-2"}))))))
