## Context

Cilium is installed by `DefaultCiliumService.install(controlHost: Host)` (`services/CiliumService.kt:11,23`), which shells `cilium install --set …` on the control node via `remoteOps.executeRemotely`. It currently runs Cilium 1.19.4 in VXLAN tunnel mode. The install is gated behind `--cilium` (opt-in; Flannel is the default). The goal is to flip the default to Cilium **ENI IPAM native routing** (no encapsulation), the only multi-AZ-safe no-encap datapath on AWS.

The design below was verified against the worktree code; three research assumptions were corrected (noted inline).

## Goals / Non-Goals

**Goals**
- Default CNI is Cilium ENI native routing; pods get VPC-routable IPs; cross-AZ pod-to-pod works with no tunnel.
- Fix the two blocking bugs (Hubble brace expansion; IMDS hop-limit on worker nodes).
- Keep Flannel selectable as a first-class fallback via `--cni=flannel`.

**Non-Goals**
- Enabling `kubeProxyReplacement` (kept OFF this cut — deliberate scope boundary).
- Any IAM / security-group / AMI / K3s-launch change (all verified already sufficient).
- Backwards compatibility for old state files beyond Jackson's lenient default handling (clusters are ephemeral).

## Decisions

### Decision 1 — CNI selection: `--cni=<cilium|flannel>` enum option, default `flannel` (OWNER-DECIDED)

The owner chose a single enum-valued option over a boolean flag. `--cni=cilium|flannel`, **default `flannel`**:
- Mutually exclusive by construction (one value), self-documenting, and extensible to future CNIs (e.g. Calico) with no new flags.
- PicoCLI validates the value natively against the `CniMode` enum (invalid value → error listing the allowed set).
- **Default stays `flannel` this patch (owner decision).** This patch lands Cilium ENI native routing as a correct, opt-in datapath (`--cni=cilium`); the default remains Flannel until native routing is proven on live multi-AZ clusters. Flipping the default to `cilium` is the separate, stabilization-gated follow-up **#819**.

Backed by:
```kotlin
enum class CniMode { Cilium, Flannel }
```
`InitConfig.ciliumEnabled: Boolean` (`ClusterState.kt:111`) is **replaced** by `InitConfig.cni: CniMode = CniMode.Flannel`. `useCustomCni = (cni == CniMode.Cilium)`. Jackson serializes the enum by name; the default applies to older/short state files. `InitConfig.fromInit` (`ClusterState.kt:167`) maps from the `--cni` value. Rejected: keep-and-invert the boolean (`--cilium` default true) — awkward UX (Flannel = `--no-cilium`), not extensible.

### Decision 2 — Thread the VPC CIDR from state, not through the call stack (CORRECTED)

Research assumed a top-level `ClusterState.cidr`; there is none. The CIDR is `InitConfig.cidr: String?` (`ClusterState.kt:110`), resolved during `up` (`Up.resolveCidr()`, `:237-243`) and **persisted back into `workingState.initConfig`** (`Up.kt:307-310`) before node setup. So at `installCilium()` time it is reliably `workingState.initConfig?.cidr` (non-null after `provisionInfrastructure`). `CiliumService.install()` gains a `vpcCidr: String` parameter; `Up.installCilium()` reads it from state and passes it — no param threaded down through `setupInstancesIfNeeded`.

```kotlin
// CiliumService
fun install(controlHost: Host, vpcCidr: String): Result<Unit>

// Up.installCilium()
val vpcCidr = requireNotNull(workingState.initConfig?.cidr) {
    "VPC CIDR must be resolved before Cilium install"
}
ciliumService.install(controlHost.toHost(), vpcCidr).getOrThrow()
```

### Decision 3 — ENI native `--set` flag set (Cilium 1.19.4) — Variant A, corrected after live test

Replace the current VXLAN `--set` block with:
`ipam.mode=eni`, `eni.enabled=true`, `routingMode=native`, `endpointRoutes.enabled=true`, `enableIPv4Masquerade=true`, `egressMasqueradeInterfaces=ens+`, `devices=ens+`, `kubeProxyReplacement=false`, `bpf.hostLegacyRouting=true`, `ipv4NativeRoutingCIDR=$vpcCidr`, `k8sServiceHost=${controlHost.private}`, `k8sServicePort=6443`, `operator.replicas=1`, `hubble.relay.enabled=true`, `hubble.ui.enabled=true`, `hubble.metrics.enabled='{dns,drop,tcp,flow,port-distribution,icmp,http}'`, `prometheus.enabled=true`, `operator.prometheus.enabled=true`, `hubble.ui.service.type=NodePort`, `hubble.ui.service.nodePort=31234`.

- **Drop** `tunnelProtocol=vxlan` and `routingMode=tunnel`.
- **Do NOT** set `autoDirectNodeRoutes` — it only routes within a single L2/subnet and breaks cross-AZ; unnecessary in ENI mode because the VPC routes pod IPs.
- **Set `egressMasqueradeInterfaces=ens+`** — a LIVE AWS test proved the earlier "do NOT set it" position wrong: in ENI mode Cilium requires a **named** egress masquerade interface, and with it empty the agent **panics** (`Egress masquerading interfaces cannot be empty…`). The `ens+` wildcard covers the Nitro primary NIC (`ens5`) plus ENI secondaries — correct on Nitro (do not hardcode `eth0`), and masquerading is done via **iptables**, not BPF. `+` is not a shell metacharacter, so it renders unquoted safely over the SSH command.
- **Set `kubeProxyReplacement=false` explicitly** — see Decision 5. The prior config never pinned it, which effectively enabled BPF masquerade / BPF host routing on the primary NIC and **blackholed the node's own inbound SSH:22 and kube-apiserver:6443** (confirmed by cilium/cilium#46010 on 1.19.4). This was the second failure the live test hit.
- **Set `bpf.hostLegacyRouting=true`** — keep host networking on the kernel stack so node SSH / API-server reachability is preserved.
- **MUST NOT set `bpf.masquerade`** — enabling BPF masquerade on the Nitro primary NIC is precisely what broke host networking. Masquerade is handled by iptables via `egressMasqueradeInterfaces`.
- **Single-quote** the Hubble metrics brace list — the current unquoted form is brace-expanded by the remote shell and breaks `cilium install`.
- **Set `devices=ens+`** — a live run showed `job-mtu-updater: MTU updated (1280)` with `ens5`/`ens6` at MTU 1280 instead of the 9001 AWS offers. Device auto-detection had picked up `tailscale0` (MTU 1280) and Cilium took the minimum across devices. Pinning the devices to the ENA interfaces keeps the tailnet interface out of the datapath and the NIC MTU intact.
- **K3s side: `--disable=traefik,servicelb` on the Cilium branch of `start-k3s-server.sh`** — ServiceLB (klipper) publishes every node InternalIP as the `traefik` LoadBalancer ingress IP. Cilium 1.19 installs a port-0 wildcard per LoadBalancer IP (`cilium-dbg bpf lb list`: `<node ip>:0/ANY [LoadBalancer, non-routable]`) and, with `ServiceNoBackendResponse: reject`, answers any pod packet to a node IP on a non-service port with ICMP port-unreachable — including the SYN-ACK of every kubelet→pod probe, so nothing in the pod network became Ready. Deleting the traefik HelmChart on the live cluster fixed it at once. The Flannel branch is unchanged.
- **Tailscale masquerade ahead of Cilium NAT** — on the control node (the tailnet subnet router) `CILIUM_POST_nat` carries `-o ens+ -m set --match-set cilium_node_set_v4 dst -j ACCEPT`, and POSTROUTING jumps to it before `ts-postrouting`, so a tailnet packet to a db node is never masqueraded and its reply has no route back: control0 works from the operator's machine, db nodes time out. Cilium re-inserts its feeder rule at position 1 on every restart, so iptables ordering cannot fix it. Live-proven fix: an nftables NAT chain at priority 90 (ahead of iptables' srcnat 100) that masquerades packets with Tailscale's forward mark (`0x40000/0xff0000`) leaving `ens*`; nf_nat performs NAT in the first chain that sets up a mapping. Installed by `CiliumService.installTailscaleMasquerade` (a script uploaded over `RemoteOperationsService`) from `Up.installCilium` when Tailscale is enabled — in the Cilium path because the need is Cilium's and Tailscale is already up by then. Idempotent (declare/delete/recreate table) and boot-persistent (`edl-tailscale-masquerade.service`; the AMI's `nftables.service` loads no drop-in directory).
- **Base AMI: match the unmanaged ENI by `Driver=ena`, and stop cloud-init hotplug re-rendering** — on the live node cloud-init's `install_hotplug` re-rendered `/etc/netplan/50-cloud-init.yaml` when the operator attached the second ENI (every pod IP as a static address on `ens5`, DHCP plus policy routing for `ens6`), and the new ENI arrived as `eth0` and was DHCP-configured before its rename to `ens6`, so a `Name=ens[6-9]` match came too late. `06-cilium-eni-unmanaged.network` now matches `Driver=ena` (05- still claims `ens5` by name and sorts first), and `/etc/cloud/cloud.cfg.d/90-easydblab-no-network-hotplug.cfg` sets `updates.network.when: [boot-new-instance, boot]` so hotplug never renders. Not validated live: needs a `build-image`.

### Decision 4 — IMDS hop-limit on all node types

`EC2InstanceService` (`:146-155`) wraps the `metadataOptions(httpPutResponseHopLimit(2), httpTokens(REQUIRED), httpEndpoint(enabled))` builder call in `if (serverType == ServerType.Control)`. Lift it out of the guard so every instance gets hop-limit 2. This is the root fix that lets the cilium-operator allocate ENIs regardless of which node it lands on. Rejected alternative: pin the operator to the control node via `nodeSelector` + toleration — more moving parts, and hop-limit 2 on workers is independently correct.

### Decision 5 — kube-proxy retained (kubeProxyReplacement EXPLICITLY OFF)

Keep K3s's kube-proxy this cut. Isolates the ENI datapath change and avoids re-opening the historical agent→API bootstrap-interception concern in the same change. `start-k3s-server.sh` already runs `--flannel-backend=none --disable-network-policy` under `useCustomCni`; no script change. Enabling `kubeProxyReplacement` (and `--disable-kube-proxy`) is a deliberate fast-follow.

**Now enforced, not merely intended.** The original config never passed `kubeProxyReplacement=false`, so Cilium's default effectively enabled it — turning on BPF masquerade / BPF host routing on the Nitro primary NIC and blackholing the node's own SSH:22 and apiserver:6443 (cilium/cilium#46010). A live AWS test hit exactly this. The flag is now set to `false` explicitly so the datapath stays on iptables + K3s kube-proxy and host networking is preserved. `kubeProxyReplacement=false` does **not** trigger the #46010 blackhole.

## Verified no-change (confirm-only)

- **IAM** — `AWSPolicy.kt:171` grants `ec2:*` (superset of all ENI actions). PASS, no change.
- **Security groups** — `InfrastructureConfig.forCluster()` opens all-port TCP (`:171-178`) and UDP (`:179-186`) across the VPC CIDR. PASS. Caveat: no ICMP rule → live validation uses curl/nc, never ping.
- **K3s launch** — flannel disabled under `useCustomCni` (`start-k3s-server.sh:37`). PASS.
- **CNI-first ordering** — `K3sClusterService.setupCluster()` runs `onServerReady` after server start but before agents join (`:154-171`). Already correct; no reordering.
- **AMI** — unchanged.

## Risks / Trade-offs

- **ENI IP capacity** — each pod consumes one VPC IP from the node's AZ subnet; density is capped by instance ENI limits (i4i.xlarge ≈ 45 pods/node) and subnet free IPs (/24 ≈ 251). Adequate for lab clusters; noted for large fan-outs.
- **All-or-nothing datapath, low-risk rollout** — per owner, native works or we pick a different tool; no VXLAN half-step is retained. This patch keeps Flannel as the default so the ENI datapath is opt-in until proven; #819 flips the default once it's trusted.
- **State field replacement** — replacing `ciliumEnabled` with `cni` changes the persisted shape; ephemeral clusters mean no migration is owed, and Jackson applies the default to older files.

## Migration

None. Ephemeral clusters; new field defaults apply automatically.
