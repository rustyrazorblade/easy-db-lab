## Context

#1199 (merged in #1203, change `openspec/changes/issue-1199`, not archived yet) installs `/etc/sysctl.d/60-easydblab.conf` on every node.  The file is the classpath resource `src/main/resources/com/rustyrazorblade/easydblab/commands/node-tuning/60-easydblab.conf`.  `NodeTuningFiles` stages it from the classpath (`Constants.NodeTuning.RESOURCE_DIR`, `SYSCTL_FILE`), and `setup_instance.sh` applies it with `sysctl -p`.  Its D8 "Scope limit" records the gap this change closes: the per-namespace `net.*` keys reach only the host network namespace.

Facts from the tree:

- **K3s version.** It is not in `Constants`.  The AMI pins `K3S_VERSION="v1.35.1+k3s1"` in `packer/base/install/install_k3s.sh`, and `SharedK3s.K3S_IMAGE` is `rancher/k3s:v1.35.1-k3s1` (its KDoc says the two must match).  So the kubelet and API server are Kubernetes 1.35.
- **K3s config seam.** `start-k3s-server.sh` and `start-k3s-agent.sh` (classpath, `services/`) write kubelet drop-ins to `/etc/rancher/k3s/config.yaml.d/` on every run: `10-kubelet-eviction.yaml` (`kubelet-arg:`), `15-kubelet-limits.yaml` (`kubelet-arg+:`), and on the server `20-datastore.yaml`.  `DefaultK3sService.startServer` and `DefaultK3sAgentService.start` upload and run them; `K3sClusterService` calls those from `bringup/k3s/K3sControlSteps` and `K3sNodeSteps`, the K3s adapters behind the provider-neutral `ControlNodeSteps` / node steps ports.  `DefaultK3sAgentService.configure` already writes a kotlinx-serialized YAML file (`K3sAgentConfig`) to the node with `remoteOps.installFile`.  The control node also runs a kubelet (the server's), so the allowlist is needed on all three node types.
- **Cluster-wide steps.** `UpPostSteps.run` applies the StorageClasses and the observability stack once per `up`, through `K8sStorageOperations` and `ObservabilityStackService`.  It runs before any kit can be installed.
- **Kits.** Thirteen packaged kits under `src/main/resources/com/rustyrazorblade/easydblab/kits/`.  None uses `hostNetwork` (kits/CLAUDE.md forbids it).  How each one makes its pods:

| Kit | Pods made by | Where a sysctls block would go per kit |
|---|---|---|
| clickhouse | Altinity operator, `ClickHouseInstallation` + `ClickHouseKeeperInstallation` | two `podTemplates` |
| clickhouse-benchmark | `kubectl run` in `bin/*.sh` (via `edl_pod_overrides`) | the overrides JSON |
| postgres | CloudNativePG operator, `Cluster` | `podSecurityContext` |
| tidb | TiDB operator, `TidbCluster` (PD, TiKV, TiDB) | `podSecurityContext` per component |
| kafka | Strimzi operator, `Kafka` + `KafkaNodePool` | `template.pod.securityContext` |
| neo4j | plain `StatefulSet` | pod spec |
| presto | Helm chart values | chart-specific `securityContext` (coordinator + worker) |
| trino | Helm chart values | chart-specific `securityContext` |
| ignite3 | plain `StatefulSet` + init `Job` | pod spec |
| memcached | plain `Deployment` (two variants) | pod spec, twice |
| flink | Flink operator, `FlinkDeployment` | `podTemplate` |
| ferrosa | plain `StatefulSet` | pod spec |
| sysbench | `kubectl run` in `bin/*.sh` | the overrides JSON |

  The operators themselves (in `kube-system`, `cnpg-system`, `strimzi`, `tidb-admin`, `default`) also run in the pod network.

- **Kubelet sysctl classes, Kubernetes 1.35** (`pkg/kubelet/sysctl/safe_sysctls.go` and `pkg/util/kernel/constants.go` at `v1.35.1`).  The kubelet admits a "safe" sysctl on any pod; any other namespaced sysctl needs `--allowed-unsafe-sysctls`, or the pod fails with `SysctlForbidden`.  The kernel gates below are all met by the Ubuntu 26.04 kernel.

| #1199 key | Per network namespace | Kubelet class on 1.35 |
|---|---|---|
| `net.core.somaxconn` | yes | unsafe |
| `net.ipv4.tcp_max_syn_backlog` | yes | unsafe |
| `net.ipv4.tcp_rmem` | yes | safe (kernel 4.15+) |
| `net.ipv4.tcp_wmem` | yes | safe (kernel 4.15+) |
| `net.ipv4.tcp_keepalive_time` | yes | safe (kernel 4.5+) |
| `net.ipv4.tcp_keepalive_intvl` | yes | safe (kernel 4.5+) |
| `net.ipv4.tcp_keepalive_probes` | yes | safe (kernel 4.5+) |
| `net.ipv4.tcp_slow_start_after_idle` | yes | unsafe |
| `net.ipv4.tcp_tw_reuse` | yes | unsafe |
| `net.ipv4.ip_local_port_range` | yes | safe |
| `net.ipv4.ip_local_reserved_ports` | yes | safe (kernel 3.16+) |
| `net.core.netdev_max_backlog` | no, host only | not settable in a pod |
| `net.core.rmem_max`, `net.core.wmem_max` | no, host only | not settable in a pod |
| `net.core.rps_sock_flow_entries` | no, host only | not settable in a pod |

  The host-only keys already reach pods (pod traffic arrives on the node's interfaces).  The kubelet accepts any `net.*` pattern in its allowlist, but the container runtime fails to start a pod whose sysctl has no entry in the pod's `/proc/sys/net`, so a host-only key must never be injected.

- **Admission policy API.** `MutatingAdmissionPolicy` is alpha in 1.30-1.33, beta and off by default in 1.34-1.35, and GA (on by default, `admissionregistration.k8s.io/v1`) from 1.36.  On K3s 1.35 the API server needs `feature-gates=MutatingAdmissionPolicy=true` and `runtime-config=admissionregistration.k8s.io/v1beta1=true`.  The fabric8 7.8.0 model jar (`kubernetes-model-admissionregistration`) has `v1beta1.MutatingAdmissionPolicy` and `MutatingAdmissionPolicyBinding`.  The kubelet's `defaultPodSysctls` (feature gate `DefaultPodSysctls`) would do this at the node, but it is alpha from 1.37, so it is not available on 1.35.

## Goals / Non-Goals

**Goals:**
- Every kit database pod, and every other pod in the pod network, runs with the #1199 per-namespace network values.
- One source for the values: the packaged host file.  Read from the classpath, so it works from a Homebrew install.
- One mechanism for every kit, current and later, with no per-kit copy.
- The K3s-specific parts (kubelet allowlist, API server flags) stay behind the K3s bootstrap seam.
- Each kit verified on a real cluster.

**Non-Goals:**
- Host-network pods and systemd services.  They are in the host namespace, which #1199 already tunes.
- The host-only keys (`netdev_max_backlog`, `rmem_max`, `wmem_max`, `rps_sock_flow_entries`), memory, file-system and process limits.  #1199 covers them for pods already.
- A second provider.  No `packer/` change, no AMI bake.

## Decisions

### D1. One cluster-wide MutatingAdmissionPolicy injects the sysctls

`up` applies one `MutatingAdmissionPolicy` named `easydblab-pod-network-sysctls` and its binding.  It matches `CREATE` of `pods` in every namespace.  Its match condition excludes host-network pods (`!(has(object.spec.hostNetwork) && object.spec.hostNetwork)`).  The mutation is a `JSONPatch` built with CEL: it adds `spec.securityContext` when the pod has none, and `securityContext.sysctls` when that is absent.  When the pod already lists sysctls, it adds only the keys the pod does not set, so a key a kit sets on purpose keeps the kit's value.  `failurePolicy: Fail`, so a pod is never created untuned in silence.  A `JSONPatch` is used, not `ApplyConfiguration`, because `PodSecurityContext.sysctls` is an atomic list and an apply configuration cannot change one.

The policy and binding are built with fabric8 in a new `configuration/` builder (`PodNetworkSysctlPolicyBuilder`), never as YAML strings, and applied through `K8sManifestOperations.applyResource`.  `UpPostSteps.run` applies it first, before the StorageClasses and the observability stack, so every later pod is covered.

Why not a block per kit (the issue's first direction): it would be about fifteen copies in five operator CRD shapes, two Helm charts with their own value schemas, plain manifests and two `kubectl run` overrides.  Each copy can drift from the host file, and each later kit must remember it.  The policy covers operator-built pods whose spec a kit does not write (the operators' own pods, TiDB's discovery pod, CNPG's join jobs), the client pods of sysbench and clickhouse-benchmark, and every later kit, with nothing in any kit.

Rejected alternatives:
- **A mutating webhook server.**  It needs a pod, a TLS certificate and a Service; the in-API-server policy needs none of them.
- **The CNI `tuning` plugin chained into the CNI config.**  It sets sysctls in each pod namespace without the kubelet allowlist, but the CNI config is owned by Cilium's install (and by K3s for flannel), so it couples the change to the CNI.  K3s's bundled CNI binary may not carry `tuning`, which would need an AMI bake.  It is also not a pod-spec `securityContext.sysctls`, so `kubectl get pod` would not show the values.
- **Kubelet `defaultPodSysctls`.**  Alpha in 1.37; not available on 1.35.

### D2. The values come from the packaged host file, read from the classpath

A new reader, `PodNetworkSysctls`, reads `Constants.NodeTuning.RESOURCE_DIR/SYSCTL_FILE` from the classpath (as `NodeTuningFiles` does), parses its `key = value` lines, and returns the keys named in `Constants.NodeTuning.POD_NETWORK_SYSCTLS` with the file's values.  The values are never repeated in Kotlin, so the host file and the pods cannot drift.  The reader fails, naming the key, when a key in `POD_NETWORK_SYSCTLS` is missing from the file.

`Constants.NodeTuning` gets two name lists:
- `POD_NETWORK_SYSCTLS`: the nine per-namespace keys to inject: the table above less `ip_local_port_range` and `ip_local_reserved_ports` (R1).
- `NOT_INJECTED_NETWORK_SYSCTLS`: `net.core.netdev_max_backlog`, `net.core.rmem_max`, `net.core.wmem_max`, `net.core.rps_sock_flow_entries`, `net.ipv4.ip_local_port_range` and `net.ipv4.ip_local_reserved_ports` (R1).

A unit test parses the packaged file and fails when a `net.*` key in it is in neither list or in both.  A new network key added to the host file therefore fails the build until someone decides whether pods get it.

### D3. The kubelet allowlist is a K3s config drop-in, written from Kotlin

Every node's kubelet gets `allowed-unsafe-sysctls=<every key in POD_NETWORK_SYSCTLS, comma-separated>`.  The allowlist names all injected keys, safe ones too.  The kubelet accepts a safe key in the list, and listing it keeps the pod admitted if a kernel or Kubernetes change ever moves it out of the safe set.

The file is `/etc/rancher/k3s/config.yaml.d/25-kubelet-sysctls.yaml` with `kubelet-arg+:` (adding to the eviction and limits args, as `15-kubelet-limits.yaml` does).  It is a kotlinx-serialized data class (as `K3sAgentConfig` is), not a heredoc, because the key list comes from `PodNetworkSysctls`.  `DefaultK3sService.startServer` and `DefaultK3sAgentService.start` upload it with `remoteOps.installFile` before they run the start script, on every run.  It is written before K3s first starts, so the first kubelet start reads it.  It is K3s-only code in the K3s services, reached only from `bringup/k3s/`.

### D4. The K3s server enables the beta API, in a config drop-in

`start-k3s-server.sh` writes `/etc/rancher/k3s/config.yaml.d/30-admission-policy.yaml` on every run:

```yaml
kube-apiserver-arg+:
  - "feature-gates=MutatingAdmissionPolicy=true"
  - "runtime-config=admissionregistration.k8s.io/v1beta1=true"
```

It is a static file in the script (no shared values), like `20-datastore.yaml`.  The policy builder uses the `v1beta1` model.  When the AMI moves to K3s 1.36 or later, the drop-in goes away and the builder moves to `v1`; the drop-in's comment and `SharedK3s.K3S_IMAGE`'s KDoc say so.

### D5. `up` proves the policy works before it goes on

The API server loads a new policy asynchronously.  After applying it, `up` creates a server-side dry-run pod (`dryRun=All`, pod network, no image pull) and reads the returned spec.  It retries, with resilience4j, until the returned pod carries every value from `PodNetworkSysctls`, for at most `Constants.NodeTuning.POD_SYSCTL_POLICY_READY_SECONDS` (30 s).  Past that, `up` fails with an event naming the missing keys.  The dry run needs no node and no kubelet, and it also proves the beta API is on.  It is checking that the policy is in effect before the first kit pod, not a verify step on data.

### D6. Provider neutrality

The policy, its builder and `PodNetworkSysctls` are provider-neutral Kubernetes objects and code, applied through the existing `K8sManifestOperations` port.  Only D3 (kubelet allowlist) and D4 (API server flags) are K3s-specific, and they are in the K3s services the `bringup/k3s/` adapters call.  On an existing cluster (EKS and the like), the tool creates neither the control plane nor the nodes: the policy needs Kubernetes 1.36+ (or the beta API on), and the nodes' kubelets need the allowlist from that platform's node config.  This change builds neither; it only keeps the split so that one can.

### D7. Docs

- `docs/development/kits.md`: a "Pod network tuning" section.  A kit's pods get the values automatically; a kit does not add its own sysctls block for these keys; a kit pod that sets one of them keeps its own value; a kit never uses `hostNetwork` (unchanged).  It names the policy and how to read the values in a pod.
- `src/main/resources/com/rustyrazorblade/easydblab/kits/CLAUDE.md`: one bullet with the same rule.
- `docs/user-guide/node-tuning.md`: the #1202 follow-up note becomes a statement that pods in the pod network get the per-namespace values, and how to check them.
- `docs/user-guide/platform-substrate.md`: the policy as part of the substrate `up` provisions.
- `configuration/CLAUDE.md` and `services/CLAUDE.md`: the builder, the reader and the drop-in.

## Real-cluster verification plan

One cluster: 3 db, 2 app, 1 control (`r`-class or `i`-class db with NVMe, as the kit guides require), tag `embark_offhours=off`, default profile.  Kits are installed, checked, then stopped one after another, so no two contend for ports or disks.

The value check is the same for every pod, so it works on images without `sysctl` or `cat`: on the pod's node, read the values inside the pod's network namespace with `nsenter -t <pid> -n sysctl <keys>`, where the PID is the pod sandbox's from `crictl inspect`.  Also `kubectl get pod -o jsonpath='{.spec.securityContext.sysctls}'`.  Every pod of the kit must report every value in `PodNetworkSysctls`, and the kernel default `ip_local_port_range` (`32768 60999`) with no reserved ports (R1).

| Kit | Pods checked | Workload |
|---|---|---|
| clickhouse | every ClickHouse and Keeper pod | `clickhouse sql "SELECT 1"`, then clickhouse-benchmark below |
| clickhouse-benchmark | the prepare and run pods | `prepare` (ClickBench, small source) and `start` |
| postgres | every CNPG instance pod | sysbench `prepare` + `start` with `--target postgres` |
| tidb | PD, TiKV, TiDB pods | sysbench `prepare` + `start` with `--target tidb` |
| sysbench | the prepare and run pods | the two runs above |
| kafka | every broker / controller pod | `producer-perf` then `consumer-perf` |
| neo4j | the Neo4j pod | a Cypher write and read over the HTTP endpoint |
| presto | coordinator and workers | `presto sql "SELECT count(*) FROM system.runtime.nodes"` |
| trino | coordinator and workers | `trino sql "SELECT count(*) FROM system.runtime.nodes"` |
| ignite3 | every Ignite pod | `ignite3 sql` create, insert, select |
| memcached | the memcached pod, RAM and extstore variants | set and get from another pod over the endpoint |
| flink | JobManager and TaskManagers | submit the bundled streaming example over REST from a pod; it reaches RUNNING |
| ferrosa | every FerrosaDB pod | `cassandra stress start -- KeyValue -d 2m --host ferrosa-0.default.svc.cluster.local` |

Also checked once: a host-network pod (the OTel collector) has no injected `sysctls` and still reads the host values; an operator pod (for example the CNPG operator in `cnpg-system`) has the values (R2); `up` on a fresh cluster reports the policy ready.  The PR carries the commands and their output.

## Risks / Trade-offs

- [A beta API on K3s 1.35] → it has the same shape in GA `v1`; D4 is the only K3s flag, and the move to 1.36 is a documented two-line change.
- [`failurePolicy: Fail` blocks every pod creation if the policy's CEL errors] → the K3s integration test creates pods through the real policy; `up` proves it with D5.
- [A namespace that enforces Pod Security `baseline` or `restricted` rejects the unsafe sysctls] → no namespace in the cluster sets Pod Security labels today; the kit checks above would show one.
- [Pods created before the policy (CoreDNS, metrics-server at K3s start) are untuned] → they are not under test.  The policy is applied before the observability stack and before any kit.
- [A cluster brought up by an older CLI has no kubelet allowlist; kit pods there fail `SysctlForbidden`] → clusters are ephemeral; such a cluster is destroyed and brought up again (`down`, then `up`).  A second `up` on the live cluster rewrites the drop-ins, but the start scripts only `systemctl start` a K3s that is already running, so the new flags take effect on a fresh cluster only.  The failure names the sysctl.
- [The policy also touches system and operator pods] → they get the same values a tuned production host gives every process; owner's decision R2.

## Resolved (owner decisions, 2026-11-19)

**R1. Pods keep the kernel defaults for `ip_local_port_range` and `ip_local_reserved_ports`.**  Both are in `NOT_INJECTED_NETWORK_SYSCTLS`, not injected.  The host's reserved list names host ports, not a pod's own listener ports, so the host's wide 1024-65535 range in a pod would let a database's outbound connection take its own listener port; the default 32768-60999 range sits above every kit's listener port.

**R2. Every pod in the pod network gets the values, not only kit pods.**  Operator-built pods carry the operator's labels, not the kit's, so a kit-label match would miss them; every pod also matches a tuned production host, where every process gets these values.

**R3. One cluster-wide MutatingAdmissionPolicy (D1), not a sysctls block per kit.**  It is one place that reads the host file and covers later kits with nothing to remember, where per-kit blocks would be about fifteen copies in eight shapes; the cost, the beta API flags on K3s 1.35 (D4), is accepted.
