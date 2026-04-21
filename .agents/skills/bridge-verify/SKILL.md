---
name: bridge-verify
description: >
  Verification-aware change workflow using Bridge CLI. Analyzes code changes against project
  profile and verification policy, identifies missing evidence obligations, generates
  verification briefs and observable contracts, runs evidence commands, and checks convergence.
  Use when user says "verify this change", "check obligations", "run bridge", "analyze change",
  "what evidence is missing", "check convergence", or after significant code changes that need
  verification tracking. Also use when user asks about artifact coverage, completeness ledgers,
  or wants to render Bridge prompts.
---

# Bridge Verification Workflow

You are operating Bridge — a verification-aware workflow toolkit. Bridge tracks alignment between code, specs, tests, formal models, and evidence artifacts. It does NOT modify code or run provers. It structures awareness of what verification work is needed and whether it's been done.

## Prerequisites

Bridge repo must be cloned and accessible. Verify:

```bash
ls -d "$(git rev-parse --show-toplevel 2>/dev/null)/../bridge" 2>/dev/null || ls -d ~/workspace/bridge 2>/dev/null
```

Bridge CLI requires Clojure. Test availability:

```bash
cd <bridge-repo-path> && clojure -M:bridge help
```

If `bb` (babashka) is available, use `bb bridge` instead of `clojure -M:bridge` for faster startup.

## Detect Bridge Context

Before running any Bridge command, locate the project's Bridge profile:

```bash
# Search current project and ancestors
find . .. ../.. -maxdepth 2 -name "profile.edn" -path "*/bridge*" -o -name "profile.edn" -path "*/.agents/*" 2>/dev/null | head -3
# Or check common locations
ls examples/*/profile.edn .bridge/profile.edn bridge/profile.edn 2>/dev/null
```

Set `BRIDGE_PROFILE` to the discovered path. All subsequent commands use `--profile $BRIDGE_PROFILE`.

Set `BRIDGE_ROOT` to the bridge repo directory (where `bb.edn` / `deps.edn` live).

## Core Loop

The verification workflow has 6 phases. Run them in order. Stop early if the user only needs partial coverage.

### Phase 0: Debug Profile

Before analyze-change, verify normalized paths and command execution roots.

```bash
# From git diff
CHANGED_FILES=$(git diff --name-only HEAD~1 2>/dev/null || git diff --name-only --cached)

# Build --changed-file flags
CHANGE_FLAGS=""
for f in $CHANGED_FILES; do
  CHANGE_FLAGS="$CHANGE_FLAGS --changed-file $f"
done

cd $BRIDGE_ROOT && clojure -M:bridge debug-profile \
  --profile $BRIDGE_PROFILE \
  $CHANGE_FLAGS
```

Read debug output first. Key fields:
- normalized `root-path`
- normalized `verification-policy-path`
- normalized artifact roots
- command `cwd`
- phase output paths
- matched subsystems and glob rules

If these are wrong, stop and fix profile before running evidence.

### Phase 1: Analyze Change

Identify what changed and what obligations arise.

```bash
cd $BRIDGE_ROOT && clojure -M:bridge analyze-change \
  --profile $BRIDGE_PROFILE \
  $CHANGE_FLAGS \
  --out /tmp/bridge-change-intent.yaml
```

Read the output. Key fields:
- `missing-obligations` — human-readable obligations
- `missing-obligations-structured` — structured reruns / artifact refreshes / role gaps
- `stale-artifacts` — stale artifact paths
- `stale-artifacts-detailed` — stale generated artifacts with `stale-because`
- `inferred-intent.mechanism-families` — semantic grouping of the change
- `risk-class` — high/medium/low based on subsystem config

Report these to the user before proceeding.

### Phase 2: Generate Brief

Create a verification brief that plans the method split.

```bash
cd $BRIDGE_ROOT && clojure -M:bridge generate-brief \
  --profile $BRIDGE_PROFILE \
  $CHANGE_FLAGS \
  --out /tmp/bridge-brief.yaml
```

Key fields to report:
- `mechanism-families[].verification-method` — model-checkable, test-verifiable, code-review-only, out-of-scope
- `concern-classes` — safety, liveness, performance, spec-fidelity, code-level
- `environment-assumptions` — what ambient assumptions matter
- `co-model-with` — coupled subsystems that must be considered together

### Phase 3: Generate Observable Contract (if applicable)

Only needed when subsystem involves traces, runtime instrumentation, or conformance evidence.

```bash
cd $BRIDGE_ROOT && clojure -M:bridge generate-observable \
  --profile $BRIDGE_PROFILE \
  --subsystem <SUBSYSTEM_NAME> \
  --out /tmp/bridge-observable.yaml
```

### Phase 4: Plan and Execute

Get structured next steps:

```bash
cd $BRIDGE_ROOT && clojure -M:bridge plan-seed \
  --profile $BRIDGE_PROFILE \
  $CHANGE_FLAGS
```

For each obligation in the plan:
1. If obligation is "write tests" → write or update tests
2. If obligation is "update specs" → update docs/specs
3. If obligation is "update formal model" → update formal artifacts
4. If obligation is "create Bridge artifact" → use stub generation:

```bash
cd $BRIDGE_ROOT && clojure -M:bridge stub-artifact \
  --kind <artifact-kind> \
  --out <target-path>
```

Then fill in the stub with real content.

### Phase 5: Run Evidence

List available evidence commands:

```bash
cd $BRIDGE_ROOT && clojure -M:bridge list-evidence --profile $BRIDGE_PROFILE
```

Dry-run first:

```bash
cd $BRIDGE_ROOT && clojure -M:bridge run-evidence \
  --profile $BRIDGE_PROFILE \
  --id <evidence-id> \
  --dry-run
```

Then run relevant ones:

```bash
cd $BRIDGE_ROOT && clojure -M:bridge run-evidence \
  --profile $BRIDGE_PROFILE \
  --id <evidence-id>
```

Evidence results now include exit code, stdout/stderr paths, timestamps, profile root, profile source path, normalized cwd, output root, shell, and duration. Report failures immediately.

### Phase 6: Validate and Converge

```bash
# Validate all artifacts
cd $BRIDGE_ROOT && clojure -M:bridge validate-dir <artifact-directory>

# Check coverage gaps
cd $BRIDGE_ROOT && clojure -M:bridge coverage --profile $BRIDGE_PROFILE

# Check completeness/omission state
cd $BRIDGE_ROOT && clojure -M:bridge completeness --profile $BRIDGE_PROFILE

# Check convergence
cd $BRIDGE_ROOT && clojure -M:bridge convergence --profile $BRIDGE_PROFILE
```

If convergence reports `regressed` for any subject → go back to Phase 1 with the new state.

If all subjects show `converged` and no missing obligations → report success.

## Shortcut: Run All Phases

For simple changes, run everything in sequence:

```bash
cd $BRIDGE_ROOT && clojure -M:bridge run-phases \
  --profile $BRIDGE_PROFILE \
  $CHANGE_FLAGS
```

This runs analyze → brief → observe → prompt → evidence and returns combined results with handoff and convergence status.

## Quick Commands Reference

| Task | Command |
|------|---------|
| Analyze change | `clojure -M:bridge analyze-change --profile P --changed-file F` |
| Generate brief | `clojure -M:bridge generate-brief --profile P --changed-file F` |
| Generate observable | `clojure -M:bridge generate-observable --profile P --subsystem S` |
| Get plan | `clojure -M:bridge plan-seed --profile P --changed-file F` |
| Render prompt | `clojure -M:bridge render-prompt --template T --profile P` |
| Stub artifact | `clojure -M:bridge stub-artifact --kind K --out O` |
| Validate one | `clojure -M:bridge validate-artifact PATH` |
| Validate dir | `clojure -M:bridge validate-dir DIR` |
| Coverage | `clojure -M:bridge coverage --profile P` |
| Missing artifacts | `clojure -M:bridge missing-artifacts --profile P` |
| Completeness | `clojure -M:bridge completeness --profile P` |
| Convergence | `clojure -M:bridge convergence --profile P` |
| List evidence | `clojure -M:bridge list-evidence --profile P` |
| Run evidence dry-run | `clojure -M:bridge run-evidence --profile P --id ID --dry-run` |
| Run evidence | `clojure -M:bridge run-evidence --profile P --id ID` |
| Debug profile | `clojure -M:bridge debug-profile --profile P --changed-file F` |
| Run all phases | `clojure -M:bridge run-phases --profile P --changed-file F` |
| Run eval | `clojure -M:bridge eval --profile EVAL_PROFILE` |
| Feasibility report | `clojure -M:bridge feasibility-report --artifact A --out O` |
| Init profile | `clojure -M:bridge init-profile --path P` |
| List templates | `clojure -M:bridge list-templates` |

## Artifact Types

Bridge tracks 19 artifact types:

| Artifact | When to create |
|----------|---------------|
| `change-intent-card` | Auto-generated by analyze-change |
| `change-impact-report` | Auto-generated by analyze-change |
| `verification-brief` | Auto-generated by generate-brief, or manually for complex subsystems |
| `observable-contract` | When subsystem has runtime traces or instrumentation |
| `completeness-ledger` | Per subsystem, tracks what verification surfaces exist |
| `verification-scope-ledger` | Per subsystem, tracks what's proved/checked/tested/documented |
| `omission-decision-record` | When required evidence is intentionally deferred or waived |
| `assumption-ledger` | When code/formal/differential assumptions need tracking |
| `verification-changelog` | When evidence iterations produce convergence or regression |
| `verification-policy` | One per project, defines evidence expectations |
| `bridge-index` | Per subsystem, maps code/spec/formal/test surfaces |
| `namespace-bridge-card` | Per namespace, detailed API + formal links |
| `formal-linkage-card` | When formal specs exist for a module |
| `differential-evidence-card` | When differential tests compare code vs formal output |
| `specification-quality-card` | When spec ambiguity or completeness needs review |
| `spot-evidence-card` | For small proof-oriented precision checks |
| `evaluation-profile` | For benchmark/eval suite configuration |
| `evaluation-report` | Auto-generated by eval command |
| `feasibility-study` | For system/platform fit assessments |

## Status Vocabularies

When writing or updating artifacts, use these canonical values:

**Workflow state:** `draft` → `active` → `converged` (or `regressed`)

**Finding status:** `suspected`, `spurious`, `spec-unfaithful`, `retracted`, `reproduced`, `confirmed`, `clean-with-scope`, `expected-window`, `known-deviation`, `assumption-dependent-clean`

**Completeness:** `required`, `present`, `missing`, `deferred`, `waived`

**Concern class:** `safety`, `liveness`, `performance`, `code-level`, `spec-fidelity`

**Verification method:** `model-checkable`, `test-verifiable`, `code-review-only`, `out-of-scope`

## Decision Rules

- **Changed code only, no tests changed:** Report policy-driven evidence reruns, often including `unit-tests` or other required evidence classes
- **Changed formal spec:** Check if differential evidence or model-check evidence needs rerun
- **Mechanism family `trace-conversion`:** Report trace-validation rerun and refresh observable / assumption artifacts
- **Mechanism family `verification-harness`:** Report executable evidence rerun and generated config/wrapper review
- **Mechanism family `specTrace-generation`:** Report generated wrapper refresh and downstream evidence reruns
- **High risk subsystem:** Always generate brief + observable
- **Regressed convergence:** Must loop back — don't mark as done
- **Multiple subsystems affected:** Report each subsystem's obligations separately
- **No profile found:** Offer to create one with `init-profile`
- **Validation errors:** Report exact error type (missing-required, unknown-field, invalid-enum) and field path

## What Bridge Does NOT Do

- Bridge does not modify source code
- Bridge does not implement theorem provers or model checkers itself (it only orchestrates configured external commands)
- Bridge does not generate tests or specs (only identifies what's missing)
- Bridge does not call LLM APIs
- YOU do the actual verification work. Bridge tells you what work is needed and tracks whether it's done.
