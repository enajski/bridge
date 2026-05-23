(ns bridge.feasibility
  (:require [bridge.io :as bio]
            [bridge.schema :as schema]
            [clojure.string :as str]))

(defn load-study [path]
  (let [data (bio/read-data path)
        result (schema/validate-artifact-data data {:root (.getParent (java.io.File. path))
                                                    :expected-kind "feasibility-study"})]
    (when-not (:valid? result)
      (throw (ex-info "Invalid feasibility study" {:path path :validation result})))
    data))

(defn study->markdown [study]
  (str "# Feasibility Study: " (:subject study) "\n\n"
       "- Study ID: `" (:study-id study) "`\n"
       "- Recommendation: **" (:recommendation study) "**\n"
       "- Business/domain fit: **" (:business-domain-fit study) "**\n"
       "- Computational-core fit: **" (:computational-core-fit study) "**\n"
       "- Full-platform fit: **" (:full-platform-fit study) "**\n\n"
       "## Summary\n\n"
       (str/join "\n" (map #(str "- " %) (:summary study)))
       "\n\n## Requirement Matrix\n\n"
       "| Requirement | Assessment | Notes |\n|---|---|---|\n"
       (str/join "\n"
                 (map (fn [{:keys [id requirement assessment notes]}]
                        (str "| " id " | " assessment " | " (str/join "; " notes) " |"))
                      (:requirements study)))
       "\n\n## Gaps / Blockers\n\n"
       (str/join "\n" (map #(str "- " %) (:blockers study)))
       "\n\n## Architecture Boundaries\n\n"
       (str/join "\n" (map #(str "- " %) (:architecture-boundaries study)))
       "\n\n## Required PoCs / Escalations\n\n"
       (str/join "\n" (concat (map #(str "- POC: " %) (:required-pocs study))
                              (map #(str "- Escalation: " %) (:escalations study))))
       "\n"))

(defn render-study! [artifact-path out-path]
  (let [study (load-study artifact-path)]
    (bio/write-text out-path (study->markdown study))
    out-path))
