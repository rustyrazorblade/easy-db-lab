## Why

#1199 tunes the network sysctls in each node's host network namespace.  Most of them (`net.core.somaxconn`, `net.ipv4.tcp_max_syn_backlog`, `tcp_rmem` / `tcp_wmem`, the `tcp_keepalive_*` values, `tcp_slow_start_after_idle`, `tcp_tw_reuse`) are per network namespace in Linux, and a new pod namespace starts at kernel defaults.  Every kit database runs in the pod network, so it gets `somaxconn` 4096 instead of 65535 and a 7200 s keepalive instead of 60 s.  Results from kit databases then differ from a tuned production deployment.  The owner made this a follow-up of #1199 (decision A of that spec), because every kit must be verified on a real cluster.

## What Changes

- Every node's kubelet permits the per-namespace network sysctls that Kubernetes classes as "unsafe" (`--allowed-unsafe-sysctls`), from a kubelet-arg drop-in in the K3s config directory.  It is written in the K3s bootstrap seam.
- One cluster-wide admission policy, applied by `up`, adds a `securityContext.sysctls` block with the tuned values to every pod in the pod network when the pod is created.  Host-network pods are left alone (the host's values already reach them, and the kubelet rejects a `net.*` sysctl on a host-network pod).  This covers every kit, every operator-built pod and every later kit with no per-kit block (owner decisions R2 and R3 in the design).
- Pods keep the kernel defaults for `ip_local_port_range` and `ip_local_reserved_ports`; the host's reserved list names host ports, not a pod's listeners (owner decision R1).
- The pod values come from the same packaged file the host applies (`node-tuning/60-easydblab.conf`), read from the classpath.  The host file and the pod values cannot drift.  A unit test fails when a new `net.*` key in that file is not classed as per-namespace or host-only.
- The K3s server enables the admission policy API (beta on the K3s release the AMI carries).
- `up` fails when the policy cannot be applied.
- Each kit (ClickHouse, clickhouse-benchmark, Postgres, TiDB, Kafka, Neo4j, Presto, Trino, Ignite 3, memcached, Flink, FerrosaDB, sysbench) is verified on a real cluster: its pods report the tuned values, and it installs, starts and serves a workload.
- Docs: the kit authoring guide and `kits/CLAUDE.md` say what a kit's pods get and what a kit must not do; the node tuning page drops its follow-up note.

## Capabilities

### New Capabilities

- `pod-network-tuning`: the per-namespace network sysctls every pod in the pod network runs with, where the values come from, and how the kubelet permits them.

### Modified Capabilities

None.  `node-os-tuning` is still pending in `openspec/changes/issue-1199` (not archived), so this change adds its own capability instead of a delta to it.

## Impact

- Code: a classpath reader for the packaged sysctl file, a fabric8 builder for the admission policy and its binding, `UpPostSteps`, `K3sService` and `K3sAgentService` (kubelet drop-in), `start-k3s-server.sh` (API server flags), `Constants.NodeTuning`.
- Tests: unit tests for the classification and the generated policy; a K3s TestContainers integration test that creates pods and reads the values back.
- Real AWS verification of every kit (`requires-aws`).  No `packer/` change, no AMI bake.
- Docs: `docs/development/kits.md`, `src/main/resources/com/rustyrazorblade/easydblab/kits/CLAUDE.md`, `docs/user-guide/node-tuning.md`, `docs/user-guide/platform-substrate.md`, and the `configuration/CLAUDE.md` / `services/CLAUDE.md` entries for the new classes.
