# Bridge Artifact Schema Quick Reference

Use `clojure -M:bridge stub-artifact --kind <kind> --out <path>` to generate any of these.

## change-intent-card
```yaml
artifact: change-intent-card
change-id: <string>            # unique ID for this change
change-surface: [<paths>]      # files that changed
change-sources: [code-diff | spec-diff | formal-diff | test-diff | manual-request]
inferred-intent:
  summary: <string>
  categories: [feature | bugfix | refactor | spec-only | code-only | tests-only | mixed | docs-only]
  mechanism-families: [<strings>]
  concern-classes: [safety | liveness | performance | code-level | spec-fidelity]
accepted-intent:
  status: draft | reviewed | accepted
  summary: <string>
semantic-scope:
  contracts: [<strings>]
  subsystems: [<strings>]
risk-class: low | medium | high
workflow-state: draft | active | converged | regressed
missing-obligations: [<strings>]
stale-artifacts: [<paths>]
open-questions: [<strings>]
```

## verification-brief
```yaml
artifact: verification-brief
subject: <string>
category: distributed | actor-message | shared-memory | lock-free | async-runtime | api | business-rule | data-pipeline | legacy | other
summary: [<strings>]
concern-classes: [safety | liveness | performance | code-level | spec-fidelity]
environment-assumptions: [transport | scheduler | memory-model | runtime-delivery | type-system | other]
mechanism-families:
  - name: <string>
    description: <string>
    concern-class: <concern-class>
    verification-method: model-checkable | test-verifiable | code-review-only | out-of-scope
    affected-paths: [<paths>]
    planned-evidence: [<strings>]
co-model-with: [<subsystem-names>]
non-goals: [<strings>]
handoff-outputs: [<paths>]
open-questions: [<strings>]
```

## observable-contract
```yaml
artifact: observable-contract
subject: <string>
observables:
  - name: <string>
    source: {path: <string>, lines: <string>}
    trigger-point: before | after | during | derived
    capture-timing: pre-state | post-state | mixed
    snapshot-strength: weak | strong | derived
    collection-mode: instrumentation | log-parsing | replay | other
    semantics-scope: base | trace | both
    captures: [<strings>]
    maps-to: [<strings>]
    used-by: [<strings>]
constraints: [<strings>]
notes: [<strings>]
```

## completeness-ledger
```yaml
artifact: completeness-ledger
subject: <string>
workflow-state: draft | active | converged | regressed
areas:
  contract: {status: required|present|missing|deferred|waived, artifacts: [<paths>]}
  implementation: {status: ..., artifacts: [...]}
  unit-tests: {status: ..., artifacts: [...]}
  property-tests: {status: ..., artifacts: [...]}
  runtime-assertions: {status: ..., artifacts: [...]}
  formal-spec: {status: ..., artifacts: [...]}
  differential-evidence: {status: ..., artifacts: [...]}
  docs: {status: ..., artifacts: [...]}
notes: [<strings>]
```

## omission-decision-record
```yaml
artifact: omission-decision-record
decision-id: <string>
subject: <string>
omitted-item: <string>
status: deferred | waived | accepted-risk
rationale: [<strings>]
compensating-controls: [<strings>]
owner: <string>
review-trigger: [<strings>]
related-artifacts: [<paths>]
```

## verification-changelog
```yaml
artifact: verification-changelog
subject: <string>
entries:
  - phase: <string>
    kind: fix-spec | fix-inv | fix-trace | fix-config | reclassify | rerun | note
    summary: <string>
    status-effect: none | converged | regressed | retracted
    related-artifacts: [<paths>]
```

## verification-policy
```yaml
artifact: verification-policy
policy-id: <string>
rules:
  - scope:
      subsystems: [<names>]          # optional
      change-categories: [<cats>]    # optional
      system-categories: [<cats>]    # optional
      risk-classes: [low|medium|high] # optional
      concern-classes: [<classes>]   # optional
    required-evidence:
      unit-tests: required | recommended | optional | forbidden
      property-tests: ...
      runtime-assertions: ...
      docs-or-nl-spec: ...
      formal-spec: ...
      differential-tests: ...
      trace-validation: ...
      benchmarks: ...
      confirmation-evidence: ...
```

For full schema details: `resources/bridge/schemas.edn` in the Bridge repo.
