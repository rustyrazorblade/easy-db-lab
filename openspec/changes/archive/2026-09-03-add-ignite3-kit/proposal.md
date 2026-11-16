## Why

easy-db-lab supports ClickHouse and Presto as query/analytics workloads, but has no in-memory distributed database option. Apache Ignite 3 fills that gap: it is a distributed SQL + key-value platform with native persistence and OTLP metrics, making it a natural fit for the lab's observability stack.

## What Changes

- Add a new `ignite3` kit that deploys an Apache Ignite 3 cluster on `db` nodes using raw K8s manifests (StatefulSet + Services + ConfigMap + Init Job)
- The kit exposes a `--storage` arg with three profiles: `aimem` (volatile), `aipersist` (default, in-memory + disk persistence), `rocksdb` (disk-based LSM)
- The kit exposes a `--replicas` arg defaulting to `${DB_NODE_COUNT}`
- Metrics are pushed from Ignite pods to the cluster's OTel Collector via OTLP (`http/protobuf`), configured via Ignite's REST API after cluster init
- SQL capability is registered via the `sql` capability block using the Ignite thin client JDBC driver on port 10800
- A documentation page is added for the `ignite3` kit

## Capabilities

### New Capabilities

- `ignite3`: Deploy, configure, start, stop, and uninstall an Apache Ignite 3 cluster; expose SQL via thin client JDBC; push metrics to the OTel Collector via OTLP

### Modified Capabilities

(none)

## Impact

- New kit resource directory: `src/main/resources/com/rustyrazorblade/easydblab/kits/ignite3/`
- New spec: `openspec/specs/ignite3/spec.md`
- New doc page: `docs/` for the ignite3 kit
- No changes to existing Kotlin code — the kit uses only the existing kit runner, manifest, shell, and capability infrastructure
- Requires the Ignite 3 thin client JDBC driver to be on the classpath (new Gradle dependency)
