(ns bridge.artifacts
  (:require [bridge.io :as bio]
            [bridge.profile :as profile]
            [bridge.requirements :as requirements]
            [bridge.schema :as schema]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]))

(defn artifact-file? [path]
  (and (bio/data-file? path)
       (let [data (try (bio/read-data path)
                       (catch Exception _ nil))]
         (and (map? data)
              (contains? data :artifact)
              (not= "project-profile" (schema/normalize-enum-value (:kind data)))))))

(defn discover-artifact-files [root]
  (->> (bio/data-files-under root)
       (filter artifact-file?)
       vec))

(defn read-artifact [path]
  (let [data (bio/read-data path)]
    (assoc data :_path path)))

(defn validate-dir [root]
  (->> (discover-artifact-files root)
       (map (fn [path]
              {:path path
               :result (schema/validate-file path)}))
       vec))

(defn subject-of [artifact]
  (or (:subject artifact)
      (:subsystem artifact)
      (:namespace artifact)
      (:formal-module artifact)
      (first (get-in artifact [:semantic-scope :subsystems]))
      (:decision-id artifact)
      (:change-id artifact)))

(defn find-artifacts [root]
  (->> (discover-artifact-files root)
       (map read-artifact)
       vec))

(defn artifacts-by-kind [root]
  (group-by :artifact (find-artifacts root)))

(defn artifacts-for-subsystem [root subsystem-name]
  (->> (find-artifacts root)
       (filter #(= subsystem-name (subject-of %)))
       vec))

(defn- stringish? [x]
  (or (string? x) (keyword? x) (symbol? x)))

(defn- artifact-parent [artifact]
  (some-> (:_path artifact) io/file .getParent))

(defn- collect-reference-paths [artifact]
  (let [impl-area-paths (concat (get-in artifact [:areas :implementation :artifacts])
                                (get-in artifact [:areas :unit-tests :artifacts])
                                (get-in artifact [:areas :property-tests :artifacts])
                                (get-in artifact [:areas :runtime-assertions :artifacts])
                                (get-in artifact [:areas :formal-spec :artifacts])
                                (get-in artifact [:areas :differential-evidence :artifacts]))]
    (->> (concat
           (:files artifact)
           (:change-surface artifact)
           (get-in artifact [:repo-paths :code])
           (get-in artifact [:repo-paths :formal])
           (get-in artifact [:repo-paths :tests])
           (get-in artifact [:models :code-paths])
           (map #(get-in % [:source :path]) (:observables artifact))
           impl-area-paths)
         (filter stringish?)
         (map str)
         distinct
         vec)))

(defn- resolve-artifact-ref [artifact path]
  (let [base (or (artifact-parent artifact) ".")]
    (try
      (bio/resolve-path base path)
      (catch Exception _ nil))))

(defn- path->subsystems [profile artifact]
  (let [root (:root-path profile)]
    (->> (collect-reference-paths artifact)
         (map #(resolve-artifact-ref artifact %))
         (filter some?)
         (filter #(and (bio/exists? %) (bio/path-within? root %)))
         (map #(bio/relativize-path root %))
         (mapcat (fn [rel]
                   (->> (:subsystems profile)
                        (filter #(profile/any-glob-match? (profile/subsystem-rel-globs %) rel))
                        (map :name))))
         distinct
         vec)))

(defn artifact-subsystems [profile artifact]
  (let [raw-subject (some-> (subject-of artifact) str)
        exact-subjects (->> (:subsystems profile)
                            (keep (fn [subsystem]
                                    (when (= raw-subject (:name subsystem))
                                      (:name subsystem))))
                            vec)
        semantic-subsystems (mapv str (get-in artifact [:semantic-scope :subsystems]))
        artifact-formal-module (some-> (:formal-module artifact) str)
        rule-subsystems (if artifact-formal-module
                          (->> (:file-glob-rules profile)
                               (keep (fn [rule]
                                       (when (= artifact-formal-module
                                                (some-> (:formal-module rule) str))
                                         (:subsystem rule))))
                               distinct
                               vec)
                          [])
        matched-path-subsystems (path->subsystems profile artifact)
        preferred (->> (concat exact-subjects semantic-subsystems rule-subsystems)
                       (filter some?)
                       distinct
                       vec)]
    (if (seq preferred)
      preferred
      matched-path-subsystems)))

(defn canonical-subjects [profile artifact]
  (let [subsystems (artifact-subsystems profile artifact)
        raw-subject (some-> (subject-of artifact) str)]
    (if (seq subsystems)
      subsystems
      (cond-> [] raw-subject (conj raw-subject)))))

(defn group-by-canonical-subject [profile artifacts]
  (reduce (fn [acc artifact]
            (reduce (fn [m subject]
                      (update m subject (fnil conj []) artifact))
                    acc
                    (canonical-subjects profile artifact)))
          {}
          artifacts))

(defn- basename-stem [s]
  (let [name (-> s io/file .getName)]
    (str/replace name #"\.[^.]+$" "")))

(defn- subject-aliases [subject]
  (let [s (str subject)
        dash-parts (str/split s #"-")
        dot-parts (str/split s #"\.")]
    (->> (concat [s]
                 (when (> (count dash-parts) 1)
                   [(first dash-parts)])
                 (when (> (count dot-parts) 1)
                   [(last dot-parts)])
                 (when (str/includes? s "/")
                   [(basename-stem s)]))
         (filter seq)
         (map str/lower-case)
         distinct
         vec)))

(defn subject-index [profile artifacts]
  (let [grouped (group-by-canonical-subject profile artifacts)
        base-index (reduce (fn [acc [subject _]]
                             (reduce (fn [m alias]
                                       (update m alias (fnil conj #{}) subject))
                                     acc
                                     (subject-aliases subject)))
                           {}
                           grouped)]
    (reduce (fn [acc artifact]
              (let [canonicals (canonical-subjects profile artifact)]
                (if (= 1 (count canonicals))
                  (let [subject (first canonicals)
                        extra-aliases (->> (concat
                                             (when-let [raw (subject-of artifact)]
                                               (subject-aliases raw))
                                             (when-let [ns-name (:namespace artifact)]
                                               (subject-aliases ns-name))
                                             (when-let [formal (:formal-module artifact)]
                                               (subject-aliases formal)))
                                           distinct)]
                    (reduce (fn [m alias]
                              (update m alias (fnil conj #{}) subject))
                            acc
                            extra-aliases))
                  acc)))
            base-index
            artifacts)))

(defn- add-req-ids [acc status ids]
  (reduce (fn [m req-id]
            (update m status (fnil conj #{}) req-id))
          acc
          ids))

(defn- map-set-values->sorted-vectors [m]
  (into {}
        (map (fn [[k v]] [k (vec (sort v))]))
        m))

(defn- requirement-coverage-summary [profile artifacts]
  (let [catalog (requirements/load-catalog profile)
        all-ids (set (map :id catalog))
        completeness (filter #(= "completeness-ledger" (:artifact %)) artifacts)
        scopes (filter #(= "verification-scope-ledger" (:artifact %)) artifacts)
        observables (filter #(= "observable-contract" (:artifact %)) artifacts)
        omissions (filter #(= "omission-decision-record" (:artifact %)) artifacts)
        by-completeness (reduce (fn [acc ledger]
                                  (reduce (fn [m [_ area]]
                                            (add-req-ids m
                                                         (schema/normalize-enum-value (:status area))
                                                         (:requirement-ids area)))
                                          acc
                                          (:areas ledger)))
                                {}
                                completeness)
        scope-keys [:proved :model-checked :differential-tested :property-tested :runtime-asserted :documented-only :intentionally-uncovered]
        by-scope (reduce (fn [acc ledger]
                           (reduce (fn [m k]
                                     (add-req-ids m (name k) (get-in ledger [:requirement-coverage k])))
                                   acc
                                   scope-keys))
                         {}
                         scopes)
        observable-mapped (->> observables
                               (mapcat :observables)
                               (mapcat :requirement-ids)
                               set)
        omitted (->> omissions (mapcat :requirement-ids) set)
        linked (set/union
                (apply set/union #{} (vals by-completeness))
                (apply set/union #{} (vals by-scope))
                observable-mapped
                omitted)
        unlinked (set/difference all-ids linked)]
    {:total-count (count all-ids)
     :feature-count (count (distinct (map :feature catalog)))
     :linked-count (count linked)
     :unlinked-count (count unlinked)
     :by-completeness-status (map-set-values->sorted-vectors by-completeness)
     :by-scope-class (map-set-values->sorted-vectors by-scope)
     :observable-mapped (vec (sort observable-mapped))
     :omitted (vec (sort omitted))
     :unlinked-ids (vec (sort unlinked))}))

(defn coverage [profile]
  (let [artifact-root (get-in profile [:artifact-paths :root])
        artifacts (if artifact-root (find-artifacts artifact-root) [])
        base {:artifact-root artifact-root
              :artifact-count (count artifacts)
              :by-kind (into {}
                             (map (fn [[k v]] [k (count v)]))
                             (group-by :artifact artifacts))
              :subsystems
              (mapv (fn [subsystem]
                      (let [required (set (:expected-artifacts subsystem))
                            present (->> artifacts
                                         (filter #(= (:name subsystem) (subject-of %)))
                                         (map :artifact)
                                         set)]
                        {:subsystem (:name subsystem)
                         :required required
                         :present present
                         :missing (vec (sort (set/difference required present)))}))
                    (:subsystems profile))}]
    (cond-> base
      (seq (requirements/requirement-sources profile))
      (assoc :requirements (requirement-coverage-summary profile artifacts)))))

(defn missing-artifacts [profile]
  (->> (:subsystems (coverage profile))
       (mapcat (fn [{:keys [subsystem missing]}]
                 (map (fn [artifact-kind]
                        {:subsystem subsystem :artifact artifact-kind})
                      missing)))
       vec))

(defn summarize-ledgers
  ([root] (summarize-ledgers root nil))
  ([root profile]
   (let [artifacts (find-artifacts root)
         completeness (filter #(= "completeness-ledger" (:artifact %)) artifacts)
         omissions (filter #(= "omission-decision-record" (:artifact %)) artifacts)
         scopes (filter #(= "verification-scope-ledger" (:artifact %)) artifacts)
         base {:counts {:completeness-ledgers (count completeness)
                        :omission-records (count omissions)
                        :scope-ledgers (count scopes)}
               :completeness-statuses
               (frequencies
                (mapcat (fn [ledger]
                          (map (comp schema/normalize-enum-value :status val)
                               (:areas ledger)))
                        completeness))
               :omission-statuses (frequencies (map (comp schema/normalize-enum-value :status) omissions))
               :workflow-states (frequencies (map (comp schema/normalize-enum-value :workflow-state) (concat completeness scopes)))}]
     (cond-> base
       (and profile (seq (requirements/requirement-sources profile)))
       (assoc :requirements (requirement-coverage-summary profile artifacts))))))

(defn- field-segment [s]
  (if (re-matches #"\d+" s)
    (Long/parseLong s)
    (keyword s)))

(defn field-path [field]
  (cond
    (nil? field) nil
    (vector? field) field
    (keyword? field) [field]
    :else (mapv field-segment (str/split (str field) #"\."))))

(defn artifact-snippet [artifact]
  {:artifact (:artifact artifact)
   :subject (subject-of artifact)
   :path (:_path artifact)
   :workflow-state (:workflow-state artifact)
   :missing-obligation-count (count (:missing-obligations artifact))})

(defn query-artifacts
  ([profile root subject] (query-artifacts profile root subject nil))
  ([profile root subject field]
   (let [subject* (str subject)
         artifacts (find-artifacts root)
         grouped (group-by-canonical-subject profile artifacts)
         aliases (subject-index profile artifacts)
         resolved-subjects (or (seq (get aliases (str/lower-case subject*)))
                               (seq (for [[alias subjects] aliases
                                          :when (or (str/includes? alias (str/lower-case subject*))
                                                    (str/includes? (str/lower-case subject*) alias))
                                          subject subjects]
                                      subject))
                               [subject*])
         path* (field-path field)
         matches (->> resolved-subjects
                      (mapcat #(get grouped % []))
                      distinct
                      vec)]
     (let [field-matches (if path*
                           (->> matches
                                (map (fn [artifact]
                                       {:artifact (:artifact artifact)
                                        :path (:_path artifact)
                                        :value (get-in artifact path*)}))
                                (filter #(some? (:value %)))
                                vec)
                           (mapv artifact-snippet matches))]
       {:subject subject*
        :resolved-subjects (vec (distinct resolved-subjects))
        :field (when field (str field))
        :match-count (count field-matches)
        :matches field-matches}))))
