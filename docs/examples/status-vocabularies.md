# Bridge status and category vocabularies

This file gives concrete example usage for current vocabularies.

## Workflow states

- `draft` — change intake exists, obligations not yet accepted
- `active` — evidence or artifact updates still in flight
- `converged` — current evidence set agrees within declared scope
- `regressed` — later evidence invalidated earlier conclusion

## Finding/result labels

- `suspected` — issue not yet confirmed outside weak evidence
- `spurious` — counterexample from overapproximation or unrealistic reduction
- `spec-unfaithful` — mismatch between model/trace/spec and implementation surface
- `retracted` — previously claimed issue withdrawn
- `reproduced` — issue triggered outside model-only evidence
- `confirmed` — issue confirmed and actionable
- `clean-with-scope` — no issue within declared abstraction boundary
- `expected-window` — transient allowed window, not defect
- `known-deviation` — accepted divergence from paper/reference design
- `assumption-dependent-clean` — clean only under explicit environment assumptions

## Completeness statuses

- `required` — policy says artifact/evidence class is required
- `present` — artifact/evidence exists now
- `missing` — required and absent
- `deferred` — intentionally postponed, needs omission record
- `waived` — intentionally not required for current scope

## System categories

- `actor-message` — per-actor sequential logic, message ordering primary
- `lock-free` — atomic/CAS logic, memory-model primary
- `async-runtime` — waiter/waker or scheduler interactions primary
- `business-rule` — contract/spec precision primary

See also:
- `../schema-reference.md`
- `../case-study-learnings.md`
