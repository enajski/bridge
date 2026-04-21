(ns bridge.io
  (:require [clj-yaml.core :as yaml]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.walk :as walk])
  (:import [java.nio.file Files Path Paths]
           [java.time Instant]))

(defn now-iso []
  (str (Instant/now)))

(defn file-extension [path]
  (some-> path str (str/split #"\.") last str/lower-case))

(defn yaml-path? [path]
  (contains? #{"yaml" "yml"} (file-extension path)))

(defn edn-path? [path]
  (= "edn" (file-extension path)))

(defn markdown-path? [path]
  (contains? #{"md" "markdown" "txt"} (file-extension path)))

(defn keywordize-data [value]
  (walk/postwalk
    (fn [node]
      (if (map? node)
        (into {}
              (map (fn [[k v]]
                     [(if (string? k) (keyword k) k) v]))
              node)
        node))
    value))

(defn ensure-parent! [path]
  (let [file (io/file path)
        parent (.getParentFile file)]
    (when parent
      (.mkdirs parent)))
  path)

(defn read-text [path]
  (slurp (io/file path)))

(defn parse-data [path content]
  (cond
    (edn-path? path) (edn/read-string {:readers *data-readers*} content)
    (yaml-path? path) (keywordize-data (yaml/parse-string content :keywords true))
    :else content))

(defn read-data [path]
  (parse-data path (read-text path)))

(defn write-edn [path data]
  (ensure-parent! path)
  (with-open [w (io/writer (io/file path))]
    (binding [*out* w]
      (pprint/pprint data)))
  path)

(defn write-yaml [path data]
  (ensure-parent! path)
  (spit (io/file path)
        (yaml/generate-string data :dumper-options {:flow-style :block}))
  path)

(defn write-text [path content]
  (ensure-parent! path)
  (spit (io/file path) content)
  path)

(defn write-data [path data]
  (cond
    (edn-path? path) (write-edn path data)
    (yaml-path? path) (write-yaml path data)
    :else (write-text path (if (string? data) data (with-out-str (pprint/pprint data))))))

(defn exists? [path]
  (.exists (io/file path)))

(defn directory? [path]
  (.isDirectory (io/file path)))

(defn absolute? [path]
  (boolean (and path (.isAbsolute (io/file path)))))

(defn absolute-path [path]
  (.getCanonicalPath (io/file path)))

(defn canonical-path-object [path]
  (.toPath (io/file (absolute-path path))))

(defn path-within? [root path]
  (let [root-path (canonical-path-object root)
        target-path (canonical-path-object path)]
    (.startsWith target-path root-path)))

(defn resolve-path [root path]
  (if (or (nil? path) (str/blank? (str path)))
    nil
    (absolute-path (if (.isAbsolute (io/file path))
                     path
                     (str (io/file root path))))))

(defn relativize-path [root path]
  (let [root-path (.toPath (io/file (absolute-path root)))
        target-path (.toPath (io/file (absolute-path path)))]
    (str (.normalize (.relativize root-path target-path)))))

(defn file-seq-paths [root pred]
  (->> (file-seq (io/file root))
       (filter #(.isFile %))
       (filter pred)
       (map #(.getPath %))
       sort
       vec))

(defn data-file? [path]
  (or (edn-path? path) (yaml-path? path)))

(defn data-files-under [root]
  (if (exists? root)
    (file-seq-paths root #(data-file? (.getPath %)))
    []))

(defn last-modified-ms [path]
  (.lastModified (io/file path)))

(defn newest-mtime [paths]
  (reduce max 0 (map last-modified-ms (filter exists? paths))))

(defn child-path [base & parts]
  (absolute-path (apply str (io/file base (first parts) (rest parts)))))

(defn run-shell
  [{:keys [command cwd timeout-ms shell]
    :or {shell ["bash" "-lc"] timeout-ms 300000}}]
  (let [attrs (make-array java.nio.file.attribute.FileAttribute 0)
        stdout-file (.toFile (Files/createTempFile "bridge-command-" ".stdout" attrs))
        stderr-file (.toFile (Files/createTempFile "bridge-command-" ".stderr" attrs))
        builder (doto (ProcessBuilder. (into [] (concat shell [command])))
                  (.redirectOutput stdout-file)
                  (.redirectError stderr-file))]
    (try
      (when cwd
        (.directory builder (io/file cwd)))
      (let [process (.start builder)
            completed? (.waitFor process (long timeout-ms) java.util.concurrent.TimeUnit/MILLISECONDS)]
        (when-not completed?
          (.destroyForcibly process)
          (.waitFor process))
        {:exit (if completed? (.exitValue process) 124)
         :out (slurp stdout-file)
         :err (slurp stderr-file)
         :timed-out? (not completed?)})
      (finally
        (Files/deleteIfExists (.toPath stdout-file))
        (Files/deleteIfExists (.toPath stderr-file))))))

(defn read-resource-edn [resource-path]
  (-> resource-path io/resource slurp edn/read-string))
