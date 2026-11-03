# CNI Acceptance Results — Flannel vs Cilium (#805)

Acceptance evidence for PR #820 (Cilium ENI native routing as opt-in `--cni=cilium`).
Two sequential clusters, one per CNI, every DB kit installed/started/smoked on each.

- **AMI under test:** `ami-02ae4ff652cb9f6a2` (easy-db-lab-cassandra-amd64-20260720224307), built on drop-in base `ami-0ae588367df58a80b` (the `configure_cilium_eni_networkd.sh` step ran + completed in the base build; cassandra 6.0-HEAD download = 200).
- **Binary:** worktree tip `d34048d1` (PR #820 branch, merged with main #822).
- **Topology (both phases):** 1 control + 3 db + 3 app, `--azs abc`, i4i.2xlarge db+app. (App tier required: presto/trino/tidb-server+pd/flink pin to `nodeSelector: type: app`.)
- **AWS:** profile sandbox-admin, us-west-2.

---

## Side-by-side PASS/FAIL matrix

| Check | Flannel (Phase 1) | Cilium (Phase 2) |
|---|---|---|
| `up` completes, exit 0 | PASS | TBD |
| All 7 nodes Ready | PASS (control0 + db0/1/2 + app0/1/2, AZs a/b/c) | TBD |
| CNI/config assertion | PASS (Flannel, no cilium pods) | TBD (native/eni/kpr=false/egress ens+/host-legacy/no bpf-masq) |
| ENI handling | PASS (every node single-ENI, no ens6 — drop-in inert) | TBD (obs node gets ens6, Unmanaged, single default via ens5, IMDS ok) |
| Node SSH + apiserver reachable | PASS (SSH all nodes; kubectl via control0) | TBD (incl host→remote-pod-IP) |
| Cross-node/AZ pod→pod | PASS (implicit: multi-node kits inter-communicate) | TBD (by pod IP, no tunnel) |
| pod→ClusterIP (CoreDNS) | PASS (SQL via ClusterIP/NodePort + CoreDNS resolves) | TBD |
| pod→internet egress | PASS (operators pulled charts/images) | TBD |
| Hubble flows | n/a | TBD |
| Obs stack Ready (incl pyroscope-ebpf all nodes) | PASS (pyroscope-ebpf 1/1 x7) | TBD |
| cassandra (install/start/smoke) | PASS (3-node ring UN a/b/c; CQL write+read) | TBD |
| clickhouse | PASS (`sql SELECT 1`) | TBD |
| postgres | PASS (`sql SELECT 1`) | TBD |
| tidb | FAIL — CNI-independent kit bug (charts.pingcap.org NXDOMAIN, dead helm repo) | TBD |
| ignite3 | PASS (`sql SELECT 1`) | TBD |
| presto | PASS (`sql SELECT 1`) | TBD |
| trino | FAIL — CNI-independent kit bug (workers crashloop on rejected config `web-ui.authentication.type`) | TBD |
| kafka | PASS (topic create + list) | TBD |
| flink | PASS (session cluster REST /overview) | TBD |
| sysbench | PASS (vs postgres: prepare + oltp_read_write, 127 tps / 2551 qps / 1907 txns in 15s) | TBD |
| Cross-AZ replicated read | PASS (cassandra RF=3 NetworkTopologyStrategy, write AZ-a, read CONSISTENCY ALL) | TBD |
| `down` clean (0 EIPs, VPC gone) | TBD | TBD |

**CNI-independent kit findings (fail on any CNI; not #805 regressions):**
- **tidb** — kit's helm repo `charts.pingcap.org` returns NXDOMAIN (retired by PingCAP) from control0 AND laptop; install fails at HelmRepo step, no pods created. Kit needs URL update.
- **trino** — all workers CrashLoopBackOff (exit 100): Trino rejects config properties `web-ui.authentication.type` / `web-ui.user`. Kit config/version mismatch.

## Phase 2 — Cilium (native routing) — result

**`up` FAILED at GrafanaUpdateConfig** (obs-readiness gate) — but NOT at the pre-drop-in failure. Root-caused to a Cilium `kubeProxyReplacement=false` datapath gap. Workspace: clusters/cni-cilium-retry-20260720-171324 (left UP as evidence). (First attempt died earlier on a transient EC2 DescribeInstances-NotFound eventual-consistency flake — infra retry gap, not CNI; clean retry cleared it.)

Cilium-column matrix status:
- up exit 0: **FAIL** (GrafanaUpdateConfig / obs stack not ready).
- All 7 nodes Ready: **PASS** (control0 + db0/1/2 + app0/1/2).
- Cilium config: native routing / ipam eni / kpr=false confirmed (the deliberate config).
- ENI handling: **PARTIAL** — control0 (busy node) got secondary ENI ens6, networkd marks it `unmanaged` (drop-in applied) AND IMDS works AND host egress works; BUT a competing `proto dhcp` default route on ens6 (metric 1026) persists (cloud-init netplan-hotplug re-DHCPs the hot-attached ENI; `Unmanaged=yes` doesn't stop it). ens5's lower metric (1024) keeps node-status/egress working, so this does NOT block the up — but the drop-in isn't fully doing its job on hot-attach (hardening item).
- pyroscope-ebpf 1/1 all nodes: **PASS** — the exact pod that failed pre-drop-in is fixed. (Drop-in's node-status fix validated.)
- Obs stack Ready: **FAIL** — tempo, pyroscope(server), s3manager, grafana crashloop.
- Kits: **NOT RUN** (up gated before the kit matrix).

**ROOT CAUSE (#805 crux):** hostNetwork pods cannot reach ClusterIP services under Cilium `kubeProxyReplacement=false`. The crashlooping obs backends are hostNetwork pods (dnsPolicy ClusterFirstWithHostNet) that resolve DNS via the CoreDNS ClusterIP `10.43.0.10:53`. That write is rejected with EPERM. Verified directly: `echo >/dev/udp/10.43.0.10/53` → "Operation not permitted" from control0 AND db0 (single-ENI) hosts; `dig @10.43.0.10` → connection refused. Tempo's own log: `lookup <bucket>.s3.us-west-2.amazonaws.com on 10.43.0.10:53: write udp ...->10.43.0.10:53: write: operation not permitted` → can't init its S3 store → exit 1. s3manager's /buckets S3 call needs ClusterIP DNS → 1s liveness timeout → SIGTERM(143). Discriminator: obs pods NOT needing ClusterIP DNS at startup (victoriametrics, victorialogs, registry, pyroscope-ebpf) are all Running 0 restarts. Host→S3/IMDS egress is fine (host→S3 307/16ms; `aws s3api list-buckets` 0.8s on both single- and multi-ENI nodes — inherent S3 latency, not CNI). On Flannel, k3s kube-proxy services host→ClusterIP so these same pods were healthy. Ties to design.md Decision 5 (kpr pinned OFF). Fix direction (owner's call): enable kubeProxyReplacement (Variant B: kpr=true + --disable-kube-proxy + hostLegacyRouting, no bpf-masquerade) or another host→ClusterIP fix — pending owner decision.

---

## Phase 1 — Flannel run journal

(filled during run)

## Phase 2 — Cilium run journal

(filled during run)
