(ns bridge.requirements
  (:require [bridge.io :as bio]
            [bridge.profile :as profile]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn normalize-id-part [x]
  (cond
    (keyword? x) (name x)
    (string? x) x
    (integer? x) (str x)
    :else (str x)))

(defn- requirement-key-text [k]
  (normalize-id-part k))

(defn requirement-key? [k]
  (boolean (re-matches #"\d+(?:-\d+)?" (requirement-key-text k))))

(defn note-key? [k]
  (boolean (re-matches #"\d+(?:-\d+)?-note" (requirement-key-text k))))

(defn- strip-note-suffix [k]
  (str/replace (requirement-key-text k) #"-note$" ""))

(defn- key-order [k]
  (mapv #(Integer/parseInt %) (str/split (requirement-key-text k) #"-")))

(defn- compare-key-orders [a b]
  (loop [xs (seq (key-order a))
         ys (seq (key-order b))]
    (cond
      (and (nil? xs) (nil? ys)) 0
      (nil? xs) -1
      (nil? ys) 1
      :else (let [cmp (compare (first xs) (first ys))]
              (if (zero? cmp)
                (recur (next xs) (next ys))
                cmp)))))

(defn- note-map [requirements]
  (into {}
        (keep (fn [[k v]]
                (when (note-key? k)
                  [(strip-note-suffix k) (str v)])))
        requirements))

(defn- group-requirements [feature-name group-kind group-key group-data source-path]
  (let [requirements (:requirements group-data)
        notes (note-map requirements)]
    (->> requirements
         (keep (fn [[k v]]
                 (when (requirement-key? k)
                   (let [req-key (requirement-key-text k)
                         acid (str feature-name "." (normalize-id-part group-key) "." req-key)
                         object-value (when (map? v) v)]
                     {:id acid
                      :id-scheme :acid
                      :feature feature-name
                      :product (:product group-data)
                      :group-kind group-kind
                      :group-key (normalize-id-part group-key)
                      :group-name (:name group-data)
                      :group-description (:description group-data)
                      :requirement-key req-key
                      :requirement (if (map? v) (:requirement v) (str v))
                      :note (or (:note object-value) (get notes req-key))
                      :deprecated (boolean (:deprecated object-value))
                      :replaced-by (vec (or (:replaced_by object-value)
                                            (:replaced-by object-value)
                                            []))
                      :source-path source-path}))))
         (sort-by :requirement-key compare-key-orders)
         vec)))

(defn parse-feature-file [path]
  (let [source-path (bio/absolute-path path)
        data (bio/read-data path)
        feature-name (get-in data [:feature :name])
        product (get-in data [:feature :product])
        components (:components data)
        constraints (:constraints data)
        component-entries (mapcat (fn [[group-key group-data]]
                                    (group-requirements feature-name
                                                        :component
                                                        group-key
                                                        (assoc group-data :product product)
                                                        source-path))
                                  components)
        constraint-entries (mapcat (fn [[group-key group-data]]
                                     (group-requirements feature-name
                                                         :constraint
                                                         group-key
                                                         (assoc group-data :product product)
                                                         source-path))
                                   constraints)]
    {:kind :acai-feature-yaml
     :source-path source-path
     :feature feature-name
     :product product
     :requirements (vec (concat component-entries constraint-entries))}))

(defn requirement-sources [profile]
  (vec (:requirement-sources profile)))

(defn- rel-repo-files [root]
  (->> (file-seq (io/file root))
       (filter #(.isFile %))
       (map #(bio/relativize-path root (.getCanonicalPath %)))
       vec))

(defn discover-source-files [profile source]
  (let [root (:root-path profile)
        rels (rel-repo-files root)
        globs (:globs source)]
    (->> rels
         (filter (fn [rel]
                   (some #(profile/path-match? % rel) globs)))
         (mapv #(bio/resolve-path root %)))))

(defn source-file-summary [path]
  (let [{:keys [kind source-path feature product requirements]} (parse-feature-file path)]
    {:kind (name kind)
     :source-path source-path
     :feature feature
     :product product
     :requirement-count (count requirements)
     :requirement-ids (mapv :id requirements)}))

(defn load-catalog [profile]
  (->> (requirement-sources profile)
       (mapcat (fn [source]
                 (mapcat :requirements
                         (map parse-feature-file (discover-source-files profile source)))))
       vec))

(defn catalog-summary [profile]
  (let [sources (requirement-sources profile)
        source-files (mapcat #(discover-source-files profile %) sources)
        parsed (map parse-feature-file source-files)]
    {:source-count (count sources)
     :file-count (count source-files)
     :requirement-count (reduce + 0 (map #(count (:requirements %)) parsed))
     :features (->> parsed (map :feature) (remove nil?) distinct sort vec)}))

(defn matched-source-files [profile changed-files]
  (let [root (:root-path profile)
        changed-rels (map #(bio/relativize-path root (bio/resolve-path root %)) changed-files)]
    (->> (requirement-sources profile)
         (mapcat (fn [source]
                   (->> changed-rels
                        (filter (fn [rel]
                                  (some #(profile/path-match? % rel) (:globs source))))
                        distinct
                        (map #(bio/resolve-path root %)))))
         distinct
         vec)))

(defn matched-source-summaries [profile changed-files]
  (->> (matched-source-files profile changed-files)
       (filter bio/exists?)
       (mapv source-file-summary)))
