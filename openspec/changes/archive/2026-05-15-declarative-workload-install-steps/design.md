## Context

The current workload install system (`install.yaml` + rendered shell scripts) works for the two
existing workloads (clickhouse, presto) but has structural limits:

- Shell scripts are opaque — the CLI cannot inspect what a workload will do before running it
- K8s operations (helm, kubectl) are embedded in shell, bypassing the service layer and losing
  error propagation, retry logic, and event emission
- The `ProcessBuilder` in `WorkloadRunnerCommand` passes cluster state as env vars and execs the
  script; there is no structured result — only an exit code
- Adding a new workload requires writing correct shell with proper error handling, quoting, and
  idempotency logic — a high bar for an AI skill generating the config
- Dashboard installation is hardcoded: `if (script == "start" && exitCode == 0)` scans a
  directory; there is no way to declare which dashboards a workload needs in `install.yaml`

The step executor model keeps the CLI as the orchestrator and pushes K8s operations through the
existing service layer (`K8sService`, `K8sNamespaceOperations`, Helm via SSH).

## Goals / Non-Goals

**Goals:**

- Replace shell script templates with typed, validated step sequences declared in `install.yaml`
- Expose a clean lifecycle: `install`, `start`, `stop`, `uninstall` — each independently executable
- Route all K8s operations through existing service layer (no subprocess kubectl)
- Make `install.yaml` machine-writable: valid YAML, validated schema, no bash quoting footguns
- Preserve the `Shell` step as an explicit escape hatch
- Migrate clickhouse and presto to typed steps
- Add a `generate-workload` Claude skill that produces valid `install.yaml` interactively

**Non-Goals:**

- Removing the `Shell` step escape hatch — it is intentional and permanent
- Supporting Flux/ArgoCD/OLM — wrong primitives for ephemeral clusters
- Workload dependency graphs (e.g., "install cert-manager before ScyllaDB") — out of scope for v1;
  document as a manual prerequisite
- GUI or web-based workload management

## Decisions

### 1. Sealed interface `InstallStep` with kotlinx.serialization polymorphism

Each step type is a Kotlin data class implementing `sealed interface InstallStep`. Serialization
uses kaml's polymorphic support with a `type` discriminator field. This gives:
- Compile-time exhaustive `when` in the executor
- Schema validation at parse time (unknown `type` values fail fast)
- Clean addition of new step types without changing the executor dispatch

**Alternative considered**: a single `Step` data class with nullable fields per step type.
Rejected: fields are untyped and wrong combinations are silently accepted.

**`values` in `Helm` step**: use `YamlMap` (kaml's `YamlNode` subtypes) rather than
`Map<String, Any>`. kotlinx.serialization cannot deserialize `Map<String, Any>` from nested YAML
without a custom serializer. `YamlMap` is already on the classpath (kaml dependency) and
round-trips cleanly. When passing to helm, serialize back to a temp values file.

### 2. Four lifecycle phases: `install`, `start`, `stop`, `uninstall`

| Phase | Semantics | Idempotent? |
|---|---|---|
| `install` | One-time setup: operator, CRDs, namespaces, storage classes | Yes — safe to re-run |
| `start` | Create workload instance (CR, StatefulSet); creates data-bearing resources | No — guarded by collision check |
| `stop` | Delete workload instance, preserve PVs and data | Yes — `Delete` steps use `ignoreNotFound: true` |
| `uninstall` | Remove everything `install` set up; destructive | Yes |

Shell scripts remain for workloads that pre-date this change only as a transitional path. The
`WorkloadRunnerCommand` tries the step executor first; if no matching phase is declared in
`install.yaml`, it falls back to the script in `bin/`.

### 3. `${VAR}` interpolation in step fields via `TemplateVariables.toMap()`

Step field values (e.g., `name: "${CLUSTER_NAME}-scylladb"`) are interpolated using the same
`TemplateVariables.toMap()` that already populates template variables. This reuses existing logic,
keeps one substitution mechanism, and makes step fields consistent with `install.yaml` defaults.
Unresolved `${VAR}` references emit a warning but do not fail.

### 4. Helm operations via SSH to control node (not local helm binary)

The existing shell scripts run `helm` on the local machine (which must have helm installed). The
`Helm` step executor SSHes to the control node via `RemoteOperationsService` and runs helm there.
The control node always has kubectl and helm available. This eliminates the local helm binary
requirement and matches how all other cluster operations work.

**Alternative considered**: bundling/downloading helm binary. Rejected: adds binary management
complexity and diverges from the established SSH pattern.

### 5. `WorkloadRunnerCommand` drives phases, not individual scripts

Instead of `easy-db-lab clickhouse start` running `bin/start.sh`, the command dispatches to the
`WorkloadStepExecutor` with phase=`start`. This changes the external interface only for workloads
that have been migrated — unmigrated workloads continue to use the script fallback.

### 6. `dashboards` declared in `install.yaml`, not discovered by convention

```yaml
dashboards:
  - path: dashboards/clickhouse-overview.json
    name: ClickHouse Overview
```

Replaces the hardcoded `if (script == "start")` scan. The step executor installs declared
dashboards after the `start` phase completes successfully. This makes dashboard installation
explicit and survives the removal of `bin/start.sh`.

### 7. Collision check becomes per-phase

`collisionCheck: true` at the top level remains valid (defaults to guarding `start`). Per-phase
override:
```yaml
collisionCheck:
  install: false   # always idempotent
  start: true      # default: check before creating CR
```

### 8. `generate-workload` skill uses the opsx:propose pattern

The skill researches the workload (helm chart, CRDs, readiness conditions), proposes a draft
`install.yaml`, refines interactively, then generates the file. It follows the same
propose→refine→generate loop already established by `opsx:propose`. Output includes:
- `src/main/resources/.../install/<name>/install.yaml`
- Any `.template` files needed for `Manifest` steps
- A checklist item for adding a `--<workload>` validation step to `bin/end-to-end-test`

## Risks / Trade-offs

**SSH round-trip latency for each Helm step** → Acceptable: helm installs are slow (seconds to
minutes); SSH overhead is negligible.

**kaml `YamlMap` is an internal kaml type** → It is stable and already used in the codebase for
YAML parsing. If kaml changes its API, the `Helm` step serializer is the only affected site.

**Fallback to shell scripts creates two code paths** → The fallback is explicitly transitional.
Clickhouse and presto are migrated in this change; the fallback exists only to avoid breaking
community-contributed workloads. Remove fallback in a follow-up once the ecosystem stabilizes.

**`Shell` step can undermine the declarative model** → Accepted trade-off. The `Shell` step is
an explicit opt-out, not a footgun. Users (and the skill) should prefer typed steps; shell is the
documented last resort.

**Real-cluster E2E validation is manual** → The `generate-workload` skill outputs a checklist
item but cannot run `bin/end-to-end-test` automatically (requires AWS credentials and a live
cluster). This is correct — the user owns the validation step.

## Migration Plan

1. Implement `InstallStep` sealed interface and kaml serialization
2. Implement `WorkloadStepExecutor` service with all step types
3. Update `WorkloadRunnerCommand` to try step executor first, fall back to script
4. Update `install.yaml` parser to read lifecycle phases
5. Migrate clickhouse `install.yaml` and remove `bin/*.sh.template`
6. Migrate presto `install.yaml` and remove `bin/*.sh.template`
7. Write `generate-workload` Claude skill
8. Update `openspec/specs/install-command/spec.md`
9. Update user docs

No rollback needed — clusters are ephemeral. Any workload directory generated before this change
uses the script fallback path automatically.

## Open Questions

- Should `HelmValues` (a rendered values file as a step) be included in v1 or deferred? Presto
  currently uses a `values.yaml.template` — migrating it cleanly may require this step type.
- Should `Kustomize` and `ManifestUrl` be v1 step types, or deferred until a concrete workload
  needs them? Including them costs little (small data classes) but adds executor surface.
