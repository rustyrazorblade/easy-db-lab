## ADDED Requirements

### Requirement: OTel Collector is reachable via ClusterIP Service

A Kubernetes ClusterIP Service named `otel-collector` SHALL be deployed in the `default` namespace alongside the OTel DaemonSet. The Service SHALL expose the OTLP gRPC port (4317) so that any pod in the cluster can push traces to `otel-collector.default.svc.cluster.local:4317` without knowing the node IP.

#### Scenario: Standard pod can push OTLP traces

- **WHEN** a non-hostNetwork pod (e.g., a TiDB SQL pod) is configured to export traces to `otel-collector.default.svc.cluster.local:4317`
- **THEN** the OTel collector on the cluster receives and processes those traces

#### Scenario: Service selects DaemonSet pods

- **WHEN** the `otel-collector` ClusterIP Service is applied to the cluster
- **THEN** it SHALL select pods with label `app.kubernetes.io/name: otel-collector`
- **AND** kube-proxy (or Cilium's eBPF replacement) SHALL route gRPC traffic to one of the DaemonSet pods

#### Scenario: Existing host-network behaviour is unchanged

- **WHEN** the ClusterIP Service is added
- **THEN** Fluent Bit SHALL continue to reach the OTel collector via `127.0.0.1:4318`
- **AND** the OTel collector SHALL continue to scrape host-networked Prometheus targets via `localhost:<port>`
