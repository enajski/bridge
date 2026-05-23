(ns bridge.templates
  (:require [bridge.io :as bio]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def template-root "resources/bridge/templates")

(def placeholder-pattern #"\{\{\s*([^}|\s]+)(?:\|([^}\s]+))?\s*\}\}")
(def if-pattern #"(?s)\{\{#if\s+([^}\s]+)\s*\}\}(.*?)\{\{/if\}\}")
(def each-pattern #"(?s)\{\{#each\s+([^}\s]+)\s*\}\}(.*?)\{\{/each\}\}")

(defn template-files []
  (->> (file-seq (io/file template-root))
       (filter #(.isFile %))
       (map #(.getPath %))
       sort
       vec))

(defn template-id [path]
  (-> path io/file .getName (str/replace #"\.[^.]+$" "")))

(defn list-templates []
  (mapv template-id (template-files)))

(defn load-template [template]
  (let [path (some (fn [candidate]
                     (when (= template (template-id candidate))
                       candidate))
                   (template-files))]
    (when-not path
      (throw (ex-info "Unknown template" {:template template :available (list-templates)})))
    {:id template :path path :content (slurp path)}))

(defn- kw [x]
  (if (keyword? x) x (keyword x)))

(defn- resolve-token [context token]
  (reduce (fn [node part]
            (cond
              (map? node) (get node (kw part) (get node part))
              :else nil))
          context
          (str/split token #"\.")))

(defn- format-value [value fmt]
  (case fmt
    "bullets" (if (sequential? value)
                (str/join "\n" (map #(str "- " %) value))
                (str value))
    "csv" (if (sequential? value) (str/join ", " value) (str value))
    "edn" (pr-str value)
    (cond
      (nil? value) ""
      (sequential? value) (str/join "\n" value)
      (map? value) (pr-str value)
      :else (str value))))

(defn- truthy? [v]
  (cond
    (nil? v) false
    (false? v) false
    (and (string? v) (str/blank? v)) false
    (sequential? v) (seq v)
    :else true))

(defn- render-fragment [content context]
  (let [with-each (str/replace
                   content
                   each-pattern
                   (fn [[_ token body]]
                     (let [items (or (resolve-token context token) [])]
                       (apply str
                              (for [item items]
                                (str/replace body
                                             #"\{\{\s*this\s*\}\}"
                                             (format-value item nil)))))))
        with-if (str/replace
                 with-each
                 if-pattern
                 (fn [[_ token body]]
                   (if (truthy? (resolve-token context token)) body "")))]
    (str/replace with-if
                 placeholder-pattern
                 (fn [[_ token fmt]]
                   (format-value (resolve-token context token) fmt)))))

(defn render-template [template context]
  (render-fragment (:content (load-template template)) context))

(defn default-context [profile subsystem files extra]
  {:profile profile
   :subsystem subsystem
   :files files
   :files-bulleted (map #(str % "") files)
   :project-name (:project-name profile)
   :root-path (:root-path profile)
   :code-paths (:code-paths profile)
   :docs-paths (:docs-paths profile)
   :formal-paths (:formal-paths profile)
   :test-paths (:test-paths profile)
   :canonical-commands (map (fn [cmd] (str (:id cmd) ": " (:command cmd)))
                            (:canonical-commands profile))
   :extra extra})

(defn render-from-profile [template profile {:keys [subsystem files extra]}]
  (render-template template (default-context profile subsystem files extra)))

(defn emit-template! [template profile opts out-path]
  (bio/write-text out-path (render-from-profile template profile opts))
  out-path)
