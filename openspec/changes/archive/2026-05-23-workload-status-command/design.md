## Context

Installed workloads run entirely in K8s. Services are exposed on private IPs only (Tailscale access). After `easy-db-lab presto start`, the user has no structured way to discover where to connect. The existing `WorkloadRunnerCommandFactory` synthesizes subcommands from `bin/` scripts and `install.yaml` lifecycle phases — `status` needs to be synthesized the same way but is fundamentally different: it reads state rather than executing steps.

## Goals / Non-Goals

**Goals:**
- `easy-db-lab <workload> status` available for every installed workload
- Shows running state (pod count, helm release state)
- Shows connection endpoints (URL, JDBC string, native ip:port) with private IPs
- Running-state detection inferred from install method; `runtime:` block as explicit override for shell-installed workloads
- `APP_NODE_IPS` available as template variable

**Non-Goals:**
- No SSH or remote execution — all checks go through the local kubeconfig
- No health probes (HTTP GET to check if the service responds)
- No global `status` aggregating all workloads (that's `easy-db-lab status`)

## Decisions

### WorkloadStatusCommand is a standalone Kotlin class, not a WorkloadRunnerCommand phase

`WorkloadRunnerCommand` executes shell scripts or typed steps. Status doesn't run anything — it reads cluster state and calls K8s APIs. Reusing `WorkloadRunnerCommand` would require shoehorning a read-only query into the step execution model. A separate `WorkloadStatusCommand` is cleaner and keeps concerns separated.

**Alternative considered:** Add `status` as a recognized phase type in `install.yaml` with a `type: k8s-status` step. Rejected — too much machinery for what is fundamentally a cluster-state read.

### Running-state detection priority

```
1. runtime: block in install.yaml       → explicit, highest priority
2. typed start phase (type: helm)       → infer helm release from phase
3. typed start phase (type: manifest)   → kubectl get pods -l app.kubernetes.io/name=<workload>
4. bin/-only (no typed phases)          → kubectl get pods -l app.kubernetes.io/name=<workload> (best-effort)
```

Shell-installed workloads that don't match heuristic labels should add a `runtime:` block to their `install.yaml`.

### `runtime:` block covers K8s resource type + identifier

Everything runs in K8s, so `runtime:` always describes a K8s resource:

```yaml
runtime:
  type: helm          # or: deployment, statefulset, pods
  release: presto     # for helm; name for deployment/statefulset; selector for pods
  namespace: default
```

**Alternative considered:** Separate `bin/status.sh` per workload. Rejected — shell scripts can't produce structured output, and the user would have to author boilerplate for every workload.

### `endpoints:` section declares connection metadata

Each endpoint declares node type, port, and connection protocol. The status command resolves private IPs from cluster state at runtime.

```yaml
endpoints:
  - name: "Presto UI"
    node-type: app       # maps to ServerType.Stress
    port: 8080
    type: http
  - name: "JDBC"
    node-type: app
    port: 8080
    type: jdbc
    scheme: presto
    path: /cassandra
```

Supported `type` values and their URL formats:

| type     | format                          |
|----------|---------------------------------|
| `http`   | `http://ip:port/path`           |
| `https`  | `https://ip:port/path`          |
| `jdbc`   | `jdbc:scheme://ip:port/path`    |
| `native` | `ip:port`                       |
| `cql`    | `ip:port`                       |

If multiple nodes of the same type exist, one URL per node is printed.

### APP_NODE_IPS added to TemplateVariables

App nodes (`ServerType.Stress`) already have their count tracked but not their IPs. `APP_NODE_IPS` is added as a comma-separated list of private IPs, mirroring `DB_NODE_IPS`. This is needed by the status command to resolve `node-type: app` endpoints.

## Risks / Trade-offs

- **Label mismatch for bin/-only workloads** — best-effort `app.kubernetes.io/name=<workload>` selector may not match. Mitigation: `runtime:` block with explicit selector.
- **Helm release name differs from workload name** — Presto's release is `presto` (matches), but future workloads may differ. Mitigation: `runtime.release` override.
- **Cluster state unavailable** — if `cluster-state.yaml` doesn't exist, IP resolution fails gracefully with a message.
