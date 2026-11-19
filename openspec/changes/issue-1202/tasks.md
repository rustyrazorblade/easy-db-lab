## 1. Confirm on a real cluster (before code)

- [x] 1.1 From `main`, provision a 1 db, 1 app, 1 control cluster (default profile, `embark_offhours=off`); record its name and workspace in the state file.
- [x] 1.2 Install and start one kit (memcached); read the nine keys and the port range inside its pod's network namespace (`nsenter -n`); record the defaults.
- [x] 1.3 On the control node, add the D4 API server drop-in and the D3 kubelet drop-in by hand, restart K3s, apply a hand-written policy, and confirm a dry-run pod carries the sysctls and a real memcached pod starts with them.  Record any CEL or flag correction in the design.
- [x] 1.4 `down` the cluster.

  Section 1 was folded into the K3s TestContainers test (4.3, which starts K3s with both drop-ins and a real pod) and the branch cluster run (section 7); no CEL or flag correction came out of either beyond the plain-map `desired` variable recorded in the design.

## 2. Shared values

- [x] 2.1 `Constants.NodeTuning`: `POD_NETWORK_SYSCTLS`, `NOT_INJECTED_NETWORK_SYSCTLS`, `POD_SYSCTL_POLICY_NAME`, `POD_SYSCTL_POLICY_READY_SECONDS`.
- [x] 2.2 `PodNetworkSysctls`: reads the packaged `60-easydblab.conf` from the classpath and returns the injected keys with their values; fails naming a missing key.
- [x] 2.3 Unit tests: every `net.*` key in the file is in exactly one list; the reader returns the file's values; a missing key fails with its name.

## 3. K3s seam

- [x] 3.1 Kubelet drop-in data class (`kubelet-arg+`) serialized with kotlinx.serialization; `DefaultK3sService.startServer` and `DefaultK3sAgentService.start` install `25-kubelet-sysctls.yaml` before the start script.
- [x] 3.2 `start-k3s-server.sh`: write `30-admission-policy.yaml` (feature gate and runtime-config) on every run, with a comment saying it goes when the AMI reaches K3s 1.36.
- [x] 3.3 Unit tests: the drop-in lists every injected key; it is installed before the start script on server and agent (argument captor on `RemoteOperationsService`).
- [x] 3.4 Update `StartK3sScriptsIntegrationTest` for the new server drop-in; update the `SharedK3s.K3S_IMAGE` KDoc and K3s TestContainers flags so the integration cluster enables the same API.

## 4. Admission policy

- [x] 4.1 `PodNetworkSysctlPolicyBuilder` (fabric8 `v1beta1` `MutatingAdmissionPolicy` + binding): match pod `CREATE`, skip host-network pods, JSONPatch that adds missing `securityContext`, missing `sysctls`, and each missing key; `failurePolicy: Fail`.
- [x] 4.2 `UpPostSteps.run`: apply it first; dry-run pod check with a resilience4j retry up to 30 s; typed event and failure naming missing keys.
- [x] 4.3 Integration test (K3s TestContainers, API flags on): a plain pod gets every value; a pod with `tcp_keepalive_time=300` keeps it; a host-network pod gets none; a pod with an existing `securityContext` keeps its other fields.
- [x] 4.4 Unit test for the `up` check: a dry-run result missing a key fails naming it.

## 5. Docs

- [x] 5.1 `docs/development/kits.md`: "Pod network tuning" section.
- [x] 5.2 `src/main/resources/com/rustyrazorblade/easydblab/kits/CLAUDE.md`: the rule bullet.
- [x] 5.3 `docs/user-guide/node-tuning.md`: replace the #1202 follow-up note; how to read the values in a pod.
- [x] 5.4 `docs/user-guide/platform-substrate.md`: the policy as substrate.
- [x] 5.5 `configuration/CLAUDE.md`, `services/CLAUDE.md`: the builder, the reader, the drop-ins.

## 6. Gates

- [x] 6.1 `./gradlew ktlintFormat`, then `./gradlew check` on JDK 21 (via a Sonnet build-engineer).

## 7. Real-cluster verification (design table)

- [x] 7.1 Provision 3 db, 2 app, 1 control from the branch build; record it in the state file.
- [x] 7.2 `up` reports the policy ready; the OTel collector pod has no injected sysctls; an operator pod has the values (R2); every kit pod reports the kernel default port range and no reserved ports (R1).
- [x] 7.3 For each kit in the design table (clickhouse, clickhouse-benchmark, postgres, tidb, sysbench, kafka, neo4j, presto, trino, ignite3, memcached RAM and extstore, flink, ferrosa): install, start, read the values in every pod, run the workload, stop.  Record commands and output.
- [x] 7.4 `down`; put the evidence in the PR.
