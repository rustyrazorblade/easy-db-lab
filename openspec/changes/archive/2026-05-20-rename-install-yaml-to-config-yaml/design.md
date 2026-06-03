## Context

Every workload template directory contains an `install.yaml` file that is the single descriptor for the workload's full behaviour: CLI args, lifecycle phases (install/start/stop/uninstall/backup/restore), runtime topology, metrics, endpoints, dashboards, and hooks. The filename `install.yaml` was chosen early when the file only controlled scaffold installation; it now covers far more than installation.

References are spread across:
- Two classpath resource files (`install/clickhouse/install.yaml`, `install/presto/install.yaml`)
- A constant `"install.yaml"` string referenced in ~6 Kotlin source files
- Test files that write `install.yaml` to temp directories

## Goals / Non-Goals

**Goals:**
- Rename `install.yaml` → `config.yaml` in all classpath template directories
- Update every hardcoded `"install.yaml"` string in production Kotlin code to `"config.yaml"`
- Update tests that create or reference `install.yaml` by name
- Add a constant in `Constants.kt` so the filename is defined in one place

**Non-Goals:**
- No behaviour changes — this is a pure rename
- No migration support for existing installed workload directories on disk (clusters are ephemeral; no user data to migrate)
- No changes to the YAML schema itself

## Decisions

**Single constant, not scattered strings**: Introduce `Constants.Workload.CONFIG_FILE = "config.yaml"` and replace every hardcoded `"install.yaml"` string with it. This ensures a future rename is a one-line change.

**No backwards compatibility shim**: The clusters are ephemeral by design. Any `install.yaml` on disk at runtime belongs to a cluster that was already provisioned — it will be torn down and reprovisioned. No fallback reading of the old filename is needed.

## Risks / Trade-offs

**User profile directories**: Power users may have custom templates in `~/.easy-db-lab/profiles/<profile>/install/<name>/install.yaml`. After this change, those templates will be ignored silently because the loader looks for `config.yaml`. This is acceptable — profiles are a developer feature and easy to update — but should be mentioned in the user docs.

## Migration Plan

1. Add `CONFIG_FILE = "config.yaml"` constant to `Constants.kt`
2. Rename the two classpath resource files
3. Replace all `"install.yaml"` string literals in production code with the constant
4. Update tests
5. Update `openspec/specs/install-command/spec.md`
6. Update `docs/` user documentation
