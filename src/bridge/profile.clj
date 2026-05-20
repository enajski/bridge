(ns bridge.profile
  (:require [bridge.io :as bio]
            [bridge.schema :as schema]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn glob->regex [glob]
  (let [pattern (-> glob
                    (str/replace #"\." "\\.")
                    (str/replace #"\*\*/" "§DIR§")
                    (str/replace #"\*\*" "§GLOB§")
                    (str/replace #"\*" "[^/]*")
                    (str/replace "§DIR§" "(?:.*/)?")
                    (str/replace "§GLOB§" ".*"))]
    (str "^" pattern "$")))

(defn path-match? [glob rel-path]
  (boolean (re-matches (re-pattern (glob->regex glob)) rel-path)))

(defn any-glob-match? [globs rel-path]
  (boolean (some #(path-match? % rel-path) globs)))

(defn normalize-command [root command]
  (cond-> command
    (:cwd command) (update :cwd #(bio/resolve-path root %))
    (:output-path command) (update :output-path #(bio/resolve-path root %))))

(defn normalize-subsystem [root subsystem]
  (cond-> subsystem
    (:artifact-root subsystem) (update :artifact-root #(bio/resolve-path root %))))

(defn normalize-derived-artifact [root artifact]
  (cond-> artifact
    (:inputs artifact) (update :inputs #(mapv (partial bio/resolve-path root) %))
    (:outputs artifact) (update :outputs #(mapv (partial bio/resolve-path root) %))))

(defn requirement-source-globs [source]
  (vec (:globs source)))

(defn requirement-source-match? [profile rel-path]
  (boolean
   (some (fn [source]
           (any-glob-match? (requirement-source-globs source) rel-path))
         (:requirement-sources profile))))

(defn match-requirement-sources [profile changed-files]
  (let [root (:root-path profile)
        rels (map #(bio/relativize-path root (bio/resolve-path root %)) changed-files)]
    (->> (:requirement-sources profile)
         (filter (fn [source]
                   (some (fn [rel]
                           (any-glob-match? (requirement-source-globs source) rel))
                         rels)))
         vec)))

(defn default-phase-output [profile phase-id ext]
  (let [phases-root (or (get-in profile [:artifact-paths :phases])
                        (bio/resolve-path (:root-path profile) "artifacts/phases"))]
    (bio/resolve-path phases-root (str (name phase-id) "." ext))))

(defn normalize-phase [profile phase]
  (let [root (:root-path profile)
        action (keyword (:action phase))
        ext (or (:output-extension phase)
                (case action
                  :render-prompt "md"
                  "yaml"))]
    (cond-> (assoc phase :id (keyword (:id phase)) :action action)
      (:output-path phase) (update :output-path #(bio/resolve-path root %))
      (nil? (:output-path phase)) (assoc :output-path (default-phase-output profile (keyword (:id phase)) ext))
      (:cwd phase) (update :cwd #(bio/resolve-path root %)))))

(defn- invalid-absolute-path [field-path value]
  (when (and value (not (bio/absolute? value)))
    {:field-path field-path
     :value value}))

(defn validate-normalized-profile! [profile]
  (let [errors (vec
                 (concat
                   (when-let [error (invalid-absolute-path [:root-path] (:root-path profile))]
                     [error])
                   (keep (fn [[k v]]
                           (invalid-absolute-path [:artifact-paths k] v))
                         (:artifact-paths profile))
                   (when-let [error (invalid-absolute-path [:verification-policy-path] (:verification-policy-path profile))]
                     [error])
                   (mapcat (fn [idx command]
                             (keep identity
                                   [(invalid-absolute-path [:canonical-commands idx :cwd] (:cwd command))
                                    (invalid-absolute-path [:canonical-commands idx :output-path] (:output-path command))]))
                           (range)
                           (:canonical-commands profile))
                   (mapcat (fn [idx phase]
                             (keep identity
                                   [(invalid-absolute-path [:phases idx :output-path] (:output-path phase))
                                    (invalid-absolute-path [:phases idx :cwd] (:cwd phase))]))
                           (range)
                           (:phases profile))
                   (mapcat (fn [idx artifact]
                             (concat
                               (keep identity
                                     (map-indexed (fn [input-idx path]
                                                    (invalid-absolute-path [:derived-artifacts idx :inputs input-idx] path))
                                                  (:inputs artifact)))
                               (keep identity
                                     (map-indexed (fn [output-idx path]
                                                    (invalid-absolute-path [:derived-artifacts idx :outputs output-idx] path))
                                                  (:outputs artifact)))))
                           (range)
                           (:derived-artifacts profile))))]
    (when (seq errors)
      (throw (ex-info "Invalid normalized profile paths"
                      {:source-path (:source-path profile)
                       :errors errors})))
    profile))

(defn normalize-profile [profile source-path]
  (let [root (bio/resolve-path (or (.getParent (io/file source-path)) ".") (:root-path profile))
        base (-> profile
                 (assoc :kind "project-profile")
                 (assoc :source-path (bio/absolute-path source-path))
                 (assoc :root-path root)
                 (update :verification-policy-path #(bio/resolve-path root %))
                 (update :code-paths #(mapv (partial bio/resolve-path root) %))
                 (update :docs-paths #(mapv (partial bio/resolve-path root) %))
                 (update :formal-paths #(mapv (partial bio/resolve-path root) %))
                 (update :test-paths #(mapv (partial bio/resolve-path root) %))
                 (update :artifact-paths (fn [m]
                                           (into {}
                                                 (map (fn [[k v]] [k (bio/resolve-path root v)]))
                                                 m)))
                 (update :canonical-commands #(mapv (partial normalize-command root) %))
                 (update :subsystems #(mapv (partial normalize-subsystem root) %))
                 (update :derived-artifacts #(mapv (partial normalize-derived-artifact root) %)))
        normalized (update base :phases #(mapv (partial normalize-phase base) %))]
    (validate-normalized-profile! normalized)))

(defn load-profile [path]
  (let [data (bio/read-data path)
        result (schema/validate-profile-data data)]
    (when-not (:valid? result)
      (throw (ex-info "Invalid project profile" {:path path :validation result})))
    (normalize-profile data path)))

(defn subsystem-rel-globs [subsystem]
  (vec (concat (:code-globs subsystem)
               (:docs-globs subsystem)
               (:formal-globs subsystem)
               (:test-globs subsystem))))

(defn match-subsystems [profile changed-files]
  (let [root (:root-path profile)
        rels (map #(bio/relativize-path root (bio/resolve-path root %)) changed-files)]
    (->> (:subsystems profile)
         (filter (fn [subsystem]
                   (some (fn [rel]
                           (any-glob-match? (subsystem-rel-globs subsystem) rel))
                         rels)))
         vec)))

(defn subsystem-by-name [profile subsystem-name]
  (or (first (filter #(= subsystem-name (:name %)) (:subsystems profile)))
      (when subsystem-name
        (let [root (:root-path profile)
              relativize-path-glob (fn [path]
                                     (str (bio/relativize-path root path) "/**/*"))]
          {:name subsystem-name
           :code-globs (mapv relativize-path-glob (:code-paths profile))
           :docs-globs (mapv relativize-path-glob (:docs-paths profile))
           :formal-globs (mapv relativize-path-glob (:formal-paths profile))
           :test-globs (mapv relativize-path-glob (:test-paths profile))}))))

(defn match-glob-rules [profile changed-files]
  (let [root (:root-path profile)
        rels (map #(bio/relativize-path root (bio/resolve-path root %)) changed-files)]
    (->> (:file-glob-rules profile)
         (filter (fn [rule]
                   (some #(path-match? (:glob rule) %) rels)))
         vec)))

(defn change-surface-kind [profile path]
  (let [resolved (bio/resolve-path (:root-path profile) path)
        rel-path (bio/relativize-path (:root-path profile) resolved)]
    (cond
      (some #(bio/path-within? % resolved) (:code-paths profile)) :code
      (or (some #(bio/path-within? % resolved) (:docs-paths profile))
          (requirement-source-match? profile rel-path)) :docs
      (some #(bio/path-within? % resolved) (:formal-paths profile)) :formal
      (some #(bio/path-within? % resolved) (:test-paths profile)) :tests
      :else :other)))

(defn profile-summary [profile]
  {:project-name (:project-name profile)
   :root-path (:root-path profile)
   :subsystem-count (count (:subsystems profile))
   :command-count (count (:canonical-commands profile))
   :phase-count (count (:phases profile))
   :derived-artifact-count (count (:derived-artifacts profile))
   :requirement-source-count (count (:requirement-sources profile))})

(defn debug-profile-data
  ([profile]
   (debug-profile-data profile []))
  ([profile changed-files]
   (let [changed-files* (vec changed-files)
         rel-changed-files (mapv #(bio/relativize-path (:root-path profile)
                                                       (bio/resolve-path (:root-path profile) %))
                                 changed-files*)
         matched-subsystems (when (seq changed-files*)
                              (match-subsystems profile changed-files*))
         matched-rules (when (seq changed-files*)
                         (match-glob-rules profile changed-files*))]
     {:summary (profile-summary profile)
      :source-path (:source-path profile)
      :normalized-paths {:root-path (:root-path profile)
                         :verification-policy-path (:verification-policy-path profile)
                         :artifact-paths (:artifact-paths profile)
                         :code-paths (:code-paths profile)
                         :docs-paths (:docs-paths profile)
                         :formal-paths (:formal-paths profile)
                         :test-paths (:test-paths profile)}
      :canonical-commands (mapv #(select-keys % [:id :kind :role :cwd :command :description :output-path])
                                (:canonical-commands profile))
      :phases (mapv #(select-keys % [:id :action :cwd :output-path :output-extension :artifact-kind :template :evidence-id])
                    (:phases profile))
      :subsystems (mapv (fn [subsystem]
                          {:name (:name subsystem)
                           :artifact-root (:artifact-root subsystem)
                           :code-globs (:code-globs subsystem)
                           :docs-globs (:docs-globs subsystem)
                           :formal-globs (:formal-globs subsystem)
                           :test-globs (:test-globs subsystem)
                           :expected-evidence (:expected-evidence subsystem)
                           :expected-artifacts (:expected-artifacts subsystem)})
                        (:subsystems profile))
      :derived-artifacts (mapv #(select-keys % [:name :kind :inputs :outputs])
                               (:derived-artifacts profile))
      :requirement-sources (mapv #(select-keys % [:kind :globs :id-scheme])
                                 (:requirement-sources profile))
      :change-debug (when (seq changed-files*)
                      {:changed-files rel-changed-files
                       :matched-subsystems (mapv (fn [subsystem]
                                                   {:name (:name subsystem)
                                                    :globs (subsystem-rel-globs subsystem)})
                                                 matched-subsystems)
                       :matched-rules (mapv #(select-keys % [:glob :subsystem :mechanism-family :concern-class :contract :formal-module :test-path :risk-note])
                                            matched-rules)})})))

(def ^:dynamic *subsystem-fingerprint-cache* nil)

(defn subsystem-files [profile subsystem]
  (let [root (:root-path profile)
        globs (subsystem-rel-globs subsystem)
        all-files (bio/repo-files root)]
    (filterv #(any-glob-match? globs %) all-files)))

(defn- subsystem-fingerprint-uncached [profile subsystem]
  (let [root (:root-path profile)
        files (subsystem-files profile subsystem)
        config-files (->> [(:source-path profile)
                           (or (:verification-policy-path profile)
                               (bio/resolve-path root ".bridge/verification-policy.yaml"))]
                          (filter bio/exists?)
                          (map #(bio/relativize-path root %)))
        sorted-files (sort (distinct (concat files config-files)))
        digest (java.security.MessageDigest/getInstance "SHA-256")]
    (doseq [f sorted-files]
      (let [fp (bio/resolve-path root f)
            mtime (bio/last-modified-ms fp)
            size (.length (io/file fp))]
        (.update digest (.getBytes (str f ":" mtime ":" size "\n") "UTF-8"))))
    (let [hash-bytes (.digest digest)]
      (->> hash-bytes
           (map #(format "%02x" %))
           (apply str)))))

(defn subsystem-fingerprint [profile subsystem]
  (let [cache *subsystem-fingerprint-cache*]
    (if cache
      (let [k [(:source-path profile) (:name subsystem)]]
        (if-let [hit (get @cache k)]
          hit
          (let [res (subsystem-fingerprint-uncached profile subsystem)]
            (swap! cache assoc k res)
            res)))
      (subsystem-fingerprint-uncached profile subsystem))))
