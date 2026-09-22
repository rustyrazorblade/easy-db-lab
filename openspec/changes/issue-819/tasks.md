# Tasks

## 1. Cilium becomes the default CNI

- [x] 1.1 `configuration/ClusterState.kt:125`: `InitConfig.cni` default → `CniMode.Cilium`.
- [x] 1.2 `configuration/ClusterState.kt:45`: rewrite the `CniMode` KDoc so Cilium is the default
      and Flannel the selectable alternative.
- [x] 1.3 `commands/Init.kt:247-252`: `--cni` default → `CniMode.Cilium`; help text names `cilium`
      as the default and `flannel` as the alternative.
- [x] 1.4 `InitTest`: default-value tests expect `CniMode.Cilium`; add a test that `--cni=flannel`
      yields `CniMode.Flannel`.
- [x] 1.5 Sweep every `InitConfig(...)` construction in tests; pin `cni = CniMode.Flannel` wherever
      the test's assertion depends on Flannel (e.g. no Cilium scrape jobs, K3s add-ons present).
- [x] 1.6 Leave the `?: CniMode.Flannel` fallbacks in `services/OtelSyncService.kt:31` and
      `services/ObservabilityStackService.kt:104` unchanged. If either lacks a comment saying a
      cluster with no recorded CNI predates Cilium and runs Flannel, add one; otherwise leave it.
- [x] 1.7 Docs: the CNI line in `CLAUDE.md` (Observability section),
      `src/main/kotlin/com/rustyrazorblade/easydblab/configuration/CLAUDE.md`,
      `docs/user-guide/networking.md`, and `docs/user-guide/platform-substrate.md` state Cilium is
      the default and `--cni=flannel` selects Flannel.
- [x] 1.8 Docs: note in `docs/user-guide/networking.md` that AMIs built before this change lack the
      Cilium node fixes and must be rebuilt with `build-image`.
- [x] 1.9 Security group: allow ICMP (all types) from the VPC CIDR in
      `AwsInfrastructureService.setupVpcNetworking`; constants in `Constants.Network`; tests.
      (Found in live validation; owner directive: fix bugs in this PR.)
- [x] 1.10 `EC2VpcService`: describe the ICMP rule as "all ICMP types" in the log line and the
      `SecurityGroupRuleConfigured` event text; tests.

## 2. Kit port convention

- [x] 2.1 `CLAUDE.md`, Kit Development section: add the rule — kits expose client ports through a
      NodePort Service (range 30000-32767), never `hostPort` or `hostNetwork`; cite postgres 30432
      and clickhouse 30123.
- [x] 2.2 `docs/development/kits.md`: add the same rule.

## 3. memcached kit

All under `src/main/resources/com/rustyrazorblade/easydblab/kits/memcached/`.

- [x] 3.1 `kit.yaml`: `type: db`, `collision-check: true`, `--memory` → `MEMORY_MB` (int, default
      1024), endpoint `memcached` type `native` port 31211 `node-type: db`, `metrics` scrape entry on
      9150 with a `pod-selector` for the memcached pod, runtime selector `easydblab/kit=memcached`,
      stop/uninstall steps deleting by that label.
- [x] 3.2 `memcached.yaml.template`: one-replica Deployment, db node affinity, memcached container
      with `-m ${MEMORY_MB}`, `memcached-exporter` sidecar on 9150, all objects labelled
      `easydblab/kit=memcached`.
- [x] 3.3 `nodeport-service.yaml.template`: NodePort 31211 → container 11211, kit label.
- [x] 3.4 Live: install, start, and confirm `memcached_*` series in VictoriaMetrics; run
      `bin/export-workload-metrics memcached` to produce `metrics-catalog.json`.
- [x] 3.5 `dashboards/memcached.json` via the `dashboard-editor` agent, from the catalog.
- [x] 3.6 `METRICS.md`.
- [x] 3.7 Docs: `docs/user-guide/kits.md` (install, `--memory`, start, connect, stop) and
      `docs/reference/ports.md` (31211, 9150).

## 4. Neo4j kit

All under `src/main/resources/com/rustyrazorblade/easydblab/kits/neo4j/`.

- [x] 4.1 `kit.yaml`: `type: db`, `collision-check: true`, `--version` (5.x and calendar-versioned 2025.x onward, default
      pinned to the current Community release), endpoints Bolt `native` 30687 and HTTP `http`
      30474 (`node-type: db`), `metrics: [{type: java-agent, service-name: neo4j}]`, a shell step
      that reads the first db node's private IP for the Bolt advertised address, `platform-pvs`
      step, stop deleting sts/svc/pods by `easydblab/kit=neo4j`, uninstall additionally deleting
      PVCs and running `platform-pvs-delete`.
- [x] 4.2 `statefulset.yaml.template`: one replica, db node, image `neo4j:${VERSION}-community`,
      `NEO4J_AUTH=none`, `hostPath` `/usr/local/otel` mounted read-only, `NEO4J_server_jvm_additional`
      carrying `-javaagent:`, `OTEL_SERVICE_NAME=neo4j`, `HOST_IP` from `status.hostIP`, OTLP
      endpoint `http://$(HOST_IP):4318`, 5 s export interval, Bolt advertised address from 4.1,
      `volumeClaimTemplates` metadata carrying `easydblab/kit=neo4j`.
- [x] 4.3 `nodeport-service.yaml.template`: NodePorts 30687 → 7687 and 30474 → 7474, kit label.
- [ ] 4.4 Live, first task after the kit starts: list the metrics the pod exports in
      VictoriaMetrics (`job="neo4j"`) and the MBeans the JVM registers. **Review the list with the
      owner before writing any dashboard panel.**
- [ ] 4.5 If Community registers database-level MBeans, add a JMX rules ConfigMap so the agent
      exports them; otherwise record in `METRICS.md` that only JVM and HTTP metrics are available.
- [ ] 4.6 `bin/export-workload-metrics neo4j` → `metrics-catalog.json`.
- [ ] 4.7 `dashboards/neo4j.json` via the `dashboard-editor` agent, with the panels agreed in 4.4.
- [ ] 4.8 `METRICS.md`.
- [x] 4.9 Docs: `docs/user-guide/neo4j.md` (install, start, connect over Bolt and HTTP, stop),
      linked from `docs/SUMMARY.md`; add 30687 and 30474 to `docs/reference/ports.md`.

## 5. Fold-in debt

- [x] 5.1 `services/KitConfig.kt:17`: rewrite the `KitMetrics.JavaAgent` KDoc line to say it is
      declarative — the agent pushes OTLP to the node collector and no ConfigMap changes — and name
      the agent path correctly (`/usr/local/otel/opentelemetry-javaagent.jar`, mounted from the
      host).

## 6. Live validation on AWS

- [x] 6.1 `./gradlew installDist`. No AMI bake: the existing AMIs already carry the Cilium node fixes (owner decision).
- [x] 6.2 `init` with no `--cni` (i4i.xlarge, 1 control + 2 db nodes in different AZs) and `up`;
      confirm `platform cni` reports Cilium native routing.
- [x] 6.3 Cross-AZ pod-to-pod connectivity by pod IP. This closes `cilium-native-routing` tasks 6.2
      and 8.4 — check them off in `openspec/changes/cilium-native-routing/tasks.md`.
- [x] 6.4 hostPort check: install and start Flink or Presto; confirm its series appear in
      VictoriaMetrics. If they do not, add `set("cni.chainingMode=portmap")` to the `--set` list in
      `CiliumService` (shared by install and upgrade), assert it in `CiliumServiceTest`, rebuild,
      and repeat the check.
- [ ] 6.5 memcached: install, start, set/get from a probe pod over `<db IP>:31211`, confirm the
      dashboard shows data, second install fails with `CollisionDetected`, stop, uninstall; confirm
      nothing labelled `easydblab/kit=memcached` remains.
- [ ] 6.6 Neo4j: install, start, `RETURN 1` over `bolt://<db IP>:30687`, confirm `job="neo4j"`
      series and dashboard data, second install fails with `CollisionDetected`, stop, uninstall;
      confirm PVCs and PVs are gone.
- [x] 6.7 Provision a separate `--cni=flannel` cluster and confirm it comes up with Flannel.

## 7. Build

- [x] 7.1 `./gradlew ktlintFormat`.
- [x] 7.2 `./gradlew check` (JDK 21), run in a subagent.
- [x] 7.3 `./gradlew installDist`.
- [x] 7.4 `openspec validate issue-819 --type change --strict`.
