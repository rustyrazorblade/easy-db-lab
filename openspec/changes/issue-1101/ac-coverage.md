| Source | Requirement | Covering scenario(s) | Status |
|--------|-------------|----------------------|--------|
| AC1 | No pod mounts hostPath under `/usr/local`, or uses hostPort / hostNetwork | `kit-host-independence: No host dependency in any built-in kit or stress job`; `Agent jars come from an init container`; `trino: Coordinator reachable on any node`; `presto: Coordinator reachable on any node` | |
| AC2 | One-node cluster runs postgres + sysbench, and the Cassandra kit + cassandra-easy-stress | `kit-host-independence: A one-node cluster runs postgres with sysbench`; `A one-node cluster runs the Cassandra kit with cassandra-easy-stress`; `stress-testing: Stress targets the Cassandra kit` | D8 decided (a): blockers in this change; stress off AWS needs #1135, which lands first (D6) |
| AC3 | Without NODE_SSH the Cassandra kit starts, check skipped and reported | `kit-host-independence: The Cassandra kit starts without NODE_SSH` | Already implemented by #1108; coverage named |
| AC4 | On AWS placement and dashboards unchanged | `kit-host-independence: AWS placement is unchanged`; `Agent versions on AWS are unchanged`; `sysbench-app-node-placement: Run pod on an app node` | Dashboards verified in AWS lab run (task 9.2) |
| Scope | Agents via init container into emptyDir | `kit-host-independence: Agent jars come from an init container`; `trino: Pyroscope agent injected` | |
| Scope | Stress jobs on pod network | `stress-testing: Start a stress job` | |
| Scope | Presto/Trino ports through Services | `presto/trino: Coordinator reachable on any node` | |
| Scope | Roles map to selectors; `edl-kit-lib.sh` reads the mapping | `kit-host-independence: Unmapped roles schedule anywhere`; `A user role mapping is honoured`; `A malformed role mapping is refused`; `kit-shell-library: Unconstrained app role accepts any node`; `Overrides JSON with an unconstrained app role` | |
| Scope | ClickHouse allows environment CIDRs | `kit-host-independence: ClickHouse allows the AWS VPC and pod CIDRs`; `Probed networks off AWS` | Postgres added (missed by issue) |
| Risk | Role pools read from `state.json` hosts | `kit-host-independence: Roles map to node selectors per environment` (pool from K8s API) | D8 decided (a) |
| Risk | Stress can only target VM Cassandra | `stress-testing: Stress targets the Cassandra kit`; `No stress target` | D7 decided (a) |
| Risk | Gauges lost without a control-plane node | task 4.1 | D9 decided (a) |
| Risk | Agent version downgrade on AWS | `kit-host-independence: Agent versions on AWS are unchanged` | |
| Risk | Multi-app-node presto/trino dialled on wrong node | `presto/trino: Coordinator reachable on any node` | |
| Excluded | Pyroscope address via Service DNS, `publishOnNode` removal | — | #1135 |
| Excluded | Kit storage / local-PV SSH mkdir | — | #1100 |
| Excluded | AMI Cassandra sidecar host dependencies | — | AWS-only by design |
