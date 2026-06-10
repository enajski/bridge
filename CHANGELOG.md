# Changelog

Notable changes to Bridge's public surfaces. The library contract is defined
in [docs/api.md](docs/api.md); only `bridge.api` changes are tracked at
contract level — internal namespaces may change in any commit.

## Unreleased

### Added

- `bridge.api`: the public library API namespace. Per-var stability tags
  (`:stable` / `:experimental`) and a machine-readable inventory via
  `(bridge.api/contract)`. See [docs/api.md](docs/api.md).
  - Stable: `load-profile`, `profile-summary`, `load-policy`, `init!`,
    `normalize-evidence-kind`, `list-commands`, `run-command`,
    `find-artifacts`, `resolve-path`, `relativize-path`, `exists?`,
    `read-data`, `contract`.
  - Experimental (shape will change with the canonical status-summary
    work): `build-status`, `planned-actions`, `next-action`.
- `bridge.summary`: the canonical machine-readable status summary
  (`:summary-version` The flattening semantics (required obligations failed-first,
  per-obligation evidence kinds and first runnable command, evidence-run
  receipt listing, next-action derivation) now live in the kernel.
- `bridge.api/check` (stable): one-call verification check returning the
  canonical summary. `bridge.api/status-summary` (stable): the projection
  from a raw `build-status` result.
- `bb bridge check --format summary` and `--format summary-json`: print the
  canonical summary as EDN or JSON, sharing the exact shape with
  `bridge.api/check`. `bb bridge next` accepts the same formats. Existing
  `--format edn` (raw status dump) is unchanged.

### Changed

- `bridge.api/build-status` docstring: now explicitly the raw, unprojected
  status; consumers should prefer `check`/`status-summary`.
