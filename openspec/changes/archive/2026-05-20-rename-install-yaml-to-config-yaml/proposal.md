## Why

`install.yaml` is a misnomer — the file defines a workload's full lifecycle (start, stop, uninstall, backup, restore), runtime topology, metrics, endpoints, dashboards, and CLI args, not just installation. Renaming it to `config.yaml` makes the filename accurately describe its contents and removes a confusing implication that it only controls the install phase.

## What Changes

- `install.yaml` → `config.yaml` in all classpath template directories (clickhouse, presto, etc.)
- All code that reads or references the filename by string literal (`"install.yaml"`) is updated
- Spec documentation updated to use the new filename
- User-facing docs updated

## Capabilities

### New Capabilities
- None

### Modified Capabilities
- `install-command`: The descriptor filename changes from `install.yaml` to `config.yaml`. The spec's "Template Structure" and "install.yaml Descriptor" sections must be updated. No behavior changes.

## Impact

- `WorkloadRunnerCommandFactory.loadInstallConfig()` — reads `install.yaml` by name
- `InstallTemplateResolver` — any hardcoded reference to `install.yaml`
- Classpath resources under `src/main/resources/.../install/*/install.yaml`
- `openspec/specs/install-command/spec.md`
- User documentation in `docs/`
- Tests that reference `install.yaml` by name
