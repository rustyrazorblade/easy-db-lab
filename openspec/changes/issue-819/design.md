# Design

## Context

`cilium-native-routing` added Cilium ENI native routing as a selectable datapath and left Flannel
as the default. The default is `CniMode.Flannel` in two places: `InitConfig.cni`
(`configuration/ClusterState.kt:125`) and the `--cni` option on `Init` (`commands/Init.kt:247-252`).
Two readers of saved state fall back to Flannel when `initConfig` is absent or predates the `cni`
field: `OtelSyncService.kt:31` and `ObservabilityStackService.kt:104`.

Kits reach the operator and other pods through NodePort Services today (postgres 30432,
clickhouse 30123). Trino, Presto, and Flink use `hostPort` for scrape or client ports. K3s's
Flannel install chains the `portmap` CNI plugin, which is what makes `hostPort` work; Cilium's
default Helm install does not chain it.

JVM kits get metrics from the OpenTelemetry Java agent that the base AMI installs at
`/usr/local/otel/opentelemetry-javaagent.jar`. `KitMetrics.JavaAgent` is declarative: the agent
pushes OTLP to the node's collector and nothing in the OTel ConfigMap changes. Its KDoc says
otherwise.

## Goals / Non-Goals

**Goals.** Cilium is the default datapath; Flannel is selectable. Kits that use `hostPort` keep
working under Cilium. Two new kits prove the datapath end to end: memcached (NodePort + scrape
metrics) and Neo4j (NodePort + Java-agent OTLP metrics). The NodePort convention is written down.

**Non-Goals.** Migrating Trino, Presto, or Flink off `hostPort`. Enabling Cilium's kube-proxy
replacement. Neo4j Enterprise, clustering, or auth. memcached persistence or replication.

## Decisions

### Flip the default in the two places that own it

`InitConfig.cni` and the `--cni` option both become `CniMode.Cilium`; the enum KDoc at
`ClusterState.kt:45` stops calling Flannel the default. The `?: CniMode.Flannel` fallbacks stay:
they describe what a cluster *without* a recorded CNI is running, and every such cluster was
provisioned before Cilium existed and runs Flannel. Changing them would make the observability
stack render Cilium scrape jobs on a Flannel cluster.

### hostPort under Cilium: verify live, chain portmap only if needed

The live validation installs a `hostPort` kit (Flink or Presto) on a default (Cilium) cluster and
checks its series in VictoriaMetrics. If they are missing, `CiliumService` adds
`cni.chainingMode=portmap` to the `--set` list shared by `cilium install` and `cilium upgrade`,
asserted in `CiliumServiceTest`. The spec states the outcome (hostPort reachable, series present),
not the mechanism.

### Kits expose client ports through NodePort Services

Both new kits publish through NodePort Services on fixed ports distinct from every existing kit
and from Hubble UI (31234): memcached 31211, Neo4j Bolt 30687 and HTTP 30474. The rule goes into
`CLAUDE.md` and `docs/development/kits.md` so future kits do not reintroduce `hostPort`.

### memcached: Deployment plus exporter sidecar

A one-replica Deployment with db node affinity. `--memory` maps to `MEMORY_MB` (default 1024) and
is passed to memcached as `-m`. A `memcached-exporter` sidecar serves Prometheus metrics on 9150,
declared as `KitMetrics.Scrape` with a `pod-selector`, so the collector on the pod's node finds it
by Kubernetes pod discovery. No PVs: memcached is in-memory. `stop` and `uninstall` delete by
`easydblab/kit=memcached`.

### Neo4j: Community StatefulSet, Java agent by hostPath

A one-replica StatefulSet on a db node, data on a `platform-pvs` volume. `--version` accepts 5.x
and the calendar-versioned releases (2025.x onward); the image is `neo4j:<ver>-community`, defaulting to the current Community release.
`NEO4J_AUTH=none`.

The agent jar is mounted read-only by `hostPath` from `/usr/local/otel` and loaded through
`NEO4J_server_jvm_additional`. `OTEL_SERVICE_NAME=neo4j` sets `job="neo4j"`. `HOST_IP` comes from
the downward API (`status.hostIP`) and the exporter pushes to `http://$(HOST_IP):4318` with a 5 s
interval. `kit.yaml` declares `metrics: [{type: java-agent, service-name: neo4j}]`.

The Bolt advertised address must be the node address clients dial: first db node private IP and
30687. No template variable carries a single db IP, so a shell step reads it and passes it to the
manifest.

Dashboard panels are chosen after the metrics a live pod actually exports are reviewed with the
owner. JVM and HTTP metrics are certain; database-level metrics only if Community exposes them (if
it exposes JMX MBeans, a JMX rules ConfigMap is added).

`stop` deletes the StatefulSet, Services, and pods by `easydblab/kit=neo4j`. `uninstall` also
deletes the PVCs (the `volumeClaimTemplates` metadata carries the kit label so the selector matches)
and runs `platform-pvs-delete`.

### Collision check on both kits

Both kits set `collision-check: true`, so a second install into the same cluster fails with
`CollisionDetected` before anything is applied.

## Alternatives Considered

These are the options the architect presented at the design stop. The owner picked the recommended option on every decision; there are no owner overrides.

**D1: OpenSpec handling of the Cilium default.**
- Chosen: validate live, check off tasks 6.2 and 8.4 of `cilium-native-routing`, archive that change first, then MODIFY the "Pod-network datapath" requirement in `issue-819`.
- Rejected: edit the unarchived `cilium-native-routing` delta in place and fold issue 819 into that change. It is quicker, but it rewrites a change record that documented a deliberate owner decision (the default stays Flannel).

**D2: How the kits expose client ports.**
- Chosen: NodePorts (memcached 31211, Neo4j Bolt 30687, Neo4j HTTP 30474), the house pattern (postgres 30432, clickhouse 30123, ignite3 30300). Reachable on any node IP, independent of hostPort support in the CNI.
- Rejected: `hostNetwork: true` on the standard ports (11211, 7687, 7474), pinned to the first db node. It pins to a host, a port conflict shows only as a CrashLoop so `start` needs its own pre-check, and the exporter also binds a host port.
- Rejected: plain `hostPort`. It depends on portmap chaining, which Cilium does not have in the current install.

**D3: memcached kit shape.**
- Chosen: declarative `kit.yaml` steps with manifest templates (postgres, kafka, ignite3 pattern), with `shell` steps only for label-scoped deletes.
- Rejected: `bin/start.sh.template` / `stop.sh.template` (sysbench pattern). That fits one-off pods better than a long-running server.

**D4: memcached metrics path.**
- Chosen: `memcached-exporter` sidecar on 9150, scraped with `KitMetrics.Scrape` pod-selector discovery. One series per pod, no metrics NodePort, no Kotlin change.
- Rejected: a metrics NodePort with a static scrape target (postgres/kafka style). Static NodePort jobs appear to be scraped by every collector in the DaemonSet (see structural debt, recommended as a separate issue).

**D5: How Neo4j metrics leave the pod.**
- Chosen: OTLP push from the OTel Java agent to the collector on the pod's own node (`status.hostIP:4318`), the same `metrics/otlp` path Cassandra uses; `OTEL_SERVICE_NAME=neo4j` gives `job="neo4j"`.
- Rejected: the agent's Prometheus exporter on 9464 with a pod-selector scrape. It matches the original AC wording literally, but Neo4j would then be the only JVM workload labelled differently from Cassandra.

**Neo4j dashboard content if Community exposes no database MBeans.**
- Chosen: build the kit first, review the exported metrics with the owner on a live pod, then decide the panels (JVM and HTTP metrics are guaranteed).
- Deferred alternative: a Cypher-polling exporter sidecar for database-level panels. Only considered if the live review shows it is needed.

## Risks / Trade-offs

- **hostPort under Cilium.** Trino, Presto, and Flink may lose their `hostPort` scrape endpoints.
  Covered by the live check and the conditional portmap chaining.
- **AMIs predating the Cilium fixes.** Nodes from an older AMI lack the cloud-init hotplug and
  systemd-networkd ENI drop-ins. Docs say to run `build-image` after this change.
- **Neo4j Community may lack database-level metrics.** The dashboard's database panels are
  conditional on what the live pod exports; JVM and HTTP panels are certain.
- **Missing `OTEL_SERVICE_NAME`.** Series would arrive as `job="unknown_service:java"`. The
  requirement pins `job="neo4j"`.
- **Neo4j config keys are version-specific.** Restricting to 5.x and the calendar-versioned releases (2025.x onward) keeps one key set
  (`server.*`).
- **Collision check not firing.** Covered by an explicit scenario on each kit.
- **No single db IP template variable.** Handled by a shell step that reads the first db node's
  private IP.
- **Half-applied manifests on a NodePort clash with another kit.** Avoided by the NodePort
  convention and distinct fixed ports per kit.
