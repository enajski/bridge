# SysMoBench Bridge profile

Thin-slice Bridge profile for SysMoBench `spin` workflow.

## Scope

Targets first eval pain points:

- path normalization across sibling repos
- executable evidence planning
- generated `specTrace.*` freshness
- trace-validation and invariant reruns

This example profile is canonical regression fixture for SysMoBench spin truthfulness work. Treat project-local profiles as adaptations of this fixture, not separate source of truth.

Important compatibility note:

- current SysMoBench CLI exposes the Phase 3 benchmark metric as `transition_validation`
- older Bridge example text and commands may still say `trace_validation`
- when wiring new runs, treat `transition_validation` as the authoritative CLI name

## Evaluation targets

Use this fixture as the Bridge-owned slice inside a larger workflow comparison matrix.

### Workflow matrix

Primary conditions to compare:

- raw baseline
  - SysMoBench task + benchmark harness only
- codex, no specula, no bridge
  - harness: Codex CLI loop
  - workflow: none (no Bridge, no Specula)
- vis, no bridge
  - harness: Vis operator loop
  - workflow: none (no Bridge, no Specula)
- vis + bridge
  - harness: Vis operator loop
  - workflow kernel: Bridge
- codex + specula
  - harness: Codex CLI loop
  - workflow kernel: Specula
- codex + bridge
  - harness: Codex CLI loop
  - workflow kernel: Bridge

Fixed fairness assumption for workflow comparisons:

- Vis and Codex are compared as harnesses, not as model IDs
- pin the underlying model to `gpt-5.4-mini`
- keep budgets/timeouts fixed across:
  - `codex, no specula, no bridge`
  - `vis, no bridge`
  - `vis + bridge`
  - `codex + specula`
  - `codex + bridge`
- `raw baseline` remains the harness-only condition

Normalization note:

- the requested matrix listed `codex (no bridge)` twice
- this document treats that as one unique condition: `codex, no specula, no bridge`

Current implementation state should be treated as readiness bands, not as an assumption that every condition runs on every machine:

- harness-ready
  - raw baseline
  - existing-spec compilation checks
- workflow-defined but environment-dependent
  - codex, no specula, no bridge
  - vis, no bridge
  - vis + bridge
  - codex + bridge
- external comparison target
  - codex + specula

Use the matrix runner to record `ready`, `partial`, or `blocked` status per machine before treating a condition as executable.

## Evaluation tracks

Keep these tracks separate in analysis and reporting.

### Track 1: external benchmark truth

- SysMoBench trace validation
- SysMoBench invariant verification
- parsed primary failure signals

### Track 2: Bridge workflow quality

- artifact validity
- open-obligation detection
- stale derived-artifact detection
- convergence versus regression classification
- phase handoff completeness

### Track 3: Vis operator-surface quality

This is the next instrumentation target rather than a fully implemented fixture today.

- turns to first correct evidence action
- wasted or irrelevant evidence runs
- recovery quality after failed trace validation
- compliance with `br/check`, `br/next`, and `br/run-evidence`
- final benchmark result conditional on staying inside the Vis tool surface

## Actionable plan

### Phase 1: lock down the benchmark-facing baseline

1. validate direct SysMoBench runs for the chosen task slice
2. validate the harness path with an existing on-disk `spin.tla` and `spin.cfg`
3. validate the Codex runtime path separately from the harness path
3. record the exact task, metric, model, agent, budget, and timeout settings
4. treat this as the canonical raw comparison surface before adding workflow layers

### Phase 2: stabilize Bridge-on-SysMoBench

1. validate local profile parity with this fixture
2. confirm evidence dry-runs resolve correct cwd, output roots, and parsers
3. run trace-validation and invariant-verification receipts
4. run `bb bridge eval --profile examples/sysmobench/evaluation-profile.yaml`
5. collect evaluation reports across chosen model or agent variants

### Phase 3: add the workflow matrix conditions

Implement conditions in this order:

1. codex, no specula, no bridge
2. codex + bridge
3. vis, no bridge
4. vis + bridge
5. codex + specula comparison capture

Each condition should hold constant:

- task set
- source repositories
- trace harnesses
- action scope
- timeouts
- budget ceilings
- repaired versus unrepaired policy

Recommended condition ids:

- `raw-baseline`
- `codex-no-specula-no-bridge`
- `vis-no-bridge`
- `vis-bridge`
- `codex-specula`
- `codex-bridge`

### Phase 4: add Vis-specific instrumentation

1. capture tool usage inside Vis
2. distinguish plain Vis actions from Bridge-mediated actions
3. log next-action suggestions and whether they were followed
4. count failed, irrelevant, or repeated runs before useful evidence
5. emit a Vis-side operator report that can be compared to the Bridge evaluation report

### Phase 5: publish comparison outputs

Each run should record:

- condition id
- task and method
- model alias
- coding agent
- benchmark outcomes
- Bridge workflow outcomes when Bridge is present
- operator-surface outcomes when Vis is present
- repair usage when repair is allowed
- cost and elapsed time

Profile expects local layout:

- Bridge repo at `../bridge`
- SysMoBench repo at `../SysMoBench`

From Bridge repo, profile path is:

```bash
examples/sysmobench/profile.edn
```

It points at sibling repo:

```text
../../../SysMoBench
```

## Prereqs

Inside `SysMoBench`:

```bash
pip install -e .
sysmobench-setup
```

Evidence commands assume:

- `.venv` exists under repo root, or command adjusted
- `sysmobench` CLI available after activation
- provider creds exported

Optional env knobs:

- `BRIDGE_SYSMOBENCH_METHOD` default `direct_call`
- `BRIDGE_SYSMOBENCH_MODEL` default `claude`

Workflow-specific env knobs to pin when the agent path is used:

- Codex model: `gpt-5.4-mini`
- TV agent: `codex`
- TV budget and timeout must be held constant across all workflow conditions

## Matrix runner

The canonical workflow-matrix runner now lives in `SysMoBench`, not here.

Use:

```bash
python3 ../SysMoBench/eval/workflow_matrix/run_matrix.py --json
```

Optional sanity run:

```bash
python3 ../SysMoBench/eval/workflow_matrix/run_matrix.py --json --run-sanity
```

That keeps `bridge/examples/` as an example surface while the runnable SysMoBench-specific evaluation logic stays with `SysMoBench`.

## Local profile migration/parity workflow

When adapting into local `.bridge/profile.edn`:

1. copy from `examples/sysmobench/profile.edn`
2. adjust repo-relative paths only
3. run `debug-profile`
4. run `list-evidence`
5. run evidence dry-runs
6. compare cwd, output roots, and parser behavior with example profile

## Debug profile first

```bash
bb bridge debug-profile --profile examples/sysmobench/profile.edn
```

With changed files:

```bash
bb bridge debug-profile \
  --profile examples/sysmobench/profile.edn \
  --changed-file ../SysMoBench/tla_eval/core/trace_generation/spin/trace_converter_impl.py
```

## Analyze spin change

```bash
bb bridge analyze-change \
  --profile examples/sysmobench/profile.edn \
  --changed-file ../SysMoBench/data/convertor/spin/spin_mapping.json \
  --changed-file ../SysMoBench/tla_eval/core/spec_processing/spec_converter.py \
  --changed-file ../SysMoBench/tla_eval/core/trace_generation/spin/trace_converter_impl.py \
  --changed-file ../SysMoBench/tla_eval/core/verification/tlc_runner.py \
  --changed-file ../SysMoBench/tla_eval/evaluation/consistency/trace_validation.py
```

Expected phase-1 signals:

- mechanism families like `trace-conversion`, `verification-harness`, `specTrace-generation`
- non-empty `missing-obligations`
- stale derived artifacts for `specTrace.*` when generator inputs changed

## Generate planning artifacts

```bash
bb bridge generate-brief \
  --profile examples/sysmobench/profile.edn \
  --changed-file ../SysMoBench/tla_eval/core/trace_generation/spin/trace_converter_impl.py

bb bridge generate-observable \
  --profile examples/sysmobench/profile.edn \
  --subsystem spin-trace-pipeline

bb bridge plan-seed \
  --profile examples/sysmobench/profile.edn \
  --changed-file ../SysMoBench/tla_eval/core/trace_generation/spin/trace_converter_impl.py
```

## Inspect evidence commands

```bash
bb bridge list-evidence --profile examples/sysmobench/profile.edn

bb bridge run-evidence \
  --profile examples/sysmobench/profile.edn \
  --id spin-trace-validation \
  --dry-run
```

Dry-run prints normalized:

- profile root
- cwd
- output root
- shell
- command string
- result-parser presence

For truth-preserving workflows, always dry-run before first real execution on new machine/profile.

## Run evidence

```bash
bb bridge run-evidence \
  --profile examples/sysmobench/profile.edn \
  --id spin-trace-validation

bb bridge run-evidence \
  --profile examples/sysmobench/profile.edn \
  --id spin-invariant-verification
```

Logs land under SysMoBench artifact root:

```text
SysMoBench/.bridge/artifacts/evidence/
```

## Evaluation truth profile

Use:

```bash
bb bridge eval --profile examples/sysmobench/evaluation-profile.yaml
```

Expected semantics:

- shell execution success recorded separately from evidence pass/fail
- trace-validation may report `failed` even if process exit code is `0`
- mixed invariant pass + trace-validation fail should not yield `converged`

Suggested comparison set:

- `raw baseline`
- `codex, no specula, no bridge`
- `vis, no bridge`
- `vis + bridge`
- `codex + specula`
- `codex + bridge`

Only some of these are fully runnable in this repo today. The missing conditions should reuse the same benchmark commands and task slices, then add workflow-specific instrumentation rather than changing the benchmark semantics.

## Notes

- `spin-generated-wrapper-check` only lists generated `specTrace` files. Good for quick freshness/debug checks.
- `examples/sysmobench/evaluation-profile.yaml` remains an illustrative Bridge example.
- Canonical runnable workflow-matrix assets now live under `SysMoBench/eval/workflow_matrix/`.
- If local env does not use `.venv`, change command strings or wrap them in committed shell scripts.
