# bridge

Bridge is lightweight verification-aware workflow tooling for serious codebases.

Bridge is not theorem prover, model checker, benchmark suite, or LLM provider wrapper. Bridge is control plane around those tools. It keeps these surfaces aligned as systems change:

- code
- natural-language specs and contracts
- tests
- runtime assertions and observables
- evidence records
- AI-agent prompts and phase handoff artifacts
- optional formal models

Bridge is change-centered. Teams can start from code diff, spec diff, test diff, or mixed change. The stable workflow is profile + policy + `bb bridge next` + evidence receipts. Formal verification, evaluation adapters, and generated formal linkage are experimental opt-in extensions.

## What problem Bridge solves

Mature repos drift. Code changes land before docs. Tests lag behind intent. Formal models cover only core slices. AI-agent context disappears between sessions. Bridge makes that drift explicit and replayable.

Bridge adds:

- machine-readable project profiles
- schema-validated artifacts
- verification policy with evidence expectations
- diff/change intake into `change-intent-card`
- completeness, omission, and verification-scope ledgers
- observable contracts for traces/runtime instrumentation
- prompt templates rendered with exact repo paths and commands
- evidence command registry and run receipts
- phase-aware workflow with explicit durable and ephemeral handoff outputs
- experimental feasibility-study, evaluation, and formal-linkage support

## Feature maturity

Stable:

- project profiles and verification policies
- `bb bridge check` and `bb bridge next`
- change analysis from profile globs
- evidence command listing, dry runs, execution receipts, and status

Experimental:

- formal-linkage cards and formal-module mapping
- proof/model-check/formal-spec evidence workflows
- SysMoBench adapter and evaluation profiles
- generated prompt/phase helpers, feasibility reports, and completeness ledgers

## Artifact types

Core artifacts:

- `bridge-index`
- `namespace-bridge-card`
- `formal-linkage-card`
- `differential-evidence-card`
- `assumption-ledger`
- `change-impact-report`
- `specification-quality-card`
- `spot-evidence-card`
- `verification-policy`
- `verification-brief`
- `change-intent-card`
- `completeness-ledger`
- `verification-scope-ledger`
- `observable-contract`
- `omission-decision-record`
- `evaluation-profile`
- `evaluation-report`
- `verification-changelog`
- `feasibility-study`

See `docs/internal/artifacts.md` for the storage split and artifact reference.

## Repo layout

- `src/bridge/` — core implementation
- `resources/bridge/schemas.edn` — canonical schemas and enums
- `resources/bridge/templates/` — prompt templates
- `examples/` — generic and SysMoBench-oriented examples
- `docs/` — format, workflow, architecture, and evaluation docs
- `test/` — focused automated tests

## Install / run

Requirements:

- Clojure CLI
- babashka optional, for `bb` tasks

Run help:

```bash
clojure -M:bridge help
# or
bb bridge help
```

Run tests:

```bash
clojure -M:test
# or
bb test
```

## CLI commands

Stable workflow:

```bash
bb bridge init
bb bridge debug-profile --profile .bridge/profile.edn
bb bridge analyze-change --profile .bridge/profile.edn --changed-file src/example.clj
bb bridge next --profile .bridge/profile.edn
bb bridge list-evidence --profile .bridge/profile.edn
bb bridge run-evidence --profile .bridge/profile.edn --id unit --dry-run
```

Experimental workflow examples:

```bash
bb bridge init-profile --path examples/demo/profile.edn
bb bridge list-templates
bb bridge render-prompt --template repo-inventory --profile examples/sysmobench/profile.edn --out .bridge/artifacts/prompts/repo-inventory.md
bb bridge validate-artifact examples/sysmobench/verification-policy.yaml
bb bridge validate-dir .bridge/artifacts
bb bridge summary --profile examples/sysmobench/profile.edn
bb bridge query --profile examples/sysmobench/profile.edn spin-trace-pipeline workflow-state
bb bridge stub-artifact --kind verification-brief --out /tmp/brief.yaml
bb bridge coverage --profile examples/sysmobench/profile.edn
bb bridge missing-artifacts --profile examples/sysmobench/profile.edn
bb bridge analyze-change --profile examples/sysmobench/profile.edn --changed-file ../SysMoBench/tla_eval/core/verification/runner.py --out /tmp/change-intent.yaml
bb bridge generate-brief --profile examples/sysmobench/profile.edn --changed-file ../SysMoBench/tla_eval/core/verification/runner.py
bb bridge generate-observable --profile examples/sysmobench/profile.edn --subsystem spin-trace-pipeline
bb bridge plan-seed --profile examples/sysmobench/profile.edn --changed-file ../SysMoBench/tla_eval/core/verification/runner.py
bb bridge convergence --profile examples/sysmobench/profile.edn
bb bridge completeness --profile examples/sysmobench/profile.edn
bb bridge init-phase --profile examples/sysmobench/profile.edn --phase analyze
bb bridge run-phase --profile examples/sysmobench/profile.edn --phase analyze --changed-file ../SysMoBench/tla_eval/core/verification/runner.py
bb bridge run-phases --profile examples/sysmobench/profile.edn --changed-file ../SysMoBench/tla_eval/core/verification/runner.py
bb bridge list-evidence --profile examples/sysmobench/profile.edn
bb bridge run-evidence --profile examples/sysmobench/profile.edn --id spin-trace-validation --dry-run
bb bridge run-evidence --profile examples/sysmobench/profile.edn --id spin-trace-validation --subject spin-trace-pipeline
bb bridge eval --profile examples/sysmobench/evaluation-profile.yaml
bb bridge feasibility-report --artifact examples/sysmobench/feasibility-study.yaml --out examples/sysmobench/feasibility-report.md
```

## Project profiles

Profiles are machine-readable repo maps. They declare:

- root path
- code/docs/test paths, with optional formal paths
- artifact output paths
- canonical evidence commands
- subsystem globs
- optional file-glob mechanism rules
- optional phase definitions
- optional evaluation suite config
- optional coupled subsystem notes and environment assumptions

See `docs/project-profile.md`.

## Verification policy

Verification policy expresses evidence expectations in data, not prose.

Policy can say when a subsystem or change category requires:

- unit tests
- property tests
- runtime assertions
- docs/natural-language spec updates
- formal spec updates
- differential tests
- trace validation / conformance evidence
- benchmarks
- confirmation evidence

Policy also states whether omissions/deferments are allowed and what record they require.

See `docs/verification-policy.md`.

## Change analysis → intent → obligations

Bridge intake flow:

1. changed files enter via CLI or scripted phase
2. profile globs map them to subsystem(s)
3. policy derives expected evidence classes
4. Bridge emits `change-intent-card`
5. Bridge marks missing obligations and stale artifacts
6. later phases create/update brief, observables, ledgers, evidence receipts

This is heuristic in v1. File/directory/subsystem rules drive matching. Deeper semantic diff can be added later without changing artifact shape.

See `docs/change-workflow.md`.

## Verification brief and observable contract

Bridge now includes generation helpers for both artifacts:

- `generate-brief`
- `generate-observable`
- `plan-seed`
- `convergence`
- `summary`
- `query`

Recommended agent flow:

```text
summary -> identify subsystem -> query specific fields -> read full artifact only if editing it
```


`verification-brief` is the first durable handoff artifact after analysis. It records:

- system/workflow category
- mechanism families
- method split: model-checkable, test-verifiable, review-only, out-of-scope
- non-goals
- environment assumptions
- next handoff outputs

`observable-contract` records runtime evidence surface:

- observable name
- source path and line range
- trigger point
- capture timing: pre/post/mixed
- snapshot strength: weak/strong/derived
- collection mode: instrumentation/log parsing/replay/other
- semantics scope: base / trace / both

This matters when trace/conformance semantics differ from hunt/base semantics.

Bridge’s default split is:

- ephemeral: `change-intent-card`, `change-impact-report`, `plan-seed`, rendered prompts, `evidence-run` receipts
- durable: `verification-brief`, `observable-contract`, `verification-policy`, `completeness-ledger`, `verification-scope-ledger`, `omission-decision-record`, `verification-changelog`, `assumption-ledger`

## Status labels

Bridge uses explicit status vocabularies. Examples:

Finding / result status:

- `suspected`
- `spurious`
- `spec-unfaithful`
- `retracted`
- `reproduced`
- `confirmed`
- `clean-with-scope`
- `expected-window`
- `known-deviation`
- `assumption-dependent-clean`

Workflow state:

- `draft`
- `active`
- `converged`
- `regressed`

Completeness state:

- `required`
- `present`
- `missing`
- `deferred`
- `waived`

See `docs/schema-reference.md`.

## Environment assumptions and non-binary outcomes

Bridge treats environment assumptions as first-class artifacts. Examples:

- transport ordering
- scheduler behavior
- memory model
- runtime delivery
- type-system guarantees

Negative or nuanced outcomes are valid and should be recorded explicitly:

- expected transient window
- known implementation deviation
- clean within stated abstraction boundary
- clean only under explicit assumptions

## System categories and workflow differences

Different categories need different prompts and evidence strategies.

Examples:

- `actor-message` systems emphasize message ordering, actor-local sequentiality, and trace observables.
- `shared-memory` and `lock-free` systems emphasize memory model, atomicity windows, reclamation, and false positive control.
- `async-runtime` and wait/wake-heavy systems need direct attention on registration, deregistration, wake targets, park/unpark pairs, and notification batching.
- `business-rule` and `data-pipeline` systems often need stronger spec quality, completeness ledgers, and differential output evidence more than concurrency modeling.

Bridge makes these categories explicit in profiles and briefs.

## Prompt rendering

Prompt templates are plain files under `resources/bridge/templates/`. Bridge renders them with exact profile paths, commands, and changed files.

List templates:

```bash
bb bridge list-templates
```

Render one:

```bash
bb bridge render-prompt \
  --template diff-intent-obligation-analysis \
  --profile examples/sysmobench/profile.edn \
  --changed-file ../SysMoBench/tla_eval/core/verification/runner.py \
  --out .bridge/artifacts/prompts/diff-intake.md
```

## Validate artifacts

Schemas are explicit and closed. Validator distinguishes:

- missing required fields
- unknown fields
- malformed enum values
- type mismatches

Validate one artifact:

```bash
bb bridge validate-artifact .bridge/artifacts/spin-trace-pipeline-verification-brief.yaml
```

Validate directory:

```bash
bb bridge validate-dir .bridge/artifacts
```

## Interactive and scripted workflow stages

Bridge phases can be driven from CLI or profile-defined phase outputs.

Typical stages:

- analyze
- brief
- prompt
- evidence
- review

Use scripted mode when work spans sessions or agents. Handoff lives in files, not only chat context.

See `docs/workflow-stages.md`.

## Evidence commands

Profiles register replayable evidence commands with kind and role metadata.

Kinds:

- unit
- property
- runtime
- differential
- trace-validation
- benchmark
- proof
- model-check
- symbolic
- custom

Roles:

- conformance
- exploration
- confirmation
- regression

Run one command:

```bash
bb bridge run-evidence --profile examples/sysmobench/profile.edn --id spin-invariant-verification --subject spin-trace-pipeline
```

`run-evidence` executes profile-defined shell commands. Treat third-party profiles as executable configuration: inspect commands with `--dry-run` before running them. Non-dry runs write schema-valid `evidence-run` artifacts under the profile evidence artifact path, and convergence can use those receipts to close or fail matching obligations. Commands default to a 300 second timeout; profile commands or the CLI may set `timeout-seconds`.

## Feasibility-study support

Bridge includes structured feasibility artifacts that separate:

- business/domain fit
- computational-core fit
- full-platform fit
- requirement-by-requirement assessment
- blockers and architecture boundaries
- required PoCs and escalations

See `docs/feasibility.md`.

## Evaluation support

Bridge supports both:

- Bridge-native workflow metrics
- external benchmark metrics

SysMoBench is supported as the first experimental evaluation target for Bridge formal workflow work. Bridge should not overfit to SysMoBench or TLA+ only.

See `docs/evaluation.md`.

## Incremental adoption for legacy repos

Bridge is designed for partial rollout.

Start with:

1. project profile
2. verification policy
3. change-intent card generation
4. completeness + omission tracking
5. one observable contract or verification brief on highest-risk subsystem

You do not need full formalization before Bridge becomes useful.

## Worked example

See `examples/sysmobench/` for a real worked example against sibling repo `../SysMoBench`.
