## Why

The cluster runs Cilium purely to get eBPF's low-overhead datapath — but `CiliumService` installs it in **VXLAN tunnel mode** (`tunnelProtocol=vxlan` + `routingMode=tunnel`), which re-encapsulates every packet and throws away the performance that is the entire reason for using Cilium. VXLAN was chosen originally only to sidestep a worker-bootstrap deadlock (Cilium's eBPF intercepting the K3s agent's TCP to the API server during join).

On AWS, the multi-AZ-safe way to run Cilium with **no encapsulation** is **ENI IPAM mode**: pods get real VPC-routable secondary IPs on the node's ENIs, so cross-AZ pod traffic is routed by the VPC itself — no tunnel, no `autoDirectNodeRoutes` (which only works within one L2 domain and breaks across AZs). This change lands Cilium ENI native routing as a correct, **opt-in** datapath selected with `--cni=cilium`; the default stays **Flannel** to de-risk the rollout. Flipping the default to Cilium once it is proven on live clusters is the deliberately-separate follow-up **#819**.

Two blocking bugs must be fixed for this to work at all:
- **The `--cilium` path is currently broken end-to-end.** `hubble.metrics.enabled={dns,drop,...}` is passed **unquoted** to a remote shell, so bash brace-expands it into seven separate tokens and `cilium install` never receives a valid value. This has meant `--cilium` never installed successfully since it landed — it went unnoticed because Cilium is opt-in and Flannel is the default.
- **The cilium-operator can deadlock on IMDS.** The IMDS hop-limit is raised to 2 only for the control node; db/app nodes keep the AWS default of 1, so the (non-hostNetwork) cilium-operator pod cannot reach IMDS if it schedules on a db node → no credentials → no ENIs → **no pod on the cluster gets an IP**.

## What Changes

- `CiliumService.install()` gains a `vpcCidr` parameter and installs Cilium **1.19.4** in ENI IPAM native-routing mode: `ipam.mode=eni`, `eni.enabled=true`, `routingMode=native`, `endpointRoutes.enabled=true`, `enableIPv4Masquerade=true`, `egressMasqueradeInterfaces=ens+`, `kubeProxyReplacement=false`, `bpf.hostLegacyRouting=true`, `ipv4NativeRoutingCIDR=<vpcCidr>`, `k8sServiceHost=<control private IP>`, `k8sServicePort=6443`, `operator.replicas=1`, Hubble relay/ui/metrics. VXLAN/tunnel flags removed. A LIVE AWS test corrected the flag set: ENI mode requires a **named** egress masquerade interface (`egressMasqueradeInterfaces=ens+`, iptables masquerade over the Nitro primary NIC `ens5` + ENI secondaries) or the agent panics; `kubeProxyReplacement` is pinned **false** and `bpf.hostLegacyRouting=true`, and `bpf.masquerade` is **not** set, avoiding the BPF-host-routing blackhole of the node's own SSH:22 / apiserver:6443 (cilium/cilium#46010). The Hubble metrics brace list is **single-quoted** to fix the expansion bug.
- The VPC CIDR is read from `workingState.initConfig?.cidr` (resolved and persisted before node setup) at the `Up.installCilium()` call site and passed to `install()`.
- IMDS `metadataOptions` (hop-limit 2, IMDSv2 required) is applied to **all** node types, not just the control node.
- A new `--cni=<cilium|flannel>` init option (default `flannel`) replaces the boolean `--cilium` flag. It is backed by a `CniMode` enum persisted in `InitConfig`, is mutually exclusive by construction, and is extensible to future CNIs. `--cni=cilium` selects Cilium ENI native routing; `--cni=flannel` (the default) selects K3s's built-in Flannel. The default-to-Cilium flip is deferred to #819.
- `kubeProxyReplacement` is now set to **false explicitly** (previously intended off but never enforced) — K3s's kube-proxy continues to service Services. This isolates the ENI datapath change from a second large variable, moots the historical agent→API bootstrap-interception concern, and — critically — avoids the BPF-host-routing blackhole of node SSH/API that the unpinned default caused on the live test (cilium/cilium#46010).
- Docs corrected: the observability CLAUDE.md line "K3s uses Cilium (not Flannel)" is currently false (Flannel is and remains the default this patch); it is rewritten to describe reality — Flannel is the default datapath, and Cilium ENI native routing is selectable via `--cni=cilium`.

## Capabilities

### Modified Capabilities

- `networking`: adds a pod-network datapath requirement — Cilium ENI IPAM native routing (no encapsulation) is a supported datapath selectable via `--cni=cilium`; Flannel remains the default (`--cni=flannel`). REQ-NET-005 (SOCKS invariant) is untouched.

## Impact

- `services/CiliumService.kt` — `install()` signature + ENI native `--set` flags + Hubble brace-quote fix + rationale comment
- `commands/Up.kt` — `installCilium()` reads the resolved VPC CIDR and passes it; CNI gating reads the `CniMode` field
- `commands/Init.kt` — `--cilium` boolean replaced by `--cni=<cilium|flannel>` (default `flannel`)
- `configuration/ClusterState.kt` — new `CniMode` enum; `InitConfig.ciliumEnabled: Boolean` replaced by `InitConfig.cni: CniMode = CniMode.Cilium`; `InitConfig.fromInit` maps from `--cni`
- `services/aws/EC2InstanceService.kt` — IMDS `metadataOptions` applied to all node types (hop-limit 2)
- `src/test/.../services/CiliumServiceTest.kt` — assert ENI native flags, absence of VXLAN, single-quoted Hubble metrics, CIDR threading
- `src/test/.../services/aws/EC2InstanceServiceTest.kt` — new: assert hop-limit 2 / IMDSv2 required for db, app, and control specs
- `src/test/.../configuration/` (or Init/UpTest) — `fromInit` default `cilium`, `--cni=flannel` mapping
- `openspec/specs/networking/spec.md` — add REQ-NET-007
- `CLAUDE.md` — observability CNI line
- **No change** (verified): `providers/aws/AWSPolicy.kt` (already grants `ec2:*`, a superset of ENI actions), `configuration/InfrastructureConfig.kt` (already opens all intra-VPC TCP/UDP — note: no ICMP, so live tests use curl/nc not ping), K3s launch scripts (flannel already disabled under `useCustomCni`), the AMI.
