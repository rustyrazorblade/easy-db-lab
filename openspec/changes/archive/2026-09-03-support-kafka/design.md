## Context

easy-db-lab kits are pluggable workloads declared in `kit.yaml` and deployed to K8s via Helm, manifests, or shell scripts. The kit system supports typed endpoints (`KitEndpoint.EndpointType`), Prometheus metrics scraping, and lifecycle phases (install/start/stop/uninstall). Currently, the endpoint type enum covers HTTP, HTTPS, JDBC, NATIVE, and CQL — no streaming protocol type exists.

Kafka on Kubernetes has one well-known complexity: advertised listeners. Unlike most services, Kafka tells clients which address to use for subsequent connections after the initial handshake. A single advertised address forces a choice between in-cluster routing (pod DNS) or external access (NodePort). Strimzi solves this by supporting multiple listener types with separate advertised addresses, configured via the `Kafka` CRD.

## Goals / Non-Goals

**Goals:**
- Deploy Kafka in KRaft mode (no ZooKeeper) via the Strimzi operator
- Expose both an in-cluster bootstrap address (K8s service DNS) and an external bootstrap address (NodePort on the db node's Tailscale IP)
- Add `KAFKA` to `KitEndpoint.EndpointType` for typed endpoint discovery
- Configurable broker count, version, and storage size

**Non-Goals:**
- Schema Registry support (separate concern, future issue)
- Topic pre-creation (left to the user or a future kit capability)
- Kafka Connect or ksqlDB deployment
- Authentication / TLS (lab environment; plaintext is fine)

## Decisions

### 1. Strimzi over Bitnami

**Decision**: Use the Strimzi operator (`strimzi/strimzi-kafka-operator` Helm chart) and `Kafka`/`KafkaNodePool` CRDs (API version `kafka.strimzi.io/v1beta2`).

**Rationale**: Strimzi's CRD model gives precise control over KRaft topology (combined controller+broker via `KafkaNodePool`), NodePort listener addresses, and JMX metrics — all first-class in the spec. Bitnami's `externalAccess` requires more Helm value wrangling and lacks the native metrics integration Strimzi provides via `metricsConfig`. Strimzi is production-grade and well-documented for this use case.

**Alternative considered**: Bitnami `bitnami/kafka` — rejected because Strimzi's CRD model fits the kit template pattern better and provides native JMX metrics configuration.

### 2. KRaft combined mode (no ZooKeeper)

**Decision**: `kraft.enabled: true`, `zookeeper.enabled: false`, single combined-mode broker (controller + broker in one process).

**Rationale**: ZooKeeper is deprecated in Kafka 3.x and removed in 4.x. Combined mode (single process is both controller and broker) is the simplest KRaft topology and appropriate for a 1-3 broker lab setup.

### 3. type: db

**Decision**: Kafka kit is `type: db`, targeting db nodes.

**Rationale**: Kafka has persistent data (topic partitions on disk). It belongs alongside other stateful workloads on db nodes, not on stateless app nodes. This also keeps Kafka co-located with Cassandra in a CDC scenario, minimising cross-node traffic.

### 4. Dual listeners via Strimzi NodePort configuration

**Decision**: Configure two listeners — `plain` (internal, port 9092) advertised as `kafka.default.svc.cluster.local:9092`, and `external` (NodePort 32100) for external access.

**Rationale**: Strimzi's `listeners` spec supports `type: nodeport` natively with a fixed `nodePort: 32100` and `preferredNodePortAddressType: InternalIP`. In-cluster consumers (e.g., Cassandra sidecar) use the K8s DNS address for efficiency; external tools connect via the NodePort on the node's Tailscale IP. No Helm value wrangling needed — the listener configuration lives in the `Kafka` CRD directly.

**Port choice**: 32100 — in the upper NodePort range, no conflict with existing kits.

### 5. New KAFKA endpoint type

**Decision**: Add `KAFKA` to `KitEndpoint.EndpointType`; `formatUrl` returns `host:port`.

**Rationale**: Reusing `NATIVE` would work at the URL level, but a distinct type lets tooling (MCP, future capabilities) semantically discover a Kafka bootstrap server without inspecting port numbers or kit names. The cost is one enum entry and one `formatUrl` branch.

## Risks / Trade-offs

- **External IP known at install time**: `__PRIVATE_IP__` must be injected into the Helm values when `kafka start` runs. This follows the existing pattern for other kits but requires the db node to be provisioned first. → Mitigation: `kafka` is a `type: db` kit, so `easy-db-lab up` provisions db nodes before any kit install.

- **Single-broker default for multi-tenant topics**: A single broker means no partition replication. For CDC testing this is fine; for replication-factor testing, users must set `--brokers ≥ 3`. → Mitigation: `--brokers` flag is exposed; document the limitation in README.

- **Strimzi CRD API version**: Strimzi uses versioned CRDs (`kafka.strimzi.io/v1beta2`). If the operator major version changes, the CRD API version may need updating. → Mitigation: pin the Strimzi chart version in the `install` step and document it in the kit.

## Open Questions

None. All decisions above are resolved.
