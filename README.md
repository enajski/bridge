# Bridge

Bridge is a command-line tool that coordinates codebase verification. It tracks evidence obligations across your code, specifications, and tests, keeping them in sync as your system changes.

Instead of running verification checks directly, Bridge acts as a coordination layer. It maps changed files to project subsystems, checks which tests or validations are required by your policy, and helps you execute those commands and track the results.

---

## Installation & Setup

Bridge is configured as a local task in your project using [Babashka](https://babashka.org/).

### 1. Install Babashka (If not already installed)

- **macOS**:
  ```bash
  brew install borkdude/brew/babashka
  ```
- **Linux & Windows**:
  ```bash
  bash < <(curl -s https://raw.githubusercontent.com/babashka/babashka/master/install)
  ```

### 2. Configure Bridge in `bb.edn`

Add Bridge as a git dependency and register its task in your project's root `bb.edn`:

```edn
{:deps {io.github.org/bridge {:git/url "https://github.com/org/bridge"
                              :git/sha "COMMIT_SHA_HERE"}}
 :tasks
 {bridge {:doc "Runs the Bridge verification workflow CLI"
          :requires ([bridge.cli :as cli])
          :task (apply cli/-main *command-line-args*)}}}
```

### 3. Initialize the Configuration

Run the initializer from your repository root:

```bash
bb bridge init
```

This creates:
- `.bridge/profile.edn` (project profile defining your subsystems)
- `.bridge/verification-policy.yaml` (rules outlining what evidence is required)
- `.bridge/ephemeral/` (directory for run receipts and local analysis cards)

### 4. Install Git Hooks (Optional)

To check verification status automatically before pushing code:

```bash
bb bridge install-hooks
```

---

## Core Concepts & Gradual Workflow

Bridge is designed for gradual adoption. You do not need to configure everything at once; you can start by tracking simple tests and add more verification checks as needed.

### Step 1: Define Your Profile & Policy

The workflow relies on two core configuration files:
- **Project Profile (`profile.edn`)**: Maps directory structures and file paths to project subsystems, and registers the shell commands (e.g. unit tests, integration tests) used to verify them.
- **Verification Policy (`verification-policy.yaml`)**: Defines what evidence is required for changes to specific subsystems before they are considered ready to merge, and can declare sandbox access for Bridge-owned paths.

### Step 2: Check Status and Find Next Steps

When files change, Bridge uses your profile and policy to evaluate what needs to be verified.

- **Check Status**:
  ```bash
  bb bridge check
  ```
  This command analyzes changed files, maps them to their subsystems, and outputs which evidence obligations are missing or out of date.

- **Find Next Step**:
  ```bash
  bb bridge next
  ```
  This inspects outstanding obligations and suggests the immediate next action to take (e.g., pointing you to the exact test command to run).

### Step 3: Run Evidence Commands

To view and run verification commands registered in your profile:

```bash
# List all registered commands
bb bridge list-evidence

# Run a specific command (e.g., unit tests)
bb bridge run-evidence --id unit
```

Executing a command via `run-evidence` generates an `evidence-run` receipt in `.bridge/ephemeral/`. When you run `bb bridge check` again, Bridge detects this receipt and marks the obligation as satisfied.

### Step 4: Gradual Expansion

As your workflow matures, you can opt into tracking additional durable verification artifacts:

1. **Verification Brief (`verification-brief`)**: Plans the scope, verification strategy, and environment assumptions for a particular subsystem or major change.
2. **Observable Contract (`observable-contract`)**: Specifies assertions, triggers, and trace parameters to collect during execution.
3. **Omission Decision Record (`omission-decision-record`)**: Formally documents why a particular policy requirement was skipped or deferred.
4. **Assumption Ledger (`assumption-ledger`)**: Records platform or environment assumptions that your verification depends upon.

---

## CLI Command Reference

Common commands for everyday development:

```bash
# Initialize configuration files
bb bridge init

# Verify current status and list missing evidence
bb bridge check

# Evaluate the last committed patch instead of the live working tree
bb bridge check --git-diff HEAD^1

# Show the recommended next verification step
bb bridge next

# List registered verification commands
bb bridge list-evidence

# Run an evidence command with a dry run (shows the shell command without executing)
bb bridge run-evidence --id unit --dry-run

# Run an evidence command and record the receipt
bb bridge run-evidence --id unit

# Validate the schema of an individual artifact file
bb bridge validate-artifact .bridge/artifacts/verification-brief.yaml

# Validate all artifacts in a directory
bb bridge validate-dir .bridge/artifacts
```

---

## Key Artifacts

Bridge uses schema-validated data files (typically YAML or EDN format) to manage the verification state:

- **`verification-policy`**: Declares evidence requirements and Bridge-owned path sandbox rules.
- **`verification-brief`**: Records verification scope, methods, and assumptions.
- **`observable-contract`**: Details runtime monitoring, triggers, and timing.
- **`omission-decision-record`**: Explains why a required test or check was waived.
- **`assumption-ledger`**: Captures external dependencies and environment assumptions.
- **`completeness-ledger`**: Summarizes which required artifacts are present or missing.
- **`change-intent-card`**: An ephemeral card capturing a change and its derived obligations.

For a full list and schema reference, see [docs/internal/artifacts.md](file:///Users/wojtekfranke/workspace/bridge/docs/internal/artifacts.md).

---

## Subsystem Categories

Different types of subsystems require different verification approaches. You can define categories in your profile to customize verification behavior:

- `actor-message`: Emphasizes message ordering and trace observables.
- `shared-memory` / `lock-free`: Emphasizes memory models, atomicity, and concurrency behavior.
- `async-runtime`: Emphasizes notification hooks, parks, and wake targets.
- `business-rule` / `data-pipeline`: Emphasizes specification quality, completeness checks, and differential outputs.

---

## Repo Layout

- `src/bridge/` — CLI and workflow engine implementation.
- `resources/bridge/schemas.edn` — Canonical artifact schemas.
- `resources/bridge/templates/` — Prompts and template definitions.
- `examples/` — Sample profiles and policy setups (e.g., `examples/generic/`).
- `docs/` — Conceptual, architectural, and internal draft documentation.
- `test/` — Core automated test suite.

---

## Examples

- **Generic Example**: See [examples/generic/](file:///Users/wojtekfranke/workspace/bridge/examples/generic/) for a simple profile and verification brief.
- **Experimental Features**: See [docs/internal/formal-draft.md](file:///Users/wojtekfranke/workspace/bridge/docs/internal/formal-draft.md) for details on draft support for formal verification, feasibility studies, and evaluation adapters.
