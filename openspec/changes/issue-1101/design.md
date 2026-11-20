## Context

Kit paths are under `src/main/resources/com/rustyrazorblade/easydblab/kits/`; Kotlin paths under `src/main/kotlin/com/rustyrazorblade/easydblab/`.

### Inventory

**hostPath to AMI agent directories**
- neo4j: `neo4j/statefulset.yaml.template:56` (agent flag), `:96-98` (mount), `:100-103` (hostPath `/usr/local/otel`).
- presto: `presto/bin/start.sh.template:21` (flag), `:32,34,45,47` (hostPath and mount `/usr/local/pyroscope`).
- trino: `trino/bin/start.sh.template:21` (flag), `:23-24` (hostPath and mount).
- stress jobs: `StressJobService.kt:150-160` (constants), `:419,429` (flags), `:464-473` (mounts), `:569-582` (hostPath volumes).

**hostNetwork**: `StressJobService.kt:553,625` with `ClusterFirstWithHostNet` at `:554,626`.

**hostPort**: presto coordinator 8080/9090 (`presto/bin/start.sh.template:24-28`); trino coordinator 8080 (`trino/bin/start.sh.template:33`) plus the deadlock workaround that deletes the coordinator pod (`trino/bin/start.sh.template:57-60`, `trino/bin/update-catalogs.sh.template:66-71`).

**One role per node**
- Label source: `Constants.kt:25` `NODE_TYPE_LABEL = "type"`; labels from `K3sClusterService.kt:384-399` (`getNodeLabels`), `K3sAgentService.kt:259-260`, `Up.kt:867`.
- Shell: `configuration/edl-kit-lib.sh:51-56` (`edl_require_app_node`), `:62` (`"nodeSelector":{"type":"app"}`), `:277-280` (`edl_push_gauges` selects the core collector by `node-role.kubernetes.io/control-plane`).
- Stress: `StressJobService.kt:556,628`.
- Kits with `nodeSelector type` or `key: type In [db|app]` affinity: `cassandra-pods/statefulset.yaml.template:26-27`; `memcached/memcached.yaml.template:22-28`; `memcached/memcached-extstore.yaml.template:51-57`; `neo4j/statefulset.yaml.template:33-39`; `postgres/postgres-cluster.yaml.template:38-44`; `clickhouse/clickhouseinstallation.yaml.template:125-131`; `clickhouse/clickhouse-keeper.yaml.template:36-42`; `tidb/tidbcluster.yaml.template:23-24,51-52,61-62,72-73`; `presto/values.yaml.template:5-10`; `trino/values.yaml.template:16-21`; `flink/flinkdeployment.yaml.template:24-25`. kafka and ignite3 are placed only through their local PVs (#1100).
- Role pools from `state.json` hosts (empty off AWS): `commands/install/KitInstallCommand.kt:135-145` (`requiredNodePoolIsMissing`); `services/TemplateVariables.kt:80-99` (`DB_NODE_COUNT`, `APP_NODE_COUNT`, `DB_NODE_IPS`); `services/KitEndpointResolver.kt:52-68` (`TARGET_*` from a host's `privateIp` + NodePort); `commands/cassandra/stress/StressStart.kt:62-75` (requires control hosts; contact point is the first VM db host); `StressJobService.kt:338-353` (requires region and control IP).
- `services/access/NodeServiceAccess.kt:7-12` dials the first node of the role, so with two app nodes presto/trino can be reached on the wrong node.

**Node SSH**: `cassandra-pods/lib/check-start.sh:102-121` already gated on `EDL_NODE_SSH` (#1108); `KitProcessEnvironment.kt:65` sets it.

**Fixed CIDR**: `clickhouse/clickhouseinstallation.yaml.template:13` (`10.0.0.0/8`); `postgres/postgres-cluster.yaml.template:56` (`host all all 10.0.0.0/8 trust`, missed by the issue). Both already break with `init --cidr 172.16.0.0/16`.

**Agent images**: cassandra-pods already copies the OTel agent from `ghcr.io/open-telemetry/opentelemetry-operator/autoinstrumentation-java:@OTEL_AGENT_VERSION@` (`statefulset.yaml.template:36-45`, `Constants.kt:632`, `build.gradle.kts:994-1004`). The Pyroscope agent exists only on the AMI and as an EMR bootstrap download. The catalog pins OTel 2.26.0 (`gradle/libs.versions.toml:73`); the AMI ships 2.31.1.

## Decision record

Owner, 2026-11-16: every decision D1-D9 below is decided as its option (a). D6: split by concern; #1135 owns the Pyroscope address, the control-node lookup in `buildJob`, `TemplateVariables.pyroscopeUrl` and `publishOnNode`, and lands before this change. D8: the AC2 blockers (install pool check, node counts, `TARGET_*` from the Kubernetes API and Service DNS) stay in this change. Test scope for postgres + sysbench on one node: a K3s integration test (task 7.4), following the "prefer TestContainers" rule, plus the AWS lab run.

## Goals / Non-Goals

**Goals:** no built-in kit or stress pod mounts a hostPath under `/usr/local` or uses `hostPort` / `hostNetwork`; one node can hold several roles; AWS placement, agent versions and dashboards are unchanged.

**Non-goals:** storage (#1100); Pyroscope address and `publishOnNode` (#1135); the AMI sidecar.

## Decisions

### D1. Where the agent jars come from (decided (a), owner 2026-11-16)
- **(a) Chosen (owner 2026-11-16):** one agent per image. OTel: upstream `autoinstrumentation-java`. Pyroscope: a small multi-arch image easy-db-lab publishes (`ghcr.io/rustyrazorblade/pyroscope-java-agent:2.3.0`, busybox + checksummed jar) from `publish-container.yml`.
- (b) One easy-db-lab agents image with both jars; cassandra-pods moves to it. Easy-db-lab republishes the OTel agent.
- (c) Download the Pyroscope jar at pod start (curl + SHA). No image, but every start depends on GitHub and fails air-gapped.

### D2. Presto and Trino coordinator exposure (decided (a), owner 2026-11-16)
- **(a) Chosen (owner 2026-11-16):** a NodePort Service owned by each kit, presto 30882, trino 30881, selecting `app.kubernetes.io/component=coordinator`. Follows the root CLAUDE.md rule; works through every #1090 seam; fixes the multi-app-node bug.
- (b) ClusterIP only. Works only through port-forward; the SOCKS seam dials node IPs.
- (c) Switch each chart's own Service to NodePort through values. Ties the port to chart value names that differ between charts.

### D3. Role mapping representation and flag (decided (a), owner 2026-11-16)
- **(a) Chosen (owner 2026-11-16):** `RoleSelectors` in state, `init --role-nodes <role>=<k=v>[,…]` repeatable, validated like `--node-label` but separate from it.
- (b) One flag per role (`--db-node-selector`, `--app-node-selector`).
- (c) Fixed label convention (`easydblab.com/role-db=true`), no flag; the user relabels their nodes, and AWS would differ.

### D4. "Anywhere" on your cluster with no mapping (decided (a), owner 2026-11-16)
- **(a) Chosen (owner 2026-11-16):** truly anywhere; co-location shows in the fidelity grade (#1098).
- (b) Preferred (soft) anti-affinity between clients and db pods; needs a common db pod label and an affinity block in every template.
- (c) Required anti-affinity. Breaks AC2 on one node; rejected.

### D5. ClickHouse and Postgres allowed CIDRs (decided (a), owner 2026-11-16)
- **(a) Chosen (owner 2026-11-16):** AWS: VPC CIDR + `Constants.K3s.CLUSTER_CIDR` (10.224.0.0/12, which K3s is already started on with `--cluster-cidr`). Elsewhere: probed at `init` from node `spec.podCIDRs` and InternalIP /32s, plus `init --client-network` (for CNIs like EKS VPC-CNI). Recorded in state.
- (b) Probe at every kit start, no state; cannot cover VPC-CNI without an override anyway.
- (c) Allow every address. Simplest; on your cluster a passwordless ClickHouse and a `trust` Postgres would be open to anything reaching their NodePorts.

### D6. Stress-job split with #1135 (decided (a), owner 2026-11-16)
- **(a) Chosen (owner 2026-11-16):** #1101 owns hostNetwork, hostPath/agents, selector and the stress target; #1135 owns the Pyroscope address, the control-node lookup in `buildJob`, `TemplateVariables.pyroscopeUrl` and `publishOnNode`. #1135 lands first.
- (b) #1101 in two PRs: kits, shell library, roles and CIDRs first; stress jobs after #1135.
- (c) #1101 also takes the stress Pyroscope address; eats into #1135's "no node-IP URL" AC.

### D7. How stress picks its target (decided (a), owner 2026-11-16; gap the issue does not name)
- **(a) Chosen (owner 2026-11-16):** the `cassandra-pods` kit if running, else the VM db hosts, else fail naming both.
- (b) Explicit `stress start --target cassandra-pods|vm`, defaulting to (a).
- (c) Kit off AWS, VM on AWS; switches on environment name, rejected by the capabilities principle.

### D8. AC2 blockers in scope (decided (a), owner 2026-11-16)
- **(a) Chosen (owner 2026-11-16):** in #1101: the install pool check, node counts and `TARGET_*` from the Kubernetes API and Service DNS. AC2 cannot pass without them.
- (b) A separate #1055 sub-issue; AC2 is then verifiable only on AWS-shaped state until it lands.

### D9. Which core collector pod receives shell gauges (decided (a), owner 2026-11-16)
- **(a) Chosen (owner 2026-11-16):** the pod on the first node matching the control selector; with none, the first Running core pod by name (stable, so one gauge stays one series). AWS unchanged.
- (b) Leave to a #1092 follow-up; gauges are lost where no control-plane node is visible.

### Fixed choices
- `NodeSelector` (value class) and `RoleSelectors` live in `configuration/`; `RoleSelectors.from(state)` reads null as `OWNED_NODES`, like `NodeCapabilities.from`.
- Every single-value `key: type In [x]` affinity becomes a `nodeSelector` (same semantics) so one variable covers all kits.
- `KitPlacementVariables` composes into `TemplateVariables` so its constructor does not grow.
- Keep `edl-kit-lib.sh` function names; old workspaces keep the old library (written once, never rewritten) — acceptable for ephemeral clusters, called out in the PR.

## Interfaces

| Unit | Package | Responsibility |
|---|---|---|
| `JavaAgent` enum (`OTEL`, `PYROSCOPE`) | `configuration/agents` | image ref, jar path in image, file name |
| `JavaAgentDelivery.applyTo(pod, container, agents)`, `flag(agent)` | `configuration/agents` | emptyDir, init containers, read-only mount; pure fabric8 |
| `NodeSelector`, `RoleSelectors` | `configuration/` | JSON and label-selector forms; `OWNED_NODES`, `ANYWHERE`, `from(state)` |
| `RoleNodes(k8sService)` | `services/` | the one role-pool lookup (`nodesMatching`), counts; fails naming the selector |
| `KitPlacementVariables` | `services/` | selector, label and count variables merged into `TemplateVariables` |
| `ClientNetworks`, `ClientNetworkProbe` | `configuration/`, `services/` | allowed CIDRs; probe at `init` off AWS |
| `StressTarget` (`VmCassandra`, `CassandraKit`), `StressTargetResolver` | `services/stress` | contact point, port, DC |
| Constants | | `Constants.Kit.Agents.*`, `Constants.Kit.Presto.NODE_PORT = 30882`, `Constants.Kit.Trino.NODE_PORT = 30881`, `Constants.K3s.CLUSTER_CIDR`, `Constants.Kit.AGENTS_MOUNT = "/edl/agents"` |

## Split with #1100 and #1135

| Work | Owner |
|---|---|
| Kit claim class, size, selector-bound claims, local-PV SSH `mkdir` / `rm -rf`, PV node affinity | #1100 |
| hostPath agents, hostPort, hostNetwork, role selectors in kits / shell lib / stress, role pools, stress target, client CIDRs | #1101 |
| Stress and presto/trino Pyroscope address (control IP → Service DNS), `buildJob` control-node requirement, `publishOnNode` removal | #1135 |

Either merge order works: #1135 points stress at the Pyroscope Service (`pyroscope.default.svc:4040`, resolved through cluster DNS on any node) and drops `publishOnNode` and the control-node requirement, so no stress pod dials `controlIP:4040`. To keep the conflict in `StressJobService` small, #1101 moves pod-spec work into `JavaAgentDelivery` and a placement step, leaving #1135 only the Pyroscope address line and the control-node lookup.

## Risks / Trade-offs

- **Agent version drift** if the catalog is not raised to 2.31.1: neo4j and stress would downgrade on AWS and JVM metric names could change.
- **Flannel clusters:** stress traffic crosses VXLAN; throughput and latency may shift against older baselines. Recorded in the fingerprint (#1098).
- **eBPF attribution:** Beyla / ebpf_exporter now see stress traffic from a pod IP; check network panels in the AWS lab run.
- **Old workspaces keep the old shell library.**
- **First start pulls agent images** on each node; off AWS they later go through the user's registry (K9).
- **Pod Security (#1095):** init containers need a restricted security context; `cp` into an emptyDir works as non-root.
- **Endpoint ports move** for presto and trino; docs and READMEs update.
- **Anywhere placement** on a multi-node cluster the user brings can co-locate db and stress (D4).
