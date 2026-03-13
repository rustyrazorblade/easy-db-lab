## Context

The Cassandra Sidecar runs on every db node to support SSTable uploads, blob restores, and schema management. Today it is:

1. **Built during Packer** — `install_sidecar.sh` clones the Git repo and runs `./gradlew shadowjar installDist`, taking 5–10 minutes.
2. **Run as a bare systemd service** — `cassandra-sidecar.service` executes the compiled binary from `/usr/local/cassandra-sidecar/bin/cassandra-sidecar`.
3. **Configured via systemd EnvironmentFile** — `SetupInstance.kt` writes `/etc/default/cassandra-sidecar` with `JAVA_OPTS`, `OTEL_SERVICE_NAME`, and `OTEL_EXPORTER_OTLP_ENDPOINT`.

The cluster already runs k3s on all nodes and uses K8s DaemonSets for every other per-node service (OTel Collector, Fluent Bit, Beyla, ebpf_exporter, etc.). The Apache project now publishes `ghcr.io/apache/cassandra-sidecar:latest` from their CI, eliminating the need to build from source.

## Goals / Non-Goals

**Goals:**
- Eliminate the Packer source build entirely
- Run the sidecar as a K8s DaemonSet on `type=db` nodes using `ghcr.io/apache/cassandra-sidecar:latest`
- Preserve all existing sidecar functionality: config mount, data dir mount, OTel/Pyroscope agent instrumentation
- Match the management model of all other cluster DaemonSets (deploy via `SidecarService`, restart via `rolloutRestart`)
- Update `Start`, `Stop`, `Restart` CLI commands accordingly
- Add the sidecar to the `grafana update-config` deployment flow

**Non-Goals:**
- Pinning to a specific image tag (use `latest` for now; pinning is a separate concern)
- Changing the sidecar configuration file format or location
- Adding new Grafana dashboards for sidecar

## Decisions

### Use `ghcr.io/apache/cassandra-sidecar:latest`

**Decision:** Pull the pre-built image directly; no airgap/skopeo pre-pull needed.

**Alternatives considered:**
- Airgap pre-pull with `skopeo copy` during Packer — adds complexity, no benefit since nodes have internet access during cluster setup
- Build from source during Packer — current approach, 5–10 min penalty per AMI build

**Rationale:** k3s pulls images on demand when a DaemonSet is applied. Nodes have outbound internet access. `ghcr.io` is the authoritative registry for this project.

### DaemonSet targets `type=db` nodes only

**Decision:** Use a `nodeSelector: {type: db}` and a matching toleration so the DaemonSet only runs on database nodes.

**Rationale:** The sidecar is only meaningful on db nodes. Running it on control or stress nodes would be wasteful and incorrect.

### hostNetwork: true

**Decision:** Run the DaemonSet with `hostNetwork: true`.

**Rationale:** The sidecar serves the Cassandra process on the same host. All other node-level DaemonSets use hostNetwork. The sidecar binds to well-known ports on the host's IP that the control plane must reach.

### Volume mounts

| Host path | Container path | Purpose |
|-----------|---------------|---------|
| `/etc/cassandra-sidecar` | `/etc/cassandra-sidecar` (read-only) | Config YAML |
| `/mnt/db1/cassandra` | `/mnt/db1/cassandra` | Data, logs, staging dirs |
| `/usr/local/otel` | `/usr/local/otel` (read-only) | OTel Java agent JAR |
| `/usr/local/pyroscope` | `/usr/local/pyroscope` (read-only) | Pyroscope Java agent JAR |

**Rationale:** Same mounts that the previous systemd service used. The config path is referenced by `-Dsidecar.config=file:///etc/cassandra-sidecar/cassandra-sidecar.yaml`. Mounts must match the `JAVA_OPTS` paths exactly.

### Environment variables via DaemonSet spec

**Decision:** Replace systemd EnvironmentFile with K8s env vars in the DaemonSet spec.

| Variable | Source |
|----------|--------|
| `JAVA_OPTS` | Built-in constant string with all agent flags; `$(HOSTNAME)`, `$(CLUSTER_NAME)`, `$(PYROSCOPE_SERVER_ADDRESS)` use K8s env var interpolation |
| `HOSTNAME` | `fieldRef: spec.nodeName` |
| `CLUSTER_NAME` | `configMapKeyRef: cluster-config/cluster_name` |
| `PYROSCOPE_SERVER_ADDRESS` | `configMapKeyRef: cluster-config/control_node_ip` formatted as URL |
| `OTEL_SERVICE_NAME` | Literal `cassandra-sidecar` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | Literal `http://localhost:4317` |

**Rationale:** The `cluster-config` ConfigMap is already deployed by other builders. Using K8s env var interpolation (`$(VAR_NAME)` syntax) in the DaemonSet spec avoids per-host SSH file uploads entirely.

### SidecarService interface

**Decision:** Replace `interface SidecarService : SystemDServiceManager` with a dedicated K8s-oriented interface:

```kotlin
interface SidecarService {
    fun deploy(controlHost: Host)
    fun rolloutRestart(controlHost: Host)
    fun remove(controlHost: Host)
}
```

**Rationale:** The old interface (start/stop/restart on individual hosts via SSH) no longer maps to the deployment model. K8s operations act on the cluster as a whole via the control plane.

### Commands

- `cassandra start` — calls `sidecarService.deploy(controlHost)` instead of per-host systemd start
- `cassandra stop` — calls `sidecarService.remove(controlHost)` instead of per-host systemd stop
- `cassandra restart` — calls `sidecarService.rolloutRestart(controlHost)` instead of per-host systemd restart

### SetupInstance cleanup

**Decision:** Remove `setupSidecarSystemdEnv` entirely from `SetupInstance.kt`.

**Rationale:** The function uploaded `/etc/default/cassandra-sidecar` to each host. With K8s env vars in the DaemonSet spec, this file is no longer needed.

## Risks / Trade-offs

- **Image freshness** — `latest` can change unexpectedly. Acceptable for now; pinning can be addressed in a follow-up.
- **Image pull time on first deploy** — first `deploy()` will pull the image (~200 MB) from ghcr.io. This happens once per node at cluster setup time, not at AMI build time. Subsequent deploys use the cached image.
- **Rolling restart behavior** — `rolloutRestart` patches the DaemonSet annotation to trigger a rolling update. Cassandra nodes go one at a time with the K8s default rolling strategy.
- **Existing clusters** — clusters provisioned with the old systemd approach will still have the old binary. Running `update-config` (which now includes sidecar deployment) will migrate them.
