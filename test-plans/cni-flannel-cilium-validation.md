# CNI Acceptance Test — Flannel vs Cilium (every DB kit, both CNIs)

Final acceptance gate for **#805** (Cilium ENI native routing as opt-in `--cni=cilium`).
Proves BOTH datapaths work end-to-end on the **rebuilt AMI** (with the `Unmanaged=yes`
secondary-ENI drop-in baked in): `--cni=flannel` (default, must not regress) and
`--cni=cilium` (native routing). Runs **sequentially**, one full cluster per CNI, with
**every available database kit** installed on each — the real "make sure everything works" bar.

## Preconditions
- **Rebuilt AMI** containing the `05-/06-` secondary-ENI networkd drop-ins (packer change from PR #820, `dev-805-ami`). Do NOT run before the AMI is rebuilt.
- PR #820 branch binary (`--cni` option present, Variant A Cilium config, IMDS hop-limit-2 for all nodes).
- AWS profile `sandbox-admin`, region us-west-2. privateIp for all in-cluster service calls.
- Topology: **1 control + 3 db + 3 app, across 3 AZs (`--azs abc`)**, all **i4i.2xlarge** (tune up if pods stay Pending). **App nodes are required**: presto, trino, tidb (tidb-server + pd), and flink hard-pin their pods to `nodeSelector: {type: app}`, so a db-only topology would leave them Pending for a topology reason unrelated to the CNI. 3 db + 3 app across the 3 AZs also gives genuine cross-AZ spread for both tiers. (≥2 nodes-per-tier in different AZs is the hard requirement; 3 gives headroom + 3-AZ coverage.)
- Cluster workspace + `$EDB` wrapper per CLAUDE.md (`clusters/<name>`, `bin/create-easy-db-lab-wrapper`).

## Kit set to validate (authoritative list from `$EDB kit list` at run time)
Install/start EVERY db kit the build ships. Known set (confirm via `kit list`):
`clickhouse`, `postgres`, `tidb`, `ignite3`, `presto`, `trino`, `kafka`, `flink`, plus
built-in `cassandra`, and `sysbench` as a SQL workload driver (targets postgres/tidb/etc.).
Add any others `kit list` reports (e.g. `mysql` if merged). If a kit legitimately can't
co-reside (hard resource/port conflict), note it as a finding — multi-workload is a core goal.

---

## PHASE 1 — `--cni=flannel` (default; regression proof)

### Steps
1. **Provision:** `$EDB init --azs abc --db-instance i4i.2xlarge` (default CNI = flannel; or explicit `--cni=flannel`), 1 control + 3 db; `$EDB up`.
2. **`up` completes with NO error**, exit 0. All 4 nodes `Ready` (`kubectl get nodes -o wide`).
3. **Confirm Flannel + AMI-drop-in inert:** CNI is Flannel (no cilium pods); **every node single-ENI** (`aws ec2 describe-instances` → 1 ENI each; no `ens6`). This proves the baked-in `06-cilium-eni-unmanaged.network` matches nothing on Flannel and does not affect the node.
4. **Core networking:** cross-node pod→pod; pod→ClusterIP (CoreDNS resolves `kubernetes.default`); pod→internet egress (curl an external URL).
5. **Observability stack fully Ready** — grafana, pyroscope-ebpf (all nodes), tempo, victoriametrics, otel-collector, beyla, ebpf-exporter, fluent-bit. Confirms the baked-in drop-in didn't break the obs stack on Flannel.
6. **Every DB kit:** for each kit in `kit list`: `$EDB kit install <k>` → `$EDB <k> start` → wait Ready → **smoke op** (a write+read / query appropriate to the kit; for SQL kits a `SELECT 1` + a tiny insert/select; for cassandra a keyspace+table+write+read via `nodetool`/cqlsh). Record Ready + smoke PASS/FAIL per kit.
7. **Cross-AZ data proof:** at least one replicated store (cassandra RF≥2 or a multi-node kit) writes on one AZ and reads back — exercises cross-AZ pod↔pod as real DB traffic.
8. **`$EDB status`** healthy; Grafana reachable from laptop; dashboards show live metrics for the running kits.
9. **Teardown:** `$EDB down --auto-approve`. Verify: all instances terminated, VPC deleted, **zero Elastic IPs** (`describe-addresses` empty), no orphaned volumes.

---

## PHASE 2 — `--cni=cilium` (native routing)

### Steps
1. **Provision:** `$EDB init --cni=cilium --azs abc --db-instance i4i.2xlarge`, 1 control + 3 db; `$EDB up`.
2. **`up` completes with NO error**, exit 0 (the whole point — the prior GrafanaUpdateConfig failures are gone). All 4 nodes `Ready`. **Host SSH + apiserver reachable on every node after full convergence** (the reachability proof — including HOST→remote-pod-IP, which was flaky on the surgeried cluster; want a clean read here).
3. **Cilium config (`kubectl -n kube-system get cm cilium-config -o yaml`):** `routing-mode: native`, `ipam: eni`, `kube-proxy-replacement: "false"`, `egress-masquerade-interfaces: ens+`, `enable-host-legacy-routing: "true"`, **NO** `enable-bpf-masquerade`, **NO** vxlan/tunnel.
4. **Secondary-ENI handling (the fix):** the control/obs node scales past 7 pods → gets `ens6`; confirm `ip link show ens6` is UP and **Unmanaged** (Cilium-owned), the node has a **single default route via ens5**, IMDS returns instance-id, and `ens6` has NO competing default route.
5. **ENI IPAM:** `kubectl get ciliumnode -o yaml` shows ENIs + allocated IPs per node; each pod IP falls in its node's AZ subnet CIDR.
6. **Datapath (curl/nc, never ping — SG has no ICMP):** cross-AZ pod→pod by pod IP (no tunnel); pod→ClusterIP (CoreDNS); pod→internet egress; **host→pod reachability**; `hubble status`/`hubble observe` shows flows; Hubble UI reachable.
7. **Observability stack fully Ready** incl. **pyroscope-ebpf 1/1 on all nodes** (the exact pod the fix recovers).
8. **Every DB kit:** identical matrix to Phase 1 step 6 — install/start/smoke every kit; record per-kit PASS/FAIL. This is the core "everything works under Cilium native routing" proof.
9. **Cross-AZ data proof:** same replicated-store write→read across AZs — now over native routing.
10. **`$EDB status`** healthy; Grafana + dashboards live.
11. **Teardown:** `$EDB down --auto-approve`. Verify: instances terminated, VPC deleted, **zero Elastic IPs**, no orphaned volumes.

---

## Deliverable
A side-by-side PASS/FAIL matrix (Flannel vs Cilium) covering: `up` success, node readiness,
CNI/config assertions, ENI handling (Cilium), the full datapath matrix, obs stack, and
**every DB kit (install/start/smoke)** — plus the cross-AZ data proof and clean teardown/leak
checks for both. Written to `test-plans/cni-flannel-cilium-validation-results.md` + a run journal.

## Merge gate
This test produces evidence **for the owner's review only**. PR #820 is NOT merged on a green
result — the owner reviews the results and explicitly approves the merge (Seam 2). No auto-merge.
