# Make Cilium the default CNI, and add memcached and Neo4j kits

## Why

Cilium ENI native routing landed as an opt-in (`--cni=cilium`, change `cilium-native-routing`)
with Flannel kept as the default until it had been proven. Issue 819 flips the default: a cluster
provisioned with no `--cni` option now runs Cilium, and Flannel remains one flag away. Flipping
the default is only credible if ordinary kits keep working on the new datapath, so the change
proves it with two new kits that exercise the paths a Cilium cluster must carry — a NodePort
client endpoint reached from another pod and from the operator's machine, Prometheus-scrape
metrics (memcached), and OTLP-push metrics from a JVM via the OpenTelemetry Java agent (Neo4j).

Existing kits that publish ports with `hostPort` (Trino, Presto, Flink) are the known risk under
Cilium, whose default install does not chain the `portmap` plugin. The change checks that live and
fixes it with portmap chaining if the check fails, and writes down the convention that new kits
expose client ports through a NodePort Service, never `hostPort` or `hostNetwork`.

## What Changes

- **Default CNI is Cilium.** `InitConfig.cni` defaults to `CniMode.Cilium`
  (`configuration/ClusterState.kt`), and `init --cni` defaults to `cilium` with help text saying
  so (`commands/Init.kt`). `--cni=flannel` still selects K3s's built-in Flannel overlay. Clusters
  whose saved state has no `cni` field keep reading as Flannel (`?: CniMode.Flannel` fallbacks in
  `OtelSyncService` and `ObservabilityStackService` are unchanged).
- **hostPort works on a Cilium cluster.** A kit that declares `hostPort` stays reachable on its
  node and its scrape series reach VictoriaMetrics. If the live check shows otherwise, `CiliumService`
  installs with `cni.chainingMode=portmap`.
- **New `memcached` kit** (`kits/memcached/`): a one-replica Deployment on the db pool, a NodePort
  Service on 31211 declared as a `native` endpoint, a `--memory` arg (MB, default 1024),
  `collision-check: true`, a `memcached-exporter` sidecar on 9150 scraped through
  `KitMetrics.Scrape` pod-selector discovery, a Grafana dashboard, `METRICS.md`, and
  `metrics-catalog.json`. No persistent volumes.
- **New `neo4j` kit** (`kits/neo4j/`): a one-replica Neo4j Community StatefulSet on a db node,
  backed by a platform PV; `--version` accepts 5.x and the calendar-versioned releases (2025.x onward); `NEO4J_AUTH=none`; Bolt NodePort
  30687 (`native`) and HTTP NodePort 30474 (`http`); metrics pushed over OTLP by the OpenTelemetry
  Java agent mounted from the base AMI, arriving as `job="neo4j"`; a Neo4j Grafana folder;
  `METRICS.md` and `metrics-catalog.json`.
- **Kit port convention.** `CLAUDE.md` (Kit Development) and `docs/development/kits.md` gain a
  rule: kits expose client ports through a NodePort Service in 30000-32767, never `hostPort` or
  `hostNetwork` (precedent: postgres 30432, clickhouse 30123).
- **Fold-in.** The `KitMetrics.JavaAgent` KDoc in `services/KitConfig.kt` is corrected: the agent
  pushes over OTLP declaratively and needs no ConfigMap.
- **Docs.** `docs/user-guide/networking.md`, `docs/user-guide/platform-substrate.md`, the CNI line
  in `CLAUDE.md` and `configuration/CLAUDE.md` state the new default and that AMIs built before
  the `cilium-native-routing` change must be rebuilt (`build-image`) to carry the Cilium node fixes.
  `docs/user-guide/kits.md` and `docs/reference/ports.md` list memcached; a new
  `docs/user-guide/neo4j.md` (linked from `SUMMARY.md`) covers Neo4j.
- **ICMP inside the VPC.** The cluster security group allows ICMP (all types) from the VPC CIDR,
  so ping works between nodes and pods and Cilium's health checker sees every node. Found during
  live validation; the rule is described to the user as "all ICMP types".

## Capabilities

### New Capabilities

- `memcached-kit` — the memcached kit: placement, NodePort endpoint, cache-size arg, collision
  check, lifecycle cleanup, exporter metrics, dashboard, docs.
- `neo4j-kit` — the Neo4j Community kit: placement and storage, versions, Bolt and HTTP endpoints,
  advertised address, Java-agent metrics, dashboard, lifecycle cleanup, docs.

### Modified Capabilities

- `networking` — the `Pod-network datapath (Cilium ENI native routing, selectable)` requirement
  (added by the unarchived `cilium-native-routing` change) changes its default from `flannel` to
  `cilium`. A new requirement states that `hostPort` works on a Cilium cluster.
- Kit convention (documentation only, no spec capability): the NodePort-only rule for client ports
  is recorded in `CLAUDE.md` and `docs/development/kits.md`.

## Impact

- `src/main/kotlin/com/rustyrazorblade/easydblab/configuration/ClusterState.kt` — default and KDoc.
- `src/main/kotlin/com/rustyrazorblade/easydblab/commands/Init.kt` — `--cni` default and help.
- `src/main/kotlin/com/rustyrazorblade/easydblab/services/CiliumService.kt` — `portmap` chaining,
  only if the live hostPort check fails.
- `src/main/kotlin/com/rustyrazorblade/easydblab/services/KitConfig.kt` — KDoc only.
- `src/main/resources/com/rustyrazorblade/easydblab/kits/memcached/` — new.
- `src/main/resources/com/rustyrazorblade/easydblab/kits/neo4j/` — new.
- Tests: `InitTest` default expectations, `InitConfig(...)` constructions that rely on Flannel,
  `CiliumServiceTest` if chaining is added.
- Docs: `CLAUDE.md`, `configuration/CLAUDE.md`, `docs/development/kits.md`,
  `docs/user-guide/networking.md`, `docs/user-guide/platform-substrate.md`,
  `docs/user-guide/kits.md`, `docs/reference/ports.md`, `docs/user-guide/neo4j.md`,
  `docs/SUMMARY.md`.
- AMIs: AMIs built before the `cilium-native-routing` change lack the Cilium node fixes
  (cloud-init hotplug, systemd-networkd ENI drop-ins) and must be rebuilt with `build-image`.
  AMIs built after it need no rebuild for this change (owner decision: no bake in this change).
- `src/main/kotlin/com/rustyrazorblade/easydblab/services/aws/AwsInfrastructureService.kt`,
  `EC2VpcService.kt`, `Constants.kt` — ICMP ingress rule from the VPC CIDR (found in live
  validation; Cilium health reported peers unreachable without it).
- Ordering: `cilium-native-routing` must be archived before this change.
