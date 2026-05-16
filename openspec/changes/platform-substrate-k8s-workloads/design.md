## Context

Easy-db-lab currently requires a bespoke Kotlin manifest builder for every new K8s workload. `ClickHouseManifestBuilder` (~670 lines) builds all K8s resources directly in Kotlin via Fabric8. This model cannot support private or proprietary databases. The platform-substrate design introduces a two-layer contract: easy-db-lab owns K8s primitives (StorageClass, PVs, node labels); workloads own their own deployment (helm charts, operators, CRDs).

**Key design principles settled during exploration:**
- `state.json` is an AWS infra cache (EC2 IDs, IPs, VPC), not a workload registry. No workload config blocks are added to `ClusterState` for new workloads.
- K8s cluster is always the source of truth for runtime state.
- The CLI is the API: generated `start.sh` scripts call `easy-db-lab platform create-pvs` as a subprocess rather than sharing internal Kotlin code.
- `install` writes scaffold artifacts; the user (or orchestration tool) runs them.

## Goals / Non-Goals

**Goals:**
- Enable deployment of any K8s workload (public or private) without modifying easy-db-lab source.
- Provide `platform create-pvs` as a standalone, composable CLI primitive.
- Provide `install` as a scaffold generator: renders templates, writes `start.sh`/`stop.sh`/`values.yaml`/`README.md`.
- Support profile-directory templates: templates placed at `~/.easy-db-lab/profiles/<profile>/install/<name>/` are automatically discoverable and installable as `install <name>`.
- Support `install --from <path>` for ad-hoc one-off templates (not listed in `install --list`).
- Ship `helm` and `kubectl` in the container so generated scripts are self-contained.
- Add `local-storage-wfc` StorageClass (WaitForFirstConsumer + Delete) at `up` time.
- Extend node ordinal labeling to app nodes.
- Add `cleanup <workload>` for opt-in disk wipe.

**Non-Goals:**
- Migrating existing `clickhouse start/stop/status/backup/restore` commands. They remain unchanged.
- Storing workload runtime state in `ClusterState`.
- easy-db-lab running helm/kubectl directly (the JVM process never shells out to K8s tooling).
- Automatic workload installation at `up` time.

## Decisions

### Decision 1: Three-tier template discovery

**Choice:** The `install` command resolves templates from three sources in priority order:
1. **Built-in** — classpath resources at `resources/install/<name>/` (ships with the tool)
2. **Profile directory** — `~/.easy-db-lab/profiles/<profile>/install/<name>/` (user's permanent collection, auto-discovered)
3. **Ad-hoc** — `--from <path>` (one-off path, not surfaced in `install --list`)

`install --list` shows built-in and profile-directory templates. `--from` is intentionally excluded — it's for one-off use, not a managed collection. If both a built-in and a profile template share the same name, the profile directory wins (user override).

**Rationale:** Users need to ship private workload templates without forking the tool or maintaining a separate repository of `--from` paths. The profile directory (`~/.easy-db-lab/profiles/<profile>/`) already exists as the user's local configuration home. Adding an `install/` subdirectory there is a natural extension — place a template directory there and it immediately works, no registration required.

**Alternative considered:** A registry file (`~/.easy-db-lab/profiles/<profile>/install-registry.yaml`) that maps names to template paths. Rejected because filesystem presence is simpler and less error-prone than a separate config file. Convention over configuration.

### Decision 2: CLI as the composition API (generated scripts)

**Choice:** Generated `start.sh` calls `easy-db-lab platform create-pvs` as a subprocess rather than inlining PV-creation logic or sharing a Kotlin library.

**Rationale:** Custom `--from` templates need the same PV creation capability as built-in templates. Sharing via internal API would require custom templates to depend on easy-db-lab's Kotlin internals. Making `platform create-pvs` a first-class CLI command gives custom templates the same surface as built-in ones, with no internal coupling.

**Alternative considered:** Internal `PlatformService.createPvs()` called by both `install` and a thin `platform create-pvs` wrapper. Rejected because it creates two code paths that must stay in sync, and custom templates can't call internal APIs anyway.

### Decision 3: No workload config in ClusterState

**Choice:** New workloads do not add config blocks to `ClusterState`. The generated files and K8s cluster are the record.

**Rationale:** `state.json` was designed to cache immutable AWS infrastructure. Making it a workload registry would cause state to drift whenever the user runs `stop.sh` or `kubectl delete` directly. K8s is always queryable; state.json would be a stale copy.

**Alternative considered:** `ClusterState.installConfigs: Map<String, WorkloadConfig>` keyed by workload name. Rejected because it cannot stay in sync with out-of-band K8s operations (helm/kubectl run by the user).

**Note:** The existing `clickHouseConfig` in `ClusterState` remains — it supports legacy `clickhouse start` and is out of scope for this change.

### Decision 4: WaitForFirstConsumer binding mode for new StorageClass

**Choice:** `local-storage-wfc` uses `WaitForFirstConsumer` (WFC). The existing `local-storage` uses `Immediate` and is unchanged.

**Rationale:** Operator-managed StatefulSets schedule pods first then expect PVCs to bind to the node the pod landed on. WFC defers PV binding until a Pod's scheduling decision is made, ensuring the PV binds to the correct node. `Delete` reclaim policy (vs. existing `Retain`) prevents orphaned PVs when an operator deletes PVCs during uninstall.

**Alternative considered:** Reuse `local-storage` for new workloads. Rejected because `Immediate` binding mode pre-binds PVs before pod scheduling, which can cause pods to be unschedulable if the wrong node is chosen. The pre-bound-by-ordinal pattern used by legacy ClickHouse is incompatible with operator-managed PVC lifecycle.

### Decision 5: Template variable contract as public API

**Choice:** Template variables (e.g., `__BUCKET_NAME__`, `__REGION__`, `__DB_NODE_COUNT__`, `__APP_NODE_COUNT__`, `__STORAGE_CLASS_WFC__`) are a documented, stable contract available to both built-in and `--from` custom templates.

**Rationale:** Custom templates need a predictable set of substitution variables to be useful across different clusters. Once documented, these variables cannot be removed without a deprecation cycle.

**Approach:** Variables sourced from `ClusterState` (AWS infra) plus command-line flags. A `TemplateVariables` data class collects all available variables and is passed to `TemplateService` for both built-in and custom template rendering.

### Decision 6: Collision detection queries K8s

**Choice:** `install clickhouse` checks for an existing `ClickHouseInstallation` CR in K8s before rendering templates.

**Rationale:** ClusterState cannot reliably indicate whether a workload is running (see Decision 2). A user might have deployed ClickHouse via the Altinity operator manually, or `start.sh` might have been run without going through easy-db-lab. K8s is the ground truth.

### Decision 7: Container ships helm and kubectl

**Choice:** Jib build config adds `helm` and `kubectl` as additional binary layers in the container image.

**Rationale:** Generated scripts call `helm install` and `kubectl apply`. For the container to be self-contained (no external tooling prerequisites), these binaries must be in the image. Jib's `extraDirectories` or a custom base image with the binaries pre-installed are the implementation options.

## Risks / Trade-offs

**`easy-db-lab` must be on PATH when scripts run** → Mitigation: README template documents this requirement. Container entrypoint already has the binary available.

**WFC StorageClass + pre-created PVs may conflict** → `local-storage-wfc` PVs are created by `platform create-pvs` and matched to the workload's StatefulSet PVC template name. If an operator uses a different PVC template name, PVs won't bind. Mitigation: document the PVC template name convention; built-in templates use the workload name as the PVC template name.

**Custom template variable contract is hard to extend without breaking** → Variables added later won't render in old templates (they'll remain as literal `__VAR__` strings). Mitigation: make unresolved variables a warning, not an error; document the full variable set clearly.

**Container image size grows** → helm (~50MB) and kubectl (~50MB) add ~100MB to the image. Acceptable for a lab tool; document in release notes.

## Migration Plan

- No migration required. Clusters are ephemeral.
- Existing `clickhouse` commands are unaffected.
- `local-storage` StorageClass continues to exist alongside `local-storage-wfc`.
- The new `up` behaviour (adding WFC StorageClass + app node labeling) applies to newly provisioned clusters only.

## Open Questions

- Should `install --list` show only built-in workloads or also scan for local `--from` template directories previously used?
- Exact set of template variables to expose in the initial contract (to be finalised during implementation).
- Base image approach for bundling helm/kubectl: custom base image vs. Jib `extraDirectories`.
