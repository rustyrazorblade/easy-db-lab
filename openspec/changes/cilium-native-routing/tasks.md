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

## 7. Cilium observability

- [x] 7.1 `CiliumService`: add `prometheus.enabled=true`, `operator.prometheus.enabled=true`, `hubble.ui.service.type=NodePort`, `hubble.ui.service.nodePort=${Constants.Cilium.HUBBLE_UI_NODE_PORT}` (31234, checked against every kit NodePort); `CiliumServiceTest` asserts each flag
- [x] 7.2 `OtelManifestBuilder.buildConfigMap`/`buildAllResources` take `cni: CniMode`; `buildCniScrapeJobs` renders `cilium-agent` (localhost:9962), `hubble` (localhost:9965, moved out of the template so Flannel no longer polls a dead port), and `cilium-operator` (pod SD in kube-system on `io.cilium/app=operator`, node-local, port 9963) only for Cilium; `ObservabilityStackService` and `OtelSyncService` read the CNI from cluster state
- [x] 7.3 Confirm `filelog/containers` already tails `kube-system` Cilium and Hubble pod logs (no exclusion matches them) — no change needed
- [x] 7.4 `KubeStateMetricsManifestBuilder` (ServiceAccount, read-only ClusterRole, ClusterRoleBinding, Service :8080, Deployment on the control node, image pinned in `Constants.KubeStateMetrics`), deployed by `ObservabilityStackService` in both telemetry modes, scraped through node-local pod discovery; unit shape tests plus the `K8sServiceIntegrationTest` apply test
- [x] 7.5 `platform cni` (`PlatformCni` + `CiliumInspectionService`): Flannel one-liner, or `cilium-config` + `CiliumNode` read-back over `kubectl` on the control node, parsed with kotlinx.serialization; tests cover the commands issued, the parse, the Flannel branch, and the rendering
- [x] 7.6 `CiliumInstallAnnotator`: `CiliumService` records started/finished/failed with real timestamps; `Up` posts them to Grafana after the observability stack is up (local mode only) and emits `Grafana.AnnotationFailed` if the post fails
- [x] 7.7 Docs: `docs/user-guide/networking.md` (new, in SUMMARY), `platform-substrate.md`, `monitoring.md`, `reference/ports.md`; `CLAUDE.md` observability section and `configuration/CLAUDE.md`
- [x] 7.8 Spec deltas: networking (Cilium metrics, Hubble UI NodePort, `platform cni`, install annotations) and observability (kube-state-metrics)

## 8. Fixes from the live run

- [x] 8.1 `start-k3s-server.sh`: Cilium branch adds `--disable=traefik,servicelb` (ServiceLB node-IP LoadBalancer ingress + Cilium port-0 wildcard rejected every pod→node packet; live-proven); Flannel branch unchanged; `CiliumServiceTest` asserts both branches
- [x] 8.2 `CiliumService`: `--set devices=ens+` so `tailscale0` (MTU 1280) is not a Cilium device and the MTU updater leaves ens5/ens6 at 9001; asserted in `CiliumServiceTest`
- [x] 8.3 `configure_cilium_eni_networkd.sh`: `06-cilium-eni-unmanaged.network` matches `Driver=ena` (catches the ENI under its pre-rename `eth0` name); new `/etc/cloud/cloud.cfg.d/90-easydblab-no-network-hotplug.cfg` sets `updates.network.when: [boot-new-instance, boot]`; self-verify greps updated; `packer/README.md` updated
- [ ] 8.4 Validate 8.3 live: `build-image`, then bring up a `--cni=cilium` cluster, force a second ENI, and confirm `ens6` has no address, no second default route, and `/etc/netplan/50-cloud-init.yaml` is not re-rendered
- [x] 8.5 `CiliumService.installTailscaleMasquerade` + `services/install-tailscale-masquerade.sh`: nft `edl_tailscale` NAT chain at priority 90 masquerading Tailscale's forward mark on `ens*`, idempotent (declare/delete/recreate) and boot-persistent (`edl-tailscale-masquerade.service` oneshot); `Up.installCilium` runs it after the Cilium install when Tailscale is enabled; `CiliumServiceTest` asserts the upload path, the exact `sudo bash` command, and the script contents; `UpTest` covers ordering, the no-Tailscale skip, and the abort on failure
- [x] 8.6 `DefaultCiliumService.install` converges on re-run: probes `helm status cilium -n kube-system` (exit-0 wrapper), then `cilium upgrade` with the identical `--set` list when the release exists, `cilium install` otherwise; `Cilium.Upgrading` event; `CiliumServiceTest` asserts the probe, the verb, the identical flag list, and probe-failure propagation
