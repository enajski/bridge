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
| `check` | Run the verification status check and return the **canonical status summary** (`:summary-version` 1) — flattened required/recommended obligations (failed first), evidence receipts, counts, and next action. Same shape as `bb bridge check --format summary`/`summary-json`. The supported way to consume check status. |
| `status-summary` | Project an existing `build-status` result into the canonical summary. |
| `find-artifacts` | Read all Bridge artifacts under a directory, with `:_path` back-references. |
| `resolve-path`, `relativize-path`, `exists?`, `read-data` | Path/data utilities with the same semantics Bridge uses internally — for profile discovery and policy reading on the consumer side. |
| `contract` | The API inventory as data. |

## Experimental

| Var | Purpose | Why experimental |
| :--- | :--- | :--- |
| `build-status` | Full raw verification status (the unprojected backbone behind `check`). | The raw shape is not a contract; the canonical summary (`check`) is. Direct consumers should select keys, not rely on the full shape. |
| `planned-actions` | Runnable evidence actions derived from a raw status, failed-first. | Tied to the raw `build-status` shape; the first action is already embedded in the summary as `:next-action`. |
| `next-action` | First planned action or nil. | Tied to the raw `build-status` shape; embedded in the summary as `:next-action`. |

The canonical status summary (decision D6 in the bridge-vis design wiki)
landed as `bridge.summary`/`bridge.api/check`: the summary flattening that
previously lived in the Vis extension now lives in the kernel, the shape
carries `:summary-version` for explicit evolution, and `bb bridge check
--format summary|summary-json` prints the identical shape so CLI and library
consumers share one meaning. `build-status` stays available as the raw,
experimental input for consumers that genuinely need unprojected detail.

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
