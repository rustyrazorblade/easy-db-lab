## ADDED Requirements

### Requirement: Kits and stress jobs carry no host dependency

No pod created by a built-in kit or by a stress job SHALL mount a `hostPath` under `/usr/local`, declare a `hostPort`, or set `hostNetwork`. A Java agent (OTel, Pyroscope) SHALL be delivered by an init container that copies its jar from an agent image into a `java-agents` `emptyDir`, mounted read-only at `/edl/agents` in the JVM container, and the JVM SHALL load it from `/edl/agents/<jar>`. Agent image references SHALL come from one source, shared by kit templates (`__OTEL_AGENT_IMAGE__`, `__PYROSCOPE_AGENT_IMAGE__`) and Kotlin. This SHALL cover neo4j, presto, trino, cassandra-pods and the stress run job.

#### Scenario: No host dependency in any built-in kit or stress job
- **WHEN** the packaged kit resources and `edl-kit-lib.sh` are scanned, and every kit manifest and a stress Job are rendered
- **THEN** none contains `hostPath`, `hostPort`, `hostNetwork`, `/usr/local/otel` or `/usr/local/pyroscope`

#### Scenario: Agent jars come from an init container
- **WHEN** a stress Job is built
- **THEN** every `-javaagent:` path in its options names a file under `/edl/agents` that one of its init containers copies into the mounted `emptyDir`
- **AND** in a running stress pod both agent jars exist at those paths

#### Scenario: Agent versions on AWS are unchanged
- **WHEN** neo4j and a stress job start on AWS
- **THEN** they load OTel Java agent 2.31.1 and Pyroscope agent 2.3.0, the versions the AMI ships

### Requirement: Roles map to node selectors per environment

Where each role's pods run SHALL come from a `RoleSelectors(db, app, control)` value resolved from cluster state. AWS and local SHALL resolve to `type=<role>`; a cluster the user brings SHALL resolve to no constraint for each role unless the user maps it with `init --role-nodes <role>=<key=value>`. Kit templates SHALL receive `__DB_NODE_SELECTOR__` and `__APP_NODE_SELECTOR__` as JSON maps, and kit processes SHALL receive `DB_NODE_LABELS`, `APP_NODE_LABELS` and `CONTROL_NODE_LABELS` as label selectors (empty when unconstrained). No kit resource, `edl-kit-lib.sh` or `StressJobService` SHALL hard-code `type=db`, `type=app` or `type=control`. Role pools (the kit install pool check, `DB_NODE_COUNT`, `APP_NODE_COUNT`) SHALL be the nodes matching the role's selector, read from the Kubernetes API, not hosts in `state.json`.

#### Scenario: AWS placement is unchanged
- **WHEN** every templated kit manifest is rendered with the AWS role selectors
- **THEN** each pod's effective node constraint equals today's `type=<role>`

#### Scenario: Unmapped roles schedule anywhere
- **WHEN** every templated kit manifest and a stress Job are rendered with no role constraint
- **THEN** no pod carries a role node selector or a role node affinity

#### Scenario: A one-node cluster runs postgres with sysbench
- **GIVEN** a one-node cluster with no `type` label and unmapped roles
- **WHEN** postgres starts and sysbench prepares and runs against it
- **THEN** all pods schedule on the one node and the sysbench run completes

#### Scenario: A one-node cluster runs the Cassandra kit with cassandra-easy-stress
- **GIVEN** a one-node cluster with no `type` label and unmapped roles
- **WHEN** the cassandra-pods kit starts and a stress job runs against it
- **THEN** both schedule and the stress Job succeeds

#### Scenario: A user role mapping is honoured
- **GIVEN** `init --role-nodes db=pool=databases --role-nodes app=pool=clients`
- **WHEN** a db kit and a stress job are rendered
- **THEN** the db pods select `pool=databases` and the stress pod selects `pool=clients`

#### Scenario: A malformed role mapping is refused
- **WHEN** `init --role-nodes db=bad` runs
- **THEN** it fails naming the value and the expected `<role>=<key=value>` form

### Requirement: Steps that need node SSH run only where NODE_SSH is present

A kit step that connects to a node over SSH SHALL run only when `EDL_NODE_SSH` reports the capability, and SHALL otherwise print one line saying it was skipped and why, and continue.

#### Scenario: The Cassandra kit starts without NODE_SSH
- **GIVEN** an environment without `NODE_SSH`
- **WHEN** the cassandra-pods kit starts
- **THEN** it starts, and its AMI-Cassandra check is skipped and reported

### Requirement: Database kits allow the environment's client networks

ClickHouse's `default` user networks and Postgres's `pg_hba` trust lines SHALL list the environment's client networks, not a fixed `10.0.0.0/8`. On AWS these SHALL be the VPC CIDR and the K3s cluster CIDR (`Constants.K3s.CLUSTER_CIDR`, passed explicitly to K3s). Elsewhere they SHALL be probed at `init` from the nodes' pod CIDRs and InternalIPs, plus each `init --client-network <cidr>`, before anything is cleaned or written, whenever `init` is given the API of a cluster the user brings (the `init --kubeconfig` option that supplies it is #1082). The set SHALL be recorded in state. The probe SHALL trust every node's InternalIP, including nodes in no role, because NodePort traffic can be SNATed from any node. In a K3s in Docker workspace (`init --local`) the recorded set SHALL be exactly the `--client-network` values. Only an environment whose networks easy-db-lab builds (the cluster contract's `clientNetworkOrigin`, EC2 only) SHALL derive them from the VPC CIDR; any other state that records no client networks SHALL fail to render kit variables, naming `--client-network`, and `init` SHALL warn (`Setup.NoClientNetworksRecorded`) when it records none.

#### Scenario: A local workspace never trusts the VPC default
- **GIVEN** `init --local` with no `--client-network`
- **WHEN** a kit's variables are rendered
- **THEN** rendering fails naming `--client-network`, and `10.0.0.0/16` is trusted nowhere
- **AND** with `--client-network 172.18.0.0/16` the client networks are exactly `172.18.0.0/16`

#### Scenario: ClickHouse allows the AWS VPC and pod CIDRs
- **GIVEN** an AWS cluster with `init --cidr 172.16.0.0/16`
- **WHEN** the clickhouse kit is rendered
- **THEN** its allowed networks are `172.16.0.0/16` and the K3s cluster CIDR, and not `10.0.0.0/8`

#### Scenario: Probed networks off AWS
- **GIVEN** a cluster whose nodes report pod CIDR `10.244.0.0/24` and InternalIP `192.168.1.5`
- **WHEN** `init` runs with `--client-network 192.168.0.0/16`
- **THEN** the recorded client networks are `10.244.0.0/24`, `192.168.1.5/32` and `192.168.0.0/16`
- **AND** the postgres kit renders one trust line per network
