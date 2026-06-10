# Changelog

Notable changes to Bridge's public surfaces. The library contract is defined
in [docs/api.md](docs/api.md); only `bridge.api` changes are tracked at
contract level — internal namespaces may change in any commit.

## Unreleased

### Added

- `bridge.api`: the public library API namespace. Exactly the surface the
  Vis bridge extension consumes, with per-var stability tags
  (`:stable` / `:experimental`) and a machine-readable inventory via
  `(bridge.api/contract)`. See [docs/api.md](docs/api.md).
  - Stable: `load-profile`, `profile-summary`, `load-policy`, `init!`,
    `normalize-evidence-kind`, `list-commands`, `run-command`,
    `find-artifacts`, `resolve-path`, `relativize-path`, `exists?`,
    `read-data`, `contract`.
  - Experimental (shape will change with the canonical status-summary
    work): `build-status`, `planned-actions`, `next-action`.
