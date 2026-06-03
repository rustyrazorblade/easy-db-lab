## Context

Grafana is deployed as a K8s Deployment on the control node by `grafana update-config`. Its data volume is currently `emptyDir`, meaning Grafana's SQLite database (dashboards, preferences, sessions) is lost every time the pod restarts — including after every `grafana update-config` run.

Workload install templates (`install clickhouse`, `install presto`, `install --from`) generate a `start.sh` that users run to deploy their workload. There is currently no mechanism for `start.sh` to register workload-specific Grafana dashboards.

## Goals / Non-Goals

**Goals:**
- Grafana data persists across pod restarts (hostPath volume)
- `grafana install <path>` pushes a dashboard JSON to a running Grafana instance via HTTP API
- ClickHouse install template bundles its dashboards and registers them from `start.sh`
- Custom templates (`install --from`) can do the same by placing JSON files in their `dashboards/` subdir

**Non-Goals:**
- Grafana folder management (all dashboards go to General)
- Dashboard versioning or rollback
- Presto dashboards (none exist yet)

## Decisions

### 1. Persistent volume: hostPath over PVC

Use `hostPath(/mnt/db1/grafana)` on the control node rather than a PVC.

**Why**: Consistent with all other workload data paths in this project (`/mnt/db1/<workload>`). The entire cluster shares one AMI and `/mnt/db1` exists on every node. Control node already uses `/mnt/db1` for Pyroscope. PVCs add K8s provisioning complexity for no gain on ephemeral clusters.

**Alternative considered**: `local-storage-wfc` PVC. Rejected — over-engineered for a single-node, ephemeral use case.

### 2. HTTP API over ConfigMap + Deployment patch

Use Grafana's REST API (`POST /api/dashboards/db`) rather than creating a ConfigMap and patching the Grafana Deployment's volume list.

**Why**: With a persistent volume, the SQLite DB survives restarts, making the API the simplest path. ConfigMap patching would conflict with `grafana update-config`, which rebuilds the Deployment from `GrafanaManifestBuilder` and would drop any dynamically-added volumes.

**Alternative considered**: k8s-sidecar container watching labeled ConfigMaps. Rejected — adds a container to the Deployment for a problem that doesn't exist once the data volume is persistent.

### 3. OkHttp for the HTTP call

OkHttp is already a declared dependency. Use it directly — no new dependencies.

**No auth required**: Grafana is configured with `GF_AUTH_ANONYMOUS_ENABLED=true` and `GF_AUTH_ANONYMOUS_ORG_ROLE=Admin`, so API calls succeed without credentials.

### 4. Dashboard JSON in template `dashboards/` subdir

Dashboard JSON files live alongside other template files in `install/<workload>/dashboards/`. Files without `.template` suffix are already copied verbatim by `InstallTemplateResolver`. The generated `start.sh` iterates over `dashboards/*.json` with a glob.

**Why**: Consistent with the existing template copy mechanism. Users adding custom workloads via `--from` get dashboard support for free by the same convention.

### 5. Reuse existing dashboard JSON files for ClickHouse

Copy `dashboards/clickhouse.json` and `dashboards/clickhouse-logs.json` (top-level project dashboards) into the `install/clickhouse/dashboards/` classpath resource directory. These were built for the built-in `clickhouse` commands and are valid for the Altinity operator setup as well.

## Risks / Trade-offs

- **hostPath directory must exist before Grafana starts**: The K8s pod will fail if `/mnt/db1/grafana` doesn't exist on the control node. Mitigation: `grafana update-config` creates the directory via SSH before applying the Deployment (same pattern as Pyroscope's `/mnt/db1/pyroscope` setup in `GrafanaUpdateConfig.applyPyroscopeResources`).
- **`grafana install` fails if Grafana isn't ready**: The command hits the HTTP API; if called too early in `start.sh`, Grafana may not be up yet. Mitigation: Grafana starts at cluster `up` time (well before `start.sh` runs), so this is not a concern in practice.
- **`overwrite: true` silently replaces existing dashboards**: Intentional — idempotent re-runs of `start.sh` should be safe.

## Migration Plan

No migration needed. Clusters are ephemeral. The hostPath change takes effect on the next `grafana update-config` run (which restarts Grafana). Any existing emptyDir data is discarded, which is acceptable since it wasn't persistent anyway.
