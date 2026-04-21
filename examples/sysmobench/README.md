# SysMoBench Bridge profile

Thin-slice Bridge profile for SysMoBench `spin` workflow.

## Scope

Targets first eval pain points:

- path normalization across sibling repos
- executable evidence planning
- generated `specTrace.*` freshness
- trace-validation and invariant reruns

This example profile is canonical regression fixture for SysMoBench spin truthfulness work. Treat project-local profiles as adaptations of this fixture, not separate source of truth.

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

## Notes

- `spin-generated-wrapper-check` only lists generated `specTrace` files. Good for quick freshness/debug checks.
- `examples/sysmobench/evaluation-profile.yaml` remains separate evaluation profile for Bridge-on-SysMoBench scoring.
- If local env does not use `.venv`, change command strings or wrap them in committed shell scripts.
