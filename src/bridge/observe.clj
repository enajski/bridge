(ns bridge.observe
  (:require [bridge.io :as bio]
            [bridge.profile :as profile]
            [clojure.string :as str]))

(defn- rule->observable [profile rule]
  {:name (or (:mechanism-family rule) (:subsystem rule) "observable")
   :source {:path (or (:formal-module rule)
                      (:test-path rule)
                      (first (:code-paths profile))
                      "src")
            :lines "TBD"}
   :trigger-point (if (= "safety" (some-> (:concern-class rule) str)) "after" "derived")
   :capture-timing (if (:test-path rule) "post-state" "mixed")
   :snapshot-strength (if (:formal-module rule) "derived" "strong")
   :collection-mode (cond
                      (:formal-module rule) "replay"
                      (:test-path rule) "instrumentation"
                      :else "other")
   :semantics-scope (if (:formal-module rule) "both" "base")
   :captures (vec (keep identity [(:contract rule) (:risk-note rule)]))
   :maps-to (vec (keep identity [(:contract rule) (:formal-module rule)]))
   :used-by (vec (concat
                  (when (:test-path rule) ["differential test"])
                  (when (:formal-module rule) ["trace validation"])
                  (when-not (or (:test-path rule) (:formal-module rule)) ["review"])))})

(defn- command->observable [cmd]
  {:name (str (:id cmd) "-output")
   :source {:path (or (:cwd cmd) (:command cmd))
            :lines "command"}
   :trigger-point "derived"
   :capture-timing "post-state"
   :snapshot-strength "derived"
   :collection-mode (case (str (:kind cmd))
                      "trace-validation" "replay"
                      "benchmark" "log-parsing"
                      "instrumentation")
   :semantics-scope (if (= "trace-validation" (str (:kind cmd))) "trace" "base")
   :captures [(or (:description cmd) (:id cmd))]
   :maps-to [(str (:kind cmd))]
   :used-by [(str (or (:role cmd) "regression"))]})

(defn generate-observable-contract [profile opts]
  (let [{:keys [subsystem-name changed-files subject]}
        (if (map? opts)
          opts
          {:subsystem-name opts :changed-files []})
        subsystem (profile/subsystem-by-name profile subsystem-name)
        rules (if (seq changed-files)
                (profile/match-glob-rules profile changed-files)
                (filter #(= subsystem-name (:subsystem %)) (:file-glob-rules profile)))
        observables (vec (concat
                          (map #(rule->observable profile %) rules)
                          (take 2 (map command->observable (:canonical-commands profile)))))]
    {:artifact "observable-contract"
     :subject (or subject subsystem-name (:project-name profile))
     :observables (if (seq observables)
                    observables
                    [{:name (str subsystem-name "-observable")
                      :source {:path (or (first (:code-globs subsystem)) "src") :lines "TBD"}
                      :trigger-point "after"
                      :capture-timing "post-state"
                      :snapshot-strength "strong"
                      :collection-mode "other"
                      :semantics-scope "base"
                      :captures []
                      :maps-to []
                      :used-by ["review"]}])
     :constraints (vec (concat
                        (when (= "async-runtime" (some-> subsystem :system-category name))
                          ["Track waiter/waker timing and registration windows explicitly."])
                        (when (= "lock-free" (some-> subsystem :system-category name))
                          ["Record snapshot strength and memory-model assumptions for shared-memory observables."])))
     :notes (vec (concat
                  (when (seq rules)
                    [(str "Generated from " (count rules) " file-glob rule(s).")])
                  (when (some #(= "trace-validation" (str (:kind %))) (:canonical-commands profile))
                    ["Trace/conformance semantics may differ from base semantics; keep both explicit."])))}))
