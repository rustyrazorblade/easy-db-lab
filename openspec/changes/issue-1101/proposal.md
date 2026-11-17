## Why

Part of #1032 (Kubernetes-forward epic), phase A. Sub-issue of #1055 (kit target contract). Design: `docs/plans/2026-11-11-kubernetes-forward-architecture.md`, section "Kit target contract".

Kits and stress jobs depend on hosts easy-db-lab built: hostPath mounts of the AMI's `/usr/local/otel` and `/usr/local/pyroscope` (neo4j, presto, trino, stress jobs), `hostNetwork` on stress jobs, `hostPort` 8080/9090 on the presto and trino coordinators, one `type=db|app|control` label per node hard-coded in nine kits, `edl-kit-lib.sh` and `StressJobService`, and `10.0.0.0/8` as the only allowed client network in ClickHouse and Postgres. A one-node laptop cluster cannot run a database next to its stress client, and Pod Security "baseline" rejects the host dependencies outright.

The Cassandra kit's SSH check is already gated on `EDL_NODE_SSH` (#1108), so that part of the issue is done and only needs coverage named.

## What Changes

- **Agents from init containers.** A `java-agents` `emptyDir` mounted read-only at `/edl/agents`; one init container per agent copies its jar in; JVMs use `-javaagent:/edl/agents/<jar>`. Applies to the stress run job, neo4j, and the presto and trino coordinator and workers. cassandra-pods moves to the same image variables. OTel agent image is the upstream `autoinstrumentation-java`; the Pyroscope agent comes from a small multi-arch `pyroscope-java-agent:2.3.0` image easy-db-lab publishes (D1). The OTel catalog pin rises to the AMI's 2.31.1 so AWS agent versions do not change.
- **Stress jobs on the pod network.** No `hostNetwork`, no `ClusterFirstWithHostNet`, no hostPath. Placement from the app role's selector.
- **Presto and Trino behind NodePort Services** owned by each kit, on fixed ports 30882 and 30881 (D2). The hostPort patches and Trino's coordinator-delete workaround go.
- **Role selectors per environment.** `RoleSelectors(db, app, control)` recorded in state; AWS and local keep `type=<role>`; a cluster the user brings is "anywhere" unless mapped with `init --role-nodes <role>=<key=value>` (D3). Templates get `__DB_NODE_SELECTOR__` / `__APP_NODE_SELECTOR__` (JSON maps); shells get `DB_NODE_LABELS`, `APP_NODE_LABELS`, `CONTROL_NODE_LABELS`. Every `key: type In [db]` affinity becomes a `nodeSelector` from the variable. `edl-kit-lib.sh` (bash 3.2) reads the variables instead of `type=app`.
- **Role pools from the Kubernetes API, not `state.json` hosts** (D8): `RoleNodes` counts and lists nodes matching a role's selector for the kit install pool check and `DB_NODE_COUNT` / `APP_NODE_COUNT`; `TARGET_*` for in-cluster clients resolves to the endpoint's Service DNS and port (#1110).
- **Stress targets the Cassandra kit.** `StressTarget` (`VmCassandra`, `CassandraKit`) chosen by D7; `stress start` drops its control-host requirement.
- **Gauge push** picks the core collector pod by the control selector, else the first Running core pod (D9).
- **Allowed client networks** for ClickHouse and Postgres come from `ClientNetworks`: on AWS the VPC CIDR plus the explicit K3s cluster CIDR; elsewhere probed at `init` from node podCIDRs and InternalIPs plus `init --client-network` (D5).
- **Guard test** over packaged kit resources and `edl-kit-lib.sh`: no `hostPath`, `hostPort`, `hostNetwork`, `/usr/local/(otel|pyroscope)`, literal `type: db|app`, `"type":"app"` or `10.0.0.0/8`.

## Capabilities

### New Capabilities
- `kit-host-independence`: kits and stress jobs carry no host dependency; role placement, agent delivery and client networks follow the environment.

### Modified Capabilities
- `kit-shell-library`: app-node check and pod placement read the role mapping.
- `sysbench-app-node-placement`: sysbench pods follow the app role's selector.
- `stress-testing`: stress jobs on the pod network, targeting VM Cassandra or the Cassandra kit.
- `trino`: Pyroscope agent from an init container; coordinator behind a NodePort Service.
- `presto`: HTTP API reached through the coordinator's NodePort Service.

## Impact

- **On AWS:** placement unchanged (`type=<role>`), agent versions unchanged, dashboards unchanged. Presto and Trino endpoints move from app-node:8080 to NodePort 30882 / 30881 on any node. Stress traffic now leaves a pod IP; under Cilium ENI this costs nothing, under `--cni=flannel` it crosses VXLAN (record in the run fingerprint, #1098).
- **Off AWS:** one node can hold the database and its client.
- Specs touched: those above plus `clickhouse-benchmark-kit`, `tidb`, `kit-install-command`, `networking` (ports) during implementation. Docs: `docs/development/kits.md`, `docs/reference/ports.md`, `docs/reference/commands.md`, kit user guides, `configuration/CLAUDE.md`, `neo4j/METRICS.md`.

## Out of scope

- Kit storage class and size, local-PV SSH `mkdir` (#1100).
- Stress-job and presto/trino Pyroscope **address** (control IP to Service DNS) and `publishOnNode` removal (#1135).
- The AMI Cassandra sidecar (`SidecarManifestBuilder`), which is AWS-only by design.
- Neutral kit variables replacing `CONTROL_HOST_PRIVATE` / `DB_NODE_IPS` in ignite3, neo4j and kafka (#1055).
