(ns bridge.summary
  "Canonical machine-readable status summary.

  This is the single flattened projection of bridge.next/build-status
  that all machine consumers share: `bb bridge check --format summary`
  prints it, bridge.api/check returns it. The shape carries
  a :summary-version so consumers can detect evolution explicitly.

  Required obligations are failed-first then open, each obligation is
  flattened with its evidence kinds and first runnable command, and
  evidence-run receipts are listed with normalized kinds."
  (:require [bridge.artifacts :as artifacts]
            [bridge.evidence :as evidence]
            [bridge.io :as bio]
            [bridge.next :as next-guide]))

(def summary-version
  "Version of the canonical status-summary shape. Bump on any change to
  field names, nesting, or semantics, with a CHANGELOG entry."
  1)

(defn flatten-obligation
  "Flatten one build-status obligation into the canonical shape:
  `:kind`, `:subject`, `:artifact`, `:summary`, `:reason`, `:state`,
  `:required-evidence`, `:actions`, plus `:evidence-kinds` (vector),
  `:evidence-kind` (only when exactly one kind), and `:command` (first
  runnable action, only when actions exist)."
  [obligation]
  (let [required-evidence (vec (:required-evidence obligation))]
    (cond-> (select-keys obligation [:kind :subject :artifact :summary :reason :state
                                     :required-evidence :actions])
      true (assoc :evidence-kinds required-evidence)
      (= 1 (count required-evidence)) (assoc :evidence-kind (first required-evidence))
      (seq (:actions obligation)) (assoc :command (first (:actions obligation))))))

(defn evidence-receipts
  "List all evidence-run receipts under the status's artifact root as
  flat maps: `:id`, `:kind` (normalized), `:role`, `:subject`,
  `:status`, `:execution-status`, `:finished-at`, `:command`. Returns
  [] when the status has no artifact root."
  [status]
  (let [artifact-root (when-let [root (:artifact-root status)]
                        (if-let [profile-root (:profile-root status)]
                          (bio/resolve-path profile-root root)
                          root))]
    (->> (when artifact-root
           (artifacts/find-artifacts artifact-root))
         (filter #(= "evidence-run" (:artifact %)))
         (mapv (fn [artifact]
                 {:id (or (:evidence-id artifact) (:id artifact))
                  :kind (some-> (:kind artifact) evidence/normalize-evidence-kind)
                  :role (:role artifact)
                  :subject (:subject artifact)
                  :status (some-> (:evidence-status artifact) str)
                  :execution-status (some-> (:execution-status artifact) str)
                  :finished-at (:finished-at artifact)
                  :command (:command artifact)})))))

(defn status-summary
  "Project a bridge.next/build-status result into the canonical
  machine-readable summary:

    {:summary-version 1
     :project :status :issue-count :changed-files :change-detection
     :counts {:required-obligations :recommended-obligations
              :stale-artifacts :subject-problems
              :receipts :passed-receipts}
     :required-obligations [...]    ; flattened, failed first then open
     :recommended-obligations [...] ; flattened
     :stale-artifacts [...]
     :subject-problems [...]
     :evidence-receipts [...]
     :convergence-summary {...}
     :next-action {...}|nil}        ; first runnable action, failed first

  Pure projection apart from reading evidence receipts from the
  status's artifact root."
  [status]
  (let [required (mapv flatten-obligation
                       (concat (:failed-obligations status)
                               (:open-obligations status)))
        recommended (mapv flatten-obligation (:recommended-obligations status))
        receipts (evidence-receipts status)]
    {:summary-version summary-version
     :project (:project status)
     :status (:status status)
     :issue-count (:issue-count status)
     :changed-files (vec (:changed-files status))
     :change-detection (:change-detection status)
     :counts {:required-obligations (count required)
              :recommended-obligations (count recommended)
              :stale-artifacts (count (:stale-artifacts status))
              :subject-problems (count (:subject-problems status))
              :receipts (count receipts)
              :passed-receipts (count (filter #(= "passed" (:status %)) receipts))}
     :required-obligations required
     :recommended-obligations recommended
     :stale-artifacts (vec (:stale-artifacts status))
     :subject-problems (vec (:subject-problems status))
     :evidence-receipts receipts
     :convergence-summary (:convergence-summary status)
     :next-action (next-guide/next-action status)}))
