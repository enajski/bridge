(ns bridge.policy
  (:require [bridge.io :as bio]
            [bridge.schema :as schema]
            [clojure.set :as set]))

(def evidence-keys
  [:unit-tests
   :property-tests
   :runtime-assertions
   :docs-or-nl-spec
   :formal-spec
   :differential-tests
   :trace-validation
   :benchmarks
   :confirmation-evidence])

(def strictness-rank
  {"optional" 1
   "recommended" 2
   "required" 3
   "forbidden" 4})

(defn normalize-policy-value [value]
  (some-> value schema/normalize-enum-value str))

(defn- normalized-set [values]
  (set (map normalize-policy-value values)))

(defn load-policy [path]
  (let [data (bio/read-data path)
        result (schema/validate-artifact-data data {:root (.getParent (java.io.File. path))})]
    (when-not (:valid? result)
      (throw (ex-info "Invalid verification policy" {:path path :validation result})))
    data))

(defn- rule-matches? [rule {:keys [subsystems change-categories system-categories risk-class concern-classes]}]
  (let [scope (:scope rule)
        subsystem-match (or (empty? (:subsystems scope))
                            (some (normalized-set (:subsystems scope)) (map normalize-policy-value subsystems)))
        change-match (or (empty? (:change-categories scope))
                         (some (normalized-set (:change-categories scope)) (map normalize-policy-value change-categories)))
        system-match (or (empty? (:system-categories scope))
                         (some (normalized-set (:system-categories scope)) (map normalize-policy-value system-categories)))
        risk-match (or (empty? (:risk-classes scope))
                       (contains? (normalized-set (:risk-classes scope)) (normalize-policy-value risk-class)))
        concern-match (or (empty? (:concern-classes scope))
                          (some (normalized-set (:concern-classes scope)) (map normalize-policy-value concern-classes)))]
    (and subsystem-match change-match system-match risk-match concern-match)))

(defn applicable-rules [policy context]
  (->> (:rules policy)
       (filter #(rule-matches? % context))
       vec))

(defn merge-requirement [a b]
  (let [rank-a (get strictness-rank (schema/normalize-enum-value a) 0)
        rank-b (get strictness-rank (schema/normalize-enum-value b) 0)]
    (if (>= rank-a rank-b) a b)))

(defn merge-required-evidence [rules]
  (reduce (fn [acc rule]
            (reduce (fn [m k]
                      (update m k merge-requirement (get-in rule [:required-evidence k] "optional")))
                    acc
                    evidence-keys))
          {}
          rules))

(defn merge-required-roles [rules]
  (reduce (fn [acc rule]
            (merge-with into acc
                        (into {}
                              (map (fn [[role kinds]]
                                     [(normalize-policy-value role)
                                      (mapv schema/normalize-enum-value kinds)]))
                              (:evidence-roles rule))))
          {}
          rules))

(defn derive-policy [policy context]
  (let [rules (applicable-rules policy context)]
    {:rules rules
     :required-evidence (merge-required-evidence rules)
     :required-roles (merge-required-roles rules)
     :omission-rules (last (keep :omission-rules rules))
     :review-rules (last (keep :review-rules rules))}))

(def evidence-class->change-source
  {:unit-tests #{:tests}
   :property-tests #{:tests}
   :runtime-assertions #{:code}
   :docs-or-nl-spec #{:docs}
   :formal-spec #{:formal}
   :differential-tests #{:tests :formal}
   :trace-validation #{:tests :formal}
   :benchmarks #{:tests}
   :confirmation-evidence #{:tests :formal :code}})

(defn present-evidence-classes [change-kinds]
  (->> evidence-class->change-source
       (keep (fn [[k kinds]]
               (when (seq (set/intersection kinds (set change-kinds)))
                 k)))
       set))

(defn obligation-message [mode k]
  (case mode
    :revalidate (str "Revalidate required evidence: " (name k))
    (str "Missing required evidence: " (name k))))

(defn missing-obligations
  ([policy-context change-kinds]
   (missing-obligations policy-context (present-evidence-classes change-kinds) #{}))
  ([policy-context changed-evidence existing-evidence]
   (let [required-evidence (:required-evidence policy-context)]
     (->> evidence-keys
          (keep (fn [k]
                  (let [level (normalize-policy-value (get required-evidence k))]
                    (cond
                      (= "required" level)
                      (obligation-message (if (contains? existing-evidence k)
                                            :revalidate
                                            :missing)
                                          k)

                      (and (= "forbidden" level)
                           (or (contains? changed-evidence k)
                               (contains? existing-evidence k)))
                      (str "Forbidden evidence present: " (name k))))))
          vec))))
