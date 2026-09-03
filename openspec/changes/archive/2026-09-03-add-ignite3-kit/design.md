## Context

easy-db-lab's kit system deploys workloads onto K8s clusters using a declarative `kit.yaml`. Two patterns exist: operator-based (ClickHouse via Altinity) and Helm-based (Presto). Apache Ignite 3 ships no official operator or Helm chart — its documented K8s deployment is raw manifests: a ConfigMap, a headless Service, a StatefulSet, an optional NodePort Service, and a one-time cluster-init Job.

The cluster already runs an OTel Collector that accepts OTLP pushes. Ignite 3 has a built-in OTLP metrics exporter, making instrumentation trivial without sidecars.

## Goals / Non-Goals

**Goals:**
- Deploy a multi-node Ignite 3 cluster on `db` nodes via raw K8s manifests
- Support three storage profiles (`aimem`, `aipersist`, `rocksdb`) selectable at start time
- Push metrics to the cluster's OTel Collector via OTLP
- Expose SQL via the `sql` capability (thin client JDBC on port 10800)
- Add Gradle dependency for the Ignite 3 thin client JDBC driver

**Non-Goals:**
- Cassandra Storage SPI integration (Ignite 2 only, not available in Ignite 3)
- Ignite 2.x support (tracked separately as a future kit)
- Compute grid or streaming APIs (not needed for initial persistence testing)
- TLS/auth configuration (lab clusters, not production)

## Decisions

### Raw K8s manifests over Helm / operator

**Decision:** Use templated YAML manifests (`.yaml.template`) loaded via `TemplateService`, consistent with the ClickHouse kit's non-operator resources.

**Rationale:** Ignite 3 has no official Helm chart or K8s operator. Raw manifests are the documented upstream approach and give full control over resource shape. The kit system already handles templated manifests cleanly.

**Alternative considered:** Build a Helm chart from scratch — adds maintenance burden with no benefit since we control the templates directly.

### Cluster Init Job as a shell step

**Decision:** After the StatefulSet pods are Ready, run a K8s Job (templated manifest) that executes `ignite3 cluster init --name=ignite --url=http://ignite-svc-headless:10300`. The shell step waits for Job completion before proceeding.

**Rationale:** Ignite 3 requires a one-time initialization call before the cluster is usable. A Job is idiomatic K8s for one-shot tasks and keeps the init logic inside the cluster (no local CLI required, consistent with `RemoteOperationsService` patterns).

**Alternative considered:** REST call from the shell step — requires `curl` and is less robust than a Job with restart semantics.

### OTLP push for metrics

**Decision:** Configure Ignite's built-in OTLP exporter to push to `http://${CONTROL_HOST_PRIVATE}:4318/v1/metrics` via a REST call after cluster init. No `metrics:` block in `kit.yaml`.

**Rationale:** Ignite 3 does not expose a Prometheus scrape endpoint. Its native OTLP support is a first-class exporter. The OTel Collector on the control node already has an OTLP HTTP receiver. This avoids a JMX sidecar entirely.

**Alternative considered:** JMX exporter sidecar — requires an extra container, an extra port, and a scrape config registration. OTLP push is strictly simpler.

### Storage profile as a `--storage` arg

**Decision:** Accept `--storage` with values `aimem`, `aipersist` (default), `rocksdb`. The value is injected into the ConfigMap template as `__STORAGE_PROFILE__` and selects the storage profile in `ignite-config.conf`.

**Rationale:** The primary use case is persistence testing, so `aipersist` is the right default. Exposing all three lets users choose the tradeoff (speed vs durability vs capacity) without changing the kit.

### Ignite 3 thin client JDBC driver for SQL capability

**Decision:** Add `org.apache.ignite:ignite-client` (the Ignite 3 thin client, which includes JDBC) to the Gradle dependencies. Register the `sql` capability in `kit.yaml` pointing to the thin client JDBC endpoint on port 10800.

**Rationale:** The `sql` capability system already handles JDBC with a `driver-class` field for drivers that don't auto-register. The Ignite thin client driver class is `org.apache.ignite.jdbc.IgniteJdbcDriver`.

## Risks / Trade-offs

- **Cluster init idempotency**: If the start script is run twice without a stop, the init Job will attempt to re-initialize. Mitigation: shell step checks if the cluster is already initialized before running the Job, or the Job is made idempotent via a check.
- **OTLP endpoint timing**: The OTLP exporter is configured via REST after init. If the OTel Collector on the control node isn't reachable at that moment, metrics will not flow. Mitigation: the OTel Collector is a cluster-level service that starts before any kit, so this should not occur in practice.
- **Ignite 3 image versioning**: The `apache/ignite3` Docker image tag must be pinned or defaulted. Using `latest` risks non-reproducible deployments. A `--version` arg should be added.
- **PVC cleanup**: `aipersist` and `rocksdb` create PersistentVolumeClaims. The `stop` step must not delete PVCs (to preserve data), but `uninstall` must. The ClickHouse kit handles this via `platform-pvs-delete` — the same pattern applies here.

## Open Questions

(none — all decisions resolved)
