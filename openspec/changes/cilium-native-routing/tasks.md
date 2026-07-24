## 1. Data model and CNI selection

- [x] 1.1 Add `enum class CniMode { Cilium, Flannel }` in `configuration/ClusterState.kt` (class-level KDoc)
- [x] 1.2 Replace `InitConfig.ciliumEnabled: Boolean` with `InitConfig.cni: CniMode = CniMode.Flannel` (default Flannel this patch; #819 flips it)
- [x] 1.3 Replace `Init.cilium: Boolean` with `Init.cni: CniMode = CniMode.Flannel` as `@Option(names = ["--cni"])` (default `flannel`; PicoCLI validates against the enum) in `commands/Init.kt`
- [x] 1.4 Update `InitConfig.fromInit` (`ClusterState.kt`) to map from `init.cni`
- [x] 1.5 Update every `ciliumEnabled` read (esp. `Up.startK3sOnAllNodes()` / `useCustomCni` gating) to `cni == CniMode.Cilium`

## 2. Cilium ENI native routing

- [x] 2.1 Change `CiliumService.install` signature (interface + `DefaultCiliumService`) to `install(controlHost: Host, vpcCidr: String): Result<Unit>`
- [x] 2.2 Replace the `--set` block with ENI native flags: `ipam.mode=eni`, `eni.enabled=true`, `routingMode=native`, `endpointRoutes.enabled=true`, `enableIPv4Masquerade=true`, `egressMasqueradeInterfaces=ens+`, `kubeProxyReplacement=false`, `bpf.hostLegacyRouting=true`, `ipv4NativeRoutingCIDR=$vpcCidr`, `k8sServiceHost=${controlHost.private}`, `k8sServicePort=6443`, `operator.replicas=1`, `hubble.relay.enabled=true`, `hubble.ui.enabled=true` (CORRECTED after a live AWS test — see design.md Decision 3: ENI mode requires a named `egressMasqueradeInterfaces` or the agent panics, and `kubeProxyReplacement` must be pinned `false` or it silently enables a BPF host-routing blackhole of the node's own SSH/API-server traffic)
- [x] 2.3 Single-quote the Hubble metrics value: `hubble.metrics.enabled='{dns,drop,tcp,flow,port-distribution,icmp,http}'` (fixes the brace-expansion bug)
- [x] 2.4 Remove `tunnelProtocol=vxlan` and `routingMode=tunnel`; do NOT add `autoDirectNodeRoutes` (only routes within a single L2 domain, breaks cross-AZ) — `egressMasqueradeInterfaces` and `kubeProxyReplacement` ARE required, per the correction in 2.2
- [x] 2.5 Replace the VXLAN-rationale comment with an ENI/native-routing rationale
- [x] 2.6 Update `Up.installCilium()` to read `workingState.initConfig?.cidr` (requireNotNull) and pass it to `install()`

## 3. IMDS hop limit for all nodes

- [x] 3.1 In `EC2InstanceService` (`~:146-155`), lift the `metadataOptions(httpPutResponseHopLimit(2), httpTokens(REQUIRED), httpEndpoint enabled)` block out of the `if (serverType == ServerType.Control)` guard so all node types receive it
- [x] 3.2 Update the now-stale "Control nodes need…" comment

## 4. Tests (unit tier)

- [x] 4.1 `CiliumServiceTest`: assert the install command contains `ipam.mode=eni`, `eni.enabled=true`, `routingMode=native`, `endpointRoutes.enabled=true`, `enableIPv4Masquerade=true`, and `ipv4NativeRoutingCIDR=<the cidr arg>`; assert it does NOT contain `tunnelProtocol=vxlan` or `routingMode=tunnel`
- [x] 4.2 `CiliumServiceTest`: regression test that the Hubble metrics value is single-quoted (`hubble.metrics.enabled='{`) — the brace bug
- [x] 4.3 `CiliumServiceTest`: pass a distinct CIDR and assert it appears verbatim in the command (guards threading)
- [x] 4.4 `EC2InstanceServiceTest` (new): capture `RunInstancesRequest` for db, app, and control specs; assert each has `metadataOptions().httpPutResponseHopLimit() == 2` and `httpTokens() == REQUIRED`
- [x] 4.5 `InitConfig.fromInit` test: default `cni == CniMode.Flannel`; `--cni=cilium` → `CniMode.Cilium`

## 5. Docs and spec

- [x] 5.1 Correct the observability CNI line in `CLAUDE.md` to describe reality — Flannel is the default datapath, Cilium ENI native routing is selectable via `--cni=cilium` (do NOT claim Cilium is the default; that lands in #819); grep confirms no `docs/` CNI mentions to update
- [x] 5.2 Ensure `openspec/specs/networking/spec.md` REQ-NET-007 is reflected (spec delta applied at archive)

## 6. Verification

- [x] 6.1 Run `./gradlew ktlintFormat && ./gradlew test && ./gradlew detekt` on JDK 21 — all pass
- [ ] 6.2 Live cross-AZ validation (1 control + 2 db nodes in different AZs) — see acceptance scenarios: default `up` → native (no VXLAN), all agents Ready, `ciliumnode` shows ENIs+IPs per node, each pod IP in its node's AZ subnet, cross-AZ pod→pod by pod IP (curl/nc), pod→ClusterIP, pod→external URL, Hubble flows; operator-on-db-node allocates ENIs (IMDS fix)
