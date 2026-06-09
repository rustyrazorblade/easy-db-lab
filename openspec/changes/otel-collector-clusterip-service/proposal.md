## Why

The OTel DaemonSet runs with `hostNetwork: true` and has no Kubernetes Service, making it unreachable from standard K8s pods (like those created by the TiDB Operator) that use normal pod networking. Without a ClusterIP Service, there is no stable DNS name for non-hostNetwork workloads to push OTLP traces to the collector.

## What Changes

- A Kubernetes ClusterIP Service is added to `OtelManifestBuilder`, exposing the OTLP gRPC port (4317) via stable in-cluster DNS: `otel-collector.default.svc.cluster.local:4317`
- The TiDB `tidbcluster.yaml.template` is updated to restore the `opentelemetry` config block, pointing to the ClusterIP Service DNS name

## Capabilities

### New Capabilities

_(none)_

### Modified Capabilities

- `observability`: The OTel collector gains a ClusterIP Service, making the OTLP gRPC endpoint addressable from any pod in the cluster via stable DNS

## Impact

- `configuration/otel/OtelManifestBuilder.kt`: add `buildService()` method and include it in the resources returned alongside the DaemonSet
- `kits/tidb/tidbcluster.yaml.template`: restore the `opentelemetry` config block with the correct ClusterIP DNS endpoint
- `openspec/specs/observability/spec.md`: add requirement for the ClusterIP Service
