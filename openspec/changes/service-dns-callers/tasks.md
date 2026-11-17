## 1. Spike: Alloy relay (D2)

- [x] 1.1 Integration test (real Alloy + Pyroscope containers, `CollectorToBackendsIntegrationTest` style): POST a reconciler-shaped JFR (`format=jfr`, `name=` series with labels, tenant header) to an Alloy `pyroscope.receive_http` relay and read it back from Pyroscope under that tenant. If it fails, use the fallback the owner already decided (D2b): forward `cluster.local` from systemd-resolved to CoreDNS on every node, point the reconciler at the Pyroscope Service FQDN, drop the relay tasks, and record the switch in `design.md`.

## 2. Shared addresses and constants

- [x] 2.1 Add `Constants.K8s.ALLOY_RELAY_PORT` and the six observability NodePort constants; unit test that none repeats a packaged kit NodePort or `HUBBLE_UI_NODE_PORT`.
- [x] 2.2 Add `configuration/BackendEndpoints` (`pyroscope()`, `mimirPush()`, `lokiOtlp()`, `tempoOtlpGrpc()`, `.svc` form) and route `OtelManifestBuilder` exporters and `GrafanaDatasourceSet` through it (D5a).
- [x] 2.3 Change `pyroscopeIngestBaseUrl(redirect)` (no IP) and add `nodeLocalProfilesRelay()`.

## 3. In-cluster producers to Service DNS

- [x] 3.1 `PyroscopeManifestBuilder`: drop the hosting branch and `CONTROL_NODE_IP` read; Alloy writes to `BackendEndpoints.pyroscope()` or redirect; add the `receive_http` relay on `127.0.0.1:ALLOY_RELAY_PORT`.
- [x] 3.2 `SidecarManifestBuilder` / `SidecarService.deploy` / `cassandra start`: drop `controlNodeIp`; agent uses Service DNS or redirect.
- [x] 3.3 `StressJobService.buildJob`: remove the control-node lookup, parameter and argument; Pyroscope address from `pyroscopeIngestBaseUrl(redirect)`. Leave hostNetwork, hostPath, selector, region and target for #1101.
- [x] 3.4 `TemplateVariables.pyroscopeUrl` from `pyroscopeIngestBaseUrl(redirect)`.
- [x] 3.5 Unit tests (real `TemplateService`): each producer renders Service DNS locally and `redirect.profiles` under redirect; stress builds with no control node.
- [x] 3.6 Extend `BuiltinKitCollectorEndpointTest`: no kit file pairs `CONTROL_HOST_PRIVATE`/`HOST_IP` with 9009, 3100, 3200, 4320 or 4040.

## 4. Host processes to the relay

- [x] 4.1 `SetupInstance`, `ProfilingStart`, `ProfilingStop`: `pyroscopeUrl = nodeLocalProfilesRelay()`; delete `ProfilingHostCommand.controlNodePrivateIp()`.
- [x] 4.2 Update `edl-profiling-reconcile.test.sh` fixtures to the relay URL; run `testCassandraScripts`.

## 5. Remove host ports and publish NodePorts

- [x] 5.1 Remove `Placement.publishOnNode`, `placePorts` and every backend/Grafana `withHostPort`.
- [x] 5.2 Backend and Grafana Service builders: `type: NodePort` with the fixed client NodePorts when the environment owns its nodes; ClusterIP otherwise (D1a, D1b-a).
- [x] 5.3 `ClusterServices.controlService(name, servicePort, nodePort)` takes the NodePort constants; Socks/Direct seam, `StatusAddresses` and `StatusCache` follow.
- [x] 5.4 Remove `ClusterConfigData.CONTROL_NODE_IP_KEY`, `publishedNodeIp` and its failure path.
- [x] 5.5 Flip `ObservabilityHostingRenderingTest` AWS scenario to "no hostPort"; update `ControlNodePortsTest`; add Service-type rendering tests for both environment kinds; seam tests assert Socks/Direct dial the NodePort.

## 6. Off-cluster callers

- [x] 6.1 `TelemetryRedirect.fromBaseHost` derives the NodePorts (D3a); unit test with and without overrides.
- [x] 6.2 `EMRProvisioningService`: Pyroscope at `controlIp:<Pyroscope NodePort>` or redirect (D4a).
- [x] 6.3 `GrafanaDashboardTreeUploader`: Pyroscope UI link at the NodePort on owned nodes (D6a).
- [x] 6.4 `bin/export-workload-metrics`: Mimir NodePort.

## 7. Integration tier

- [x] 7.1 Extend `PortableObservabilityPlacementIntegrationTest`: AWS-hosted stack on K3s, every pod Ready, no `hostPort`; a hostNetwork + `ClusterFirstWithHostNet` Job POSTs a JFR to the Pyroscope Service and the profile is read back with the tenant.
- [x] 7.2 Each published NodePort reaches its backend from the K3s node address.

## 8. Docs and guidance

- [x] 8.1 `docs/reference/ports.md`: control-node section becomes Service ports plus published NodePorts and the relay port.
- [x] 8.2 `docs/user-guide/monitoring.md`, `network-connectivity.md`, `profiling.md`, `platform-substrate.md`, `docs/development/kits.md` (stop advising `CONTROL_HOST_PRIVATE:4040`), `help/observability.md`.
- [x] 8.3 `configuration/CLAUDE.md`, `dashboards/CLAUDE.md`, root `CLAUDE.md` Grafana port mention.
- [x] 8.4 Update #1101's `design.md` line on stress reaching `controlIP:4040` (issue-1101 4f34cd9e).

## 9. Verify

- [ ] 9.1 `./gradlew check` (JDK 21) in a subagent; ktlint and detekt clean with no suppressions.
- [ ] 9.2 AWS lab run (`/easy-db-lab:plan`, `/easy-db-lab:run`): profiles arrive from eBPF, sidecar, stress, reconciler and EMR; `status`, `logs query`, `grafana annotate` work; `down` flushes and tears down; a redirected second cluster lands all four signals.
