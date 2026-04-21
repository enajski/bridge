(ns bridge.citation
  (:require [bridge.io :as bio]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def citation-pattern #"([^\s,;()\[\]{}]*[/.][^\s,;()\[\]{}]*):(\d+)(?:-(\d+))?")

(defn parse-citation [token]
  (when-let [[_ path start end] (re-matches citation-pattern token)]
    {:path path
     :line-start (Long/parseLong start)
     :line-end (Long/parseLong (or end start))}))

(defn citations-in-string [s]
  (->> (re-seq citation-pattern (str s))
       (map (fn [[match]] (parse-citation match)))
       (filter some?)
       vec))

(defn citations-in-data [data]
  (letfn [(walk [node]
            (cond
              (string? node) (citations-in-string node)
              (map? node) (mapcat walk (concat (keys node) (vals node)))
              (sequential? node) (mapcat walk node)
              :else []))]
    (vec (walk data))))

(defn line-count [path]
  (with-open [r (io/reader path)]
    (count (line-seq r))))

(defn citation-warning [root {:keys [path line-start line-end] :as citation}]
  (let [resolved (bio/resolve-path root path)]
    (cond
      (nil? resolved)
      nil

      (not (bio/exists? resolved))
      {:type :missing-citation-path
       :citation citation
       :message (str "Citation path does not exist: " path)}

      :else
      (let [max-line (line-count resolved)]
        (when (> line-end max-line)
          {:type :invalid-citation-range
           :citation citation
           :message (str "Citation range exceeds file length: " path ":" line-start "-" line-end
                         " > " max-line)})))))

(defn citation-warnings [root data]
  (->> (citations-in-data data)
       (map #(citation-warning root %))
       (filter some?)
       vec))
