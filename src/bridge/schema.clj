(ns bridge.schema
  (:require [bridge.citation :as citation]
            [bridge.io :as bio]
            [clojure.string :as str]))

(defonce schema-db (delay (bio/read-resource-edn "bridge/schemas.edn")))

(defn db [] @schema-db)

(defn enums [] (:enums (db)))

(defn artifact-schemas [] (:artifact-schemas (db)))

(defn profile-schema [] (:profile-schema (db)))

(defn schema-for [kind]
  (when kind
    (get (artifact-schemas) (if (keyword? kind) (name kind) (str kind)))))

(defn artifact-kinds []
  (->> (artifact-schemas) keys (sort-by str) vec))

(defn enum-values [enum-key]
  (get (enums) enum-key []))

(defn normalize-enum-value [value]
  (cond
    (keyword? value) (name value)
    :else value))

(defn- error [type path message & [extra]]
  (merge {:type type :path path :message message} extra))

(defn- type-name [schema]
  (or (:label schema) (:type schema)))

(declare validate* stub-value)

(defn- validate-enum [schema value path]
  (let [values (or (:values schema)
                   (enum-values (:enum schema)))
        actual (normalize-enum-value value)
        allowed (set (map normalize-enum-value values))]
    (if (contains? allowed actual)
      []
      [(error :invalid-enum
              path
              (str "Expected one of " (pr-str (vec values)) ", got " (pr-str value))
              {:allowed (vec values) :actual value})])))

(defn- validate-scalar [pred error-type schema value path]
  (if (pred value)
    []
    [(error error-type
            path
            (str "Expected " (type-name schema) ", got " (pr-str value))
            {:expected (type-name schema) :actual value})]))

(defn- validate-map [schema value path]
  (if-not (map? value)
    [(error :invalid-type path (str "Expected map, got " (pr-str value)) {:expected :map :actual value})]
    (let [required (:required schema)
          optional (:optional schema)
          known-keys (set (concat (keys required) (keys optional)))
          missing-errors (->> required
                              (keep (fn [[k subschema]]
                                      (when-not (contains? value k)
                                        (error :missing-required
                                               (conj path k)
                                               (str "Missing required field " k)
                                               {:field k})))))
          unknown-errors (if (:closed schema true)
                           (->> (keys value)
                                (remove known-keys)
                                (map (fn [k]
                                       (error :unknown-field
                                              (conj path k)
                                              (str "Unknown field " k)
                                              {:field k}))))
                           [])
          child-errors (concat
                         (mapcat (fn [[k subschema]]
                                   (when (contains? value k)
                                     (validate* subschema (get value k) (conj path k))))
                                 required)
                         (mapcat (fn [[k subschema]]
                                   (when (contains? value k)
                                     (validate* subschema (get value k) (conj path k))))
                                 optional))]
      (vec (concat missing-errors unknown-errors child-errors)))))

(defn- validate-vector [schema value path]
  (if-not (sequential? value)
    [(error :invalid-type path (str "Expected vector, got " (pr-str value)) {:expected :vector :actual value})]
    (let [items (vec value)
          min-count (:min schema 0)
          max-count (:max schema)
          len (count items)
          size-errors (concat
                        (when (< len min-count)
                          [(error :invalid-size path (str "Expected at least " min-count " item(s)")
                                  {:min min-count :actual len})])
                        (when (and max-count (> len max-count))
                          [(error :invalid-size path (str "Expected at most " max-count " item(s)")
                                  {:max max-count :actual len})]))]
      (vec (concat size-errors
                   (mapcat (fn [idx item]
                             (validate* (:items schema) item (conj path idx)))
                           (range len)
                           items))))))

(defn- validate-map-of [schema value path]
  (if-not (map? value)
    [(error :invalid-type path (str "Expected map, got " (pr-str value)) {:expected :map :actual value})]
    (vec
      (concat
        (mapcat (fn [[k v]]
                  (concat (validate* (:keys schema) k (conj path :key))
                          (validate* (:values schema) v (conj path k))))
                value)))))

(defn validate* [schema value path]
  (let [schema-type (:type schema)]
    (case schema-type
      :any []
      :string (validate-scalar string? :invalid-type schema value path)
      :boolean (validate-scalar #(instance? Boolean %) :invalid-type schema value path)
      :integer (validate-scalar integer? :invalid-type schema value path)
      :number (validate-scalar number? :invalid-type schema value path)
      :keyword (validate-scalar keyword? :invalid-type schema value path)
      :identifier (validate-scalar #(or (keyword? %) (string? %)) :invalid-type schema value path)
      :enum (validate-enum schema value path)
      :map (validate-map schema value path)
      :vector (validate-vector schema value path)
      :map-of (validate-map-of schema value path)
      [(error :invalid-schema path (str "Unsupported schema type " schema-type) {:schema schema})])))

(defn validate-data
  ([schema data] (validate-data schema data {}))
  ([schema data {:keys [root]}]
   (let [errors (validate* schema data [])
         warnings (if root (citation/citation-warnings root data) [])]
     {:valid? (empty? errors)
      :errors errors
      :warnings warnings})))

(defn artifact-kind [data]
  (some-> (:artifact data) normalize-enum-value))

(defn validate-artifact-data
  ([data] (validate-artifact-data data {}))
  ([data {:keys [root expected-kind]}]
   (let [kind (or expected-kind (artifact-kind data))
         schema (schema-for kind)]
     (if-not schema
       {:valid? false
        :errors [(error :unknown-artifact [] (str "Unknown artifact kind " (pr-str kind))
                        {:artifact kind})]
        :warnings []}
       (validate-data schema data {:root root})))))

(defn validate-profile-data [data]
  (validate-data (profile-schema) data))

(defn validate-file [path & [{:keys [expected-kind]}]]
  (let [data (bio/read-data path)
        root (.getParent (java.io.File. path))]
    (if (= "project-profile" (normalize-enum-value (:kind data)))
      (validate-profile-data data)
      (validate-artifact-data data {:root root :expected-kind expected-kind}))))

(defn- first-example [schema]
  (or (:example schema)
      (case (:type schema)
        :string "<value>"
        :boolean false
        :integer 1
        :number 1.0
        :keyword :value
        :identifier "value"
        :enum (-> (or (:values schema) (enum-values (:enum schema))) first)
        :vector (let [item (stub-value (:items schema))]
                  (if (= 0 (:min schema 0)) [] [item]))
        :map (into {}
                   (map (fn [[k subschema]] [k (stub-value subschema)]))
                   (:required schema))
        :map-of {"key" (stub-value (:values schema))}
        :any nil
        nil)))

(defn stub-value [schema]
  (first-example schema))

(defn stub-artifact [kind]
  (let [schema (schema-for kind)]
    (-> (stub-value schema)
        (assoc :artifact kind))))

(defn stub-profile []
  (stub-value (profile-schema)))

(defn explain-errors [result]
  (concat
    (map (fn [{:keys [path message]}]
           (str "ERROR " (if (seq path) (pr-str path) "<root>") ": " message))
         (:errors result))
    (map (fn [{:keys [message]}]
           (str "WARN: " message))
         (:warnings result))))
