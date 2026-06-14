## Why

Kafka is a foundational streaming platform needed for CDC pipelines (e.g., Cassandra → Kafka → OpenSearch) and standalone streaming benchmarks. Without a Kafka kit, these multi-component test scenarios cannot be assembled in easy-db-lab.

## What Changes

- New `kafka` kit deploying Apache Kafka in KRaft mode via the Bitnami Helm chart
- New `KAFKA` endpoint type added to `KitEndpoint.EndpointType` for typed bootstrap server discovery
- Dual-listener configuration: INTERNAL (pod-to-pod, K8s DNS) and EXTERNAL (NodePort, Tailscale IP) exposed simultaneously

## Capabilities

### New Capabilities

- `kafka-kit`: Kafka kit lifecycle — install, start, stop, uninstall via Bitnami Helm; KRaft mode; persistent storage; configurable broker count and version
- `kafka-endpoint-type`: New `kafka` endpoint type in the endpoint model, with `formatUrl` returning `host:port`

### Modified Capabilities

<!-- none -->

## Impact

- **New files**: `src/main/resources/com/rustyrazorblade/easydblab/kits/kafka/` (kit.yaml, values.yaml.template, README.md.template, metrics-catalog.json)
- **Modified**: `KitConfig.kt` — add `KAFKA` to `KitEndpoint.EndpointType` enum and its `formatUrl` branch
- **No breaking changes** to existing kits or commands
