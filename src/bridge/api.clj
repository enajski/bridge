(ns bridge.api
  "Public library API for Bridge.

  This namespace is the only supported entry point for external library
  consumers. The reference consumer is the Vis bridge extension
  (vis-foundation-bridge); the surface below is exactly the set of
  operations that integration needs, no more. Everything not exposed
  here — including every other bridge.* namespace — is internal and may
  change without notice.

  Every public var carries a :bridge.api/stability tag:

    :stable        Argument and return shapes are a contract. Breaking
                   changes only with a version bump and a CHANGELOG entry.
    :experimental  Safe to call today, but the return shape is expected
                   to change. Currently this covers the check/status
                   surface, which will be reshaped when Bridge grows a
                   canonical machine-readable status summary.

  Call (contract) for this inventory as data. See docs/api.md for the
  prose version, stability policy, and migration notes."
  (:require [bridge.artifacts :as artifacts]
            [bridge.cli :as cli]
            [bridge.evidence :as evidence]
            [bridge.io :as bio]
            [bridge.next :as next-guide]
            [bridge.policy :as policy]
            [bridge.profile :as profile]))

;; ---------------------------------------------------------------------------
;; Profile and policy (stable)

(defn ^{:bridge.api/stability :stable} load-profile
  "Read, schema-validate, and normalize a project profile from `path`
  (EDN or YAML). Returns the normalized profile map: `:root-path`,
  surface paths, `:artifact-paths`, command cwds, and phase outputs are
  all resolved to absolute paths. Throws ex-info with `:validation`
  data when the profile is invalid."
  [path]
  (profile/load-profile path))

(defn ^{:bridge.api/stability :stable} profile-summary
  "Small fixed summary of a loaded profile: `:project-name`,
  `:root-path`, and `:subsystem-count` / `:command-count` /
  `:phase-count` / `:derived-artifact-count` /
  `:requirement-source-count`."
  [profile]
  (profile/profile-summary profile))

(defn ^{:bridge.api/stability :stable} load-policy
  "Read and schema-validate a verification policy from `path` (YAML or
  EDN). Returns the policy data unmodified. Throws ex-info with
  `:validation` data when the policy is invalid."
  [path]
  (policy/load-policy path))

;; ---------------------------------------------------------------------------
;; Bootstrap (stable)

(defn ^{:bridge.api/stability :stable} init!
  "Bootstrap Bridge in the directory `(:root opts)` (default \".\").
  Creates the `.bridge/` layout, a starter profile with heuristically
  inferred canonical commands, a default verification policy, and a
  `.gitignore` entry for `.bridge/ephemeral/`. Throws when a profile or
  policy already exists. Returns `{:created [...] :updated [...]
  :next-actions [...]}`.

  Note: the generated profile is a starter state, not project-specific
  verification semantics. Consumers should treat heuristic bootstrap
  output as requiring review or remediation, not as authoritative."
  [opts]
  (cli/command-init opts))

;; ---------------------------------------------------------------------------
;; Evidence (stable)

(defn ^{:bridge.api/stability :stable} normalize-evidence-kind
  "Normalize an evidence-kind string/keyword to its canonical
  evidence-kind string (resolving aliases, case, keywords). Unknown
  kinds pass through lowercased. The canonical vocabulary may grow, but
  existing kinds keep their meaning."
  [kind]
  (evidence/normalize-evidence-kind kind))

(defn ^{:bridge.api/stability :stable} list-commands
  "List the profile's canonical evidence commands as flat descriptors:
  `:id`, `:kind`, `:role`, `:description`, `:command`, `:cwd`,
  `:profile-root`, `:output-path`, `:subject`, `:default-output-root`,
  `:default-artifact-path`, `:timeout-ms`, `:result-parser` (type only)."
  [profile]
  (evidence/list-commands profile))

(defn ^{:bridge.api/stability :stable} run-command
  "Execute one canonical evidence command by `id`. Writes stdout/stderr
  captures and a schema-validated `evidence-run` receipt under the
  profile's evidence output root, and returns the receipt map
  (`:evidence-id`, `:execution-status`, `:evidence-status`,
  `:exit-code`, `:failure-signals`, `:parsed-metrics`, paths, timing).

  `opts` keys: `:out-dir`, `:out-path`, `:subject`, `:timeout-seconds`,
  and `:dry-run?` — with `:dry-run? true` nothing executes and the
  execution plan is returned with `:dry-run? true`. Throws ex-info on
  unknown id, invalid cwd, or execution failure."
  ([profile id] (run-command profile id {}))
  ([profile id opts] (evidence/run-command profile id opts)))

;; ---------------------------------------------------------------------------
;; Artifacts (stable)

(defn ^{:bridge.api/stability :stable} find-artifacts
  "Read all Bridge artifacts under directory `root`. Returns a vector
  of artifact maps, each with its `:artifact` kind, the artifact's own
  fields, and a `:_path` back-reference to the source file."
  [root]
  (artifacts/find-artifacts root))

;; ---------------------------------------------------------------------------
;; Status (experimental)

(defn ^{:bridge.api/stability :experimental} build-status
  "Build the full verification status for a profile — the backbone of
  `bb bridge check` and the Vis `br/check` tool. `opts` keys:
  `:changed-files`, `:git-diff-spec`, `:policy` (preloaded policy data).
  Returns a map including `:status` (\"clear\" or
  \"attention-required\"), `:issue-count`, `:open-obligations`,
  `:failed-obligations`, `:completed-obligations`,
  `:recommended-obligations`, `:stale-artifacts`, `:subject-problems`,
  `:convergence-summary`, `:intent`, and change-detection fields.

  Experimental: this shape predates the planned canonical status
  summary; field names and nesting will change when that lands.
  Consumers should select the keys they need rather than relying on the
  full shape."
  ([profile] (build-status profile {}))
  ([profile opts] (next-guide/build-status profile opts)))

(defn ^{:bridge.api/stability :experimental} planned-actions
  "Derive runnable evidence actions from a `build-status` result,
  failed obligations first. Each action carries `:evidence-id`,
  `:state`, `:summary`, `:reason`, and `:required-evidence`.

  Experimental: tied to the `build-status` shape and will move with it."
  [status]
  (next-guide/planned-actions status))

(defn ^{:bridge.api/stability :experimental} next-action
  "First entry of `planned-actions`, or nil when nothing is runnable.

  Experimental: tied to the `build-status` shape and will move with it."
  [status]
  (next-guide/next-action status))

;; ---------------------------------------------------------------------------
;; Path and data utilities (stable)
;;
;; Convenience re-exports so consumers resolving profile/policy/artifact
;; locations use the same path semantics Bridge uses internally.

(defn ^{:bridge.api/stability :stable} resolve-path
  "Resolve `path` against `root` to a canonical absolute path. Absolute
  paths pass through (canonicalized); nil/blank paths return nil."
  [root path]
  (bio/resolve-path root path))

(defn ^{:bridge.api/stability :stable} relativize-path
  "Relativize `path` against `root`; both are canonicalized first.
  Returns the relative path as a string."
  [root path]
  (bio/relativize-path root path))

(defn ^{:bridge.api/stability :stable} exists?
  "True when a file or directory exists at `path`."
  [path]
  (bio/exists? path))

(defn ^{:bridge.api/stability :stable} read-data
  "Read and parse a data file by extension: EDN or YAML to Clojure
  data (keywordized), anything else as a raw string."
  [path]
  (bio/read-data path))

;; ---------------------------------------------------------------------------
;; Contract introspection

(defn ^{:bridge.api/stability :stable} contract
  "The API inventory as data: a vector of
  `{:name :stability :arglists :doc}`, sorted with stable entries
  first. This is the machine-readable form of the honest list in
  docs/api.md."
  []
  (->> (ns-publics 'bridge.api)
       (map (fn [[sym v]]
              (let [m (meta v)]
                {:name sym
                 :stability (:bridge.api/stability m)
                 :arglists (:arglists m)
                 :doc (:doc m)})))
       (sort-by (juxt #(if (= :stable (:stability %)) 0 1)
                      (comp str :name)))
       vec))
