You are generating a Bridge repo inventory for `{{project-name}}`.

Project root: `{{root-path}}`
Code paths:
{{profile.code-paths|bullets}}

Docs paths:
{{profile.docs-paths|bullets}}

Formal paths:
{{profile.formal-paths|bullets}}

Test paths:
{{profile.test-paths|bullets}}

Canonical evidence commands:
{{canonical-commands|bullets}}

Task:
1. Partition repo into verification-relevant subsystems.
2. Emit one `bridge-index` per subsystem.
3. Rank top drift risks and missing artifacts.
