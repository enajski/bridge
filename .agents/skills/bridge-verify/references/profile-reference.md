# Bridge Profile Quick Reference

## Minimal profile

```edn
{:kind "project-profile"
 :project-name "my-project"
 :root-path "."
 :code-paths ["src"]
 :docs-paths ["docs"]
 :formal-paths ["specs"]
 :test-paths ["test"]
 :artifact-paths {:root "artifacts"
                  :phases "artifacts/phases"
                  :evidence "artifacts/evidence"
                  :evaluations "artifacts/evaluations"}
 :canonical-commands [{:id "unit" :kind "unit" :command "make test"}]
 :subsystems [{:name "core"
               :code-globs ["src/**/*.clj"]
               :docs-globs ["docs/**/*.md"]
               :formal-globs []
               :test-globs ["test/**/*.clj"]
               :expected-artifacts ["change-intent-card" "verification-brief"]}]
 :phases []}
```

## Key optional fields

```edn
{;; Link to verification policy
 :verification-policy-path "path/to/verification-policy.yaml"

 ;; File-glob rules for mechanism-family matching
 :file-glob-rules [{:glob "src/engine/**/*.clj"
                    :subsystem "engine"
                    :mechanism-family "execution-safety"
                    :concern-class "safety"
                    :contract "execution order stable"
                    :formal-module "specs/engine.tla"
                    :test-path "test/engine_test.clj"
                    :risk-note "Concurrency changes need trace revalidation."}]

 ;; Subsystem optional fields
 :subsystems [{:name "engine"
               ;; ... required fields ...
               :system-category "distributed"  ; or actor-message, lock-free, async-runtime, api, business-rule
               :risk-class "high"              ; high, medium, low
               :expected-evidence ["unit" "model-check" "trace-validation"]
               :coupled-with ["trace-runtime"]
               :environment-assumptions ["scheduler" "transport"]}]

 ;; Phase definitions
 :phases [{:id :analyze :action "analyze-change"}
          {:id :brief :action "generate-brief"}
          {:id :observe :action "generate-observable"}
          {:id :prompt :action "render-prompt" :template "diff-intent-obligation-analysis"}
          {:id :evidence :action "run-evidence" :evidence-id "unit"}]

 ;; Coupled subsystem declarations
 :coupled-subsystems [{:subject "engine" :with ["trace-runtime"] :reason "Shared observables."}]

 ;; Environment assumptions
 :environment-assumptions ["Scheduler is cooperative." "Transport is FIFO."]}
```

## Evidence command kinds

`unit`, `property`, `runtime`, `differential`, `trace-validation`, `benchmark`, `proof`, `model-check`, `symbolic`, `custom`

## Evidence command roles

`conformance`, `exploration`, `confirmation`, `regression`
