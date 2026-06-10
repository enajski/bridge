# Bridge Library API (`bridge.api`)

`bridge.api` is the only supported entry point for consuming Bridge as a
library. Everything else under `bridge.*` is internal and may change in any
commit without notice.

The surface is deliberately an **honest list**: it contains exactly the
operations the reference consumer — the Vis bridge extension
(`vis-foundation-bridge`) — needs, with each var tagged by how much you can
rely on it. It is not an aspirational "everything Bridge can do" surface.
New vars are added when a consumer demonstrates a need, not preemptively.

Call `(bridge.api/contract)` at a REPL to get this inventory as data
(`{:name :stability :arglists :doc}` per var).

## Stability tiers

| Tag | Meaning |
| :--- | :--- |
| `:stable` | Argument and return shapes are a contract. Breaking changes happen only with a version bump and a CHANGELOG entry. |
| `:experimental` | Safe to call today, but the return shape is expected to change. Select the keys you need; do not depend on the full shape. |

Every public var in `bridge.api` carries a `:bridge.api/stability` metadata
tag and a docstring; the test suite enforces both, and pins the exact
membership of each tier so moving a var between tiers is an explicit,
reviewed change.

## Stable

| Var | Purpose |
| :--- | :--- |
| `load-profile` | Read, validate, and normalize a project profile (all paths absolute). |
| `profile-summary` | Small fixed summary map of a loaded profile. |
| `load-policy` | Read and validate a verification policy. |
| `init!` | Bootstrap a `.bridge/` layout with starter profile + policy. The output is heuristic starter state — consumers must not treat it as authoritative project semantics. |
| `normalize-evidence-kind` | Canonicalize an evidence-kind string/keyword (e.g. `"unit"` → `"unit-tests"`). |
| `list-commands` | Flat descriptors of the profile's canonical evidence commands. |
| `run-command` | Execute one evidence command, write captures + a schema-validated `evidence-run` receipt, return the receipt. Supports `:dry-run?`. |
| `find-artifacts` | Read all Bridge artifacts under a directory, with `:_path` back-references. |
| `resolve-path`, `relativize-path`, `exists?`, `read-data` | Path/data utilities with the same semantics Bridge uses internally — for profile discovery and policy reading on the consumer side. |
| `contract` | The API inventory as data. |

## Experimental

| Var | Purpose | Why experimental |
| :--- | :--- | :--- |
| `build-status` | Full verification status (the backbone of `bb bridge check` / Vis `br/check`). | The shape predates the planned canonical machine-readable status summary; field names and nesting will change when that lands. |
| `planned-actions` | Runnable evidence actions derived from a status, failed-first. | Tied to the `build-status` shape. |
| `next-action` | First planned action or nil. | Tied to the `build-status` shape. |

The experimental tier is where the status-summary work (decision D6 in the
bridge-vis design wiki) will land: Bridge will own a canonical
status-summary shape, and the summary flattening that today lives in the Vis
extension will move behind this namespace. When that happens, `build-status`
either stabilizes or is superseded by a stable summary function, with a
CHANGELOG entry and a migration note either way.

## What is deliberately NOT in the API

- **Schema internals** (`bridge.schema`) — consumers see validation results
  through `load-profile`/`load-policy` errors and receipt validation, not the
  validator itself.
- **Policy evaluation** (`bridge.policy` rule matching, obligation
  derivation) — that is kernel semantics; consumers receive its *results*
  via `build-status`. A consumer needing rule matching directly is a design
  smell (a second policy engine growing outside Bridge).
- **Workflow phases, brief/observable generation, eval, adapters** — CLI-level
  orchestration. If a library consumer needs these, that is a new contract
  conversation, not a casual import.
- **Rendering** (`bridge.next/render-plain`, `bridge.tui`) — presentation
  belongs to consumers.

If you find yourself requiring a `bridge.*` namespace other than
`bridge.api`, file the gap rather than depending on internals — the honest
list only stays honest if it grows through declared needs.

## Versioning and change policy

- Bridge is currently consumed via git-SHA pins. From this point on:
  - breaking changes to `:stable` vars require a CHANGELOG entry **before**
    consumers are expected to bump their pin;
  - `:experimental` shape changes are noted in the CHANGELOG but do not
    block a pin bump.
- Tagged releases should follow once the experimental tier settles; pins
  should then move only between tags.
- The membership of each tier is pinned by `bridge.api-test`; changing it
  requires updating the test, this document, and the CHANGELOG together.
