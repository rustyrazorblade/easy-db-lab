## Context

The concept of "a deployable thing you install onto a cluster" is currently called a "workload" throughout the codebase, specs, CLI output, and documentation. The term is generic infrastructure jargon. Easy-db-lab uses a lab metaphor (clusters are labs, experiments run on them), and "kit" fits this theme better: a lab kit is a packaged set of materials you set up to run an experiment.

This is a cross-cutting rename touching: Kotlin type names, resource file names, spec folder names, CLI help text, event field names, template variable names, and documentation.

## Goals / Non-Goals

**Goals:**
- Replace "workload" with "kit" everywhere it refers to the concept (a deployable package installed onto a cluster)
- Rename `config.yaml` → `kit.yaml` as the kit descriptor filename
- Rename `WorkloadInstallConfig` and all related Kotlin types to `Kit*` equivalents
- Rename `workload-*` spec folders to `kit-*`
- Update all CLI output, help text, and error messages

**Non-Goals:**
- No behavioral changes — this is a pure rename
- No changes to the `install` command name or other command names
- No changes to step types, lifecycle phases, or the template variable contract (except `__WORKLOAD_NAME__` → `__KIT_NAME__`)
- No backwards compatibility shim for `config.yaml` — clusters are ephemeral, no migration path needed

## Decisions

### Decision 1: `kit.yaml` as the descriptor filename

**Choice:** The kit descriptor file is renamed from `config.yaml` to `kit.yaml`.

**Rationale:** `config.yaml` is too generic — it could be any configuration file. `kit.yaml` is distinctive and immediately signals "this is an easy-db-lab kit descriptor." It is analogous to `Chart.yaml` in Helm or `pyproject.toml` in Python.

**Alternative considered:** Keep `config.yaml`. Rejected because it is ambiguous next to other config files users may place in the directory.

### Decision 2: No fallback to `config.yaml`

**Choice:** The CLI loader looks only for `kit.yaml`. If a template directory contains `config.yaml` but not `kit.yaml`, it is not loaded.

**Rationale:** Clusters are ephemeral — there are no user templates in production that need a migration path. Fail fast is preferred.

**Alternative considered:** Load `config.yaml` as a fallback with a deprecation warning. Rejected because it adds dead code and complicates the loader.

### Decision 3: `__KIT_NAME__` replaces `__WORKLOAD_NAME__`

**Choice:** The template variable that holds the kit name is renamed from `__WORKLOAD_NAME__` to `__KIT_NAME__`.

**Rationale:** Consistency — if the concept is a "kit", all variables and events that reference it should say "kit".

**Alternative considered:** Keep `__WORKLOAD_NAME__` for compatibility with existing user templates. Rejected for same reason as Decision 2 — no long-lived templates to protect.

### Decision 4: Kotlin type naming

**Choice:** All `Workload*` types are renamed to `Kit*`:

| Old | New |
|---|---|
| `WorkloadInstallConfig` | `KitConfig` |
| `WorkloadMetrics` | `KitMetrics` |
| `WorkloadRuntime` | `KitRuntime` |
| `WorkloadEndpoint` | `KitEndpoint` |
| `WorkloadArgSpec` | `KitArgSpec` |
| `WorkloadHooks` | `KitHooks` |
| `WorkloadHook` | `KitHook` |
| `WorkloadHookExecutor` | `KitHookExecutor` |

`InstallStep` stays as-is — steps describe phases within a kit, not kits themselves.

### Decision 5: Spec folder renames

**Choice:** Spec directories named `workload-*` are renamed:

| Old | New |
|---|---|
| `workload-lifecycle-hooks` | `kit-lifecycle-hooks` |
| `workload-metrics-declaration` | `kit-metrics-declaration` |
| `workload-running-state` | `kit-running-state` |
| `workload-uninstall-command` | `kit-uninstall-command` |
| `uninstalled-workload-hint` | `uninstalled-kit-hint` |

**Rationale:** Spec names should match the vocabulary used everywhere else.

## Risks / Trade-offs

**Built-in kit templates need renaming** → All classpath template directories containing `config.yaml` must rename the file to `kit.yaml`. This is a one-time change that touches `clickhouse`, `presto`, and any other built-in kits. Low risk — the rename is mechanical.

**Event field names** → Events like `Event.Workload.ScriptStarted(workload, script)` have a field named `workload`. These should be renamed to `kit` for consistency. This touches the event sealed class hierarchy and all consumers. Low behavioral risk; moderate rename surface.

**Test fixtures** → Test YAML fixtures using `config.yaml` as the descriptor filename must be updated. Low risk — tests should fail clearly when the filename is wrong.

## Open Questions

- Should `RUNNING_WORKLOADS` (the env var injected into scripts) be renamed to `RUNNING_KITS`? This is a user-visible env var that appears in user-authored scripts. Same ephemeral-cluster argument applies, but worth confirming.
