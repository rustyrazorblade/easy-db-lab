## Why

The current workload install system generates shell scripts (`start.sh`, `stop.sh`) that are
opaque, difficult to validate, and require the user to know bash internals to extend. This makes
it hard to add new workloads reliably, impossible for an AI skill to generate them safely, and
provides no structured way to express common patterns (Helm installs, K8s waits, namespace
creation). Typed step sequences replace ad-hoc shell with a declarative, validated, AI-writable
format while preserving a `Shell` escape hatch for complex cases.

## What Changes

- **`install.yaml` gains lifecycle phases** (`install`, `start`, `stop`, `uninstall`), each a
  sequence of typed steps replacing the current shell script templates
- **New typed step types**: `HelmRepo`, `Helm`, `HelmUninstall`, `Namespace`, `Manifest`,
  `ManifestUrl`, `Kustomize`, `Wait`, `Delete`, `PlatformPvs`, `ConfigMap`, `Label`, `Exec`,
  `Shell` (escape hatch)
- **`install.yaml` gains** a top-level `version` field and a `dashboards` list (replacing the
  hardcoded post-`start` scan in `WorkloadRunnerCommand`)
- **Step executors** replace `ProcessBuilder` shell invocations — steps call K8s and Helm services
  directly through the service layer
- **`${VAR}` interpolation** in step fields using `TemplateVariables.toMap()` (consistent with
  existing `default` interpolation)
- **Clickhouse and presto** migrated from generated shell scripts to typed steps
- **New `generate-workload` Claude skill** that researches helm charts, proposes a full
  `install.yaml` with typed steps, refines interactively, and generates all supporting files
- **E2E validation story**: the skill emits a checklist item to add a `--<workload>` step to
  `bin/end-to-end-test` for real-cluster validation

## Capabilities

### New Capabilities

- `typed-install-steps`: The `InstallStep` sealed interface, YAML schema for lifecycle phases,
  step executor service, and per-phase collision/guard semantics
- `generate-workload-skill`: Claude skill in `.claude/skills/` that researches, proposes, and
  generates new workload configs interactively

### Modified Capabilities

- `install-command`: The `install.yaml` schema expands to include lifecycle phases, `version`,
  `dashboards`, and per-phase `collisionCheck`; `WorkloadRunnerCommand` is updated to use the
  step executor instead of `ProcessBuilder`

## Impact

- `install.yaml` schema (breaking for any hand-written `install.yaml` files outside the repo)
- New `InstallStep` sealed interface and `WorkloadStepExecutor` service
- `WorkloadInstallCommandFactory` — reads new lifecycle fields
- `WorkloadRunnerCommand` — delegates to `WorkloadStepExecutor` instead of `ProcessBuilder`
- Clickhouse and presto template directories restructured (shell script templates removed)
- New Claude skill file at `.claude/skills/generate-workload/`
- `openspec/specs/install-command/spec.md` updated
- `docs/user-guide/` updated for new `install.yaml` format
