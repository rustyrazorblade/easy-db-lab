## 1. Environment data model

- [ ] 1.1 Add `configuration/environment/Environment.kt`. It is the sealed `Ec2K3s` / `K3sDocker(dataDir)` / `Kubeconfig(source)` record, with `displayName` and `declaredCapabilities`, and matching Jackson `@JsonTypeInfo`/`@JsonSubTypes` and kotlinx `@SerialName` ids (`ec2-k3s`, `k3s-docker`, `kubeconfig`). KDoc says the record names only the provisioner (K1).
- [ ] 1.2 Add `ClusterState.environment: Environment = Environment.Ec2K3s`.
- [ ] 1.3 `ClusterStateManager.load`: catch `InvalidTypeIdException` before the generic wrapper. Throw the error that names the path, the value and the known ids.
- [ ] 1.4 Unit tests: round-trip all three variants; a missing field reads as `Ec2K3s`; an unknown `type` gives the specific message and the executor reports it with no stack trace.

## 2. Cluster contract

- [ ] 2.1 Add the form enums `ToolingForm`, `NodeAccessForm`, `StoreKind` and `NetworkMode`, and `ClusterContract` with `of(state)`, the only `when (state.environment)` in main code.
- [ ] 2.2 Add `ClusterContractSource` and `StateClusterContractSource`. With no state, the latter resolves a default `Ec2K3s`. Register it in Koin.
- [ ] 2.3 Add `NodeCapability.AWS_ACCOUNT` to `NodeCapabilities.AWS` only. `CapabilityOverrides` refuses it.
- [ ] 2.4 `NodeCapabilities.from`: return the recorded set, else `environment.declaredCapabilities`. `Init.effectiveCapabilities()` starts from the environment's declared set.
- [ ] 2.5 Make `Placement.from` and `ObservabilityHosting.from` read the contract. Replace their "until #1040" KDoc.
- [ ] 2.6 Replace `StackEnvironment.from` and the `capabilitiesOf`/`environmentOf` lambdas in `ObservabilityStackService`, `OtelSyncService`, `Status` and `StatusCache` with the injected `ClusterContractSource`.
- [ ] 2.7 `ObservabilityStackService` and `OtelSyncService` read the region from `state.initConfig.region` and stop injecting `User`.
- [ ] 2.8 Unit tests: a parameterized resolution table over the three environments for every contract field; an AWS state with no recorded capabilities still yields the AWS set; the stack service skips the stages a `K3sDocker` contract lacks.

## 3. Per-environment Koin selection

- [ ] 3.1 Bind `ClusterTooling` as a `factory` selected on `toolingForm`. Remove the "until #1040" comment in `ServicesModule.kt:75`.
- [ ] 3.2 Audit the `single` holders of `ClusterTooling` so none freezes the form across a REPL `init`.
- [ ] 3.3 Update the KDoc in `KubeApiLocator` and `WorkspaceKubeApiLocator`. The locator is already environment-neutral; the contract does not change it.
- [ ] 3.4 Integration test: `LocalClusterToolingIntegrationTest` drops its Koin rebind and uses a `K3sDocker` state through the production binding.
- [ ] 3.5 Unit test: in one Koin graph, an `init --local` after an AWS state switches the resolved `ClusterTooling` on the next resolution.

## 4. Command gating and the profile rule

- [ ] 4.1 Add `@RequiresCapability(vararg capabilities, alternative)` and `Event.Environment.CommandUnavailable(command, environment, missing, alternative)`. Check it in `checkRequirements` before the profile check.
- [ ] 4.2 Annotate the AWS-only commands listed in the `execution-environment` spec. `cassandra use` also requires `NODE_SSH`.
- [ ] 4.3 Add `kernel/DeclaresEnvironment`. `Init` implements it. The executor applies `@RequireProfileSetup` only when the target environment has `AWS_ACCOUNT`.
- [ ] 4.4 Unit tests:
  - `cassandra use` and `spark` in a `K3sDocker` workspace emit `CommandUnavailable` naming the environment, with no side effect and no profile load.
  - `build-image` with no state passes the check.
  - An `Ec2K3s` workspace with no profile still requires setup.
- [ ] 4.5 Koin graph test: with no `settings.yaml` and a `K3sDocker` state, resolving `Status`, `KitRunnerCommand`, `Up`, `Down` and the grafana, logs and metrics commands loads no `User`.

## 5. init --local

- [ ] 5.1 Add `--local` and `--data-dir` (default `Constants.Local.DEFAULT_DATA_DIRECTORY`, resolved against the current directory, recorded absolute). `--data-dir` without `--local` is a usage error.
- [ ] 5.2 Add the local branch of `createClusterState`. It records `K3sDocker`, `PortForward` exposure (D3), no Tailscale, the `LOCAL_DOCKER` capabilities with overrides, Flannel, region `""` (D5) and `Arch.ofHost()`. It touches no `User` and no EC2 service.
- [ ] 5.3 Refuse explicitly given AWS-only options by name (`ParseResult.hasMatchedOption`).
- [ ] 5.4 Write `ProxyEnvFile.recordDirect(true)` and emit `Event.Setup.LocalWorkspaceInitialized`. Stop reading the env file key at `Init.kt:510`.
- [ ] 5.5 `clean` never lists the data directory, and `checkExistingFiles` does not refuse it.
- [ ] 5.6 Unit tests, with a real `UserConfigProvider` over an empty `@TempDir` and an EC2 mock that fails on any call:
  - the recorded state, with the default and a typed relative `--data-dir`;
  - `EDL_NETWORK_MODE=direct` in the env file;
  - `exposure = PortForward` recorded;
  - the AWS-only options refused with nothing written;
  - AWS `init` unchanged, with no `EDL_NETWORK_MODE` key.

## 6. up and down

- [ ] 6.1 Add a guard at the top of `Up.execute` and `Down.execute`. For an environment with no provisioner it emits `Event.Environment.ProvisionerUnavailable` and fails before any side effect or profile check.
- [ ] 6.2 Unit test: `up` and `down` in a `K3sDocker` workspace fail with the event and make no AWS call.

## 7. Direct network mode

- [ ] 7.1 `ProxyPreflight`: re-assert `recordDirect` from the contract next to `recordTailscale`, and return early when `networkMode == Direct`. `WorkspaceShellTools.prepare` writes the same key.
- [ ] 7.2 `SocksStart`: `TunnelNotNeeded` when `networkMode == Direct`. `RecordedTunnelRoutes`: `Usable(Direct)` in direct mode.
- [ ] 7.3 `ProxiedKubernetesClientFactory`: set `httpsProxy` only when `networkMode == Tunnel`. Never touch `socksProxyHost` or `socksProxyPort`.
- [ ] 7.4 Unit tests:
  - `ProxyPreflight` in a `K3sDocker` workspace restores a lost key and never asks `SocksProxyService`.
  - `socks start` reports no tunnel needed.
  - An `Ec2K3s` workspace keeps today's behaviour and has no key.
- [ ] 7.5 Integration test on a K3s TestContainer: in a `K3sDocker` workspace, a `@RequiresProxy` command reaches the API with no tunnel and no `PORT_PROPERTY`. A wrapped `kubectl` run through `KitProcessEnvironment` succeeds, with the caller's proxy variables passed through.

## 8. Docs and guidance

- [ ] 8.1 `help provisioning`, and the user-guide provisioning and networking pages: `init --local`, `--data-dir`, and the data directory surviving `clean` and being deleted with the workspace.
- [ ] 8.2 Root `CLAUDE.md`: the env file holds `EDL_NETWORK_MODE`, written from the recorded environment.
- [ ] 8.3 `configuration/CLAUDE.md` (environment, contract, hosting and declared set), `services/CLAUDE.md` (tooling selection), `commands/CLAUDE.md` (`@RequiresCapability`, the `@RequireProfileSetup` rule) and `docs/development/kits.md:113`.
- [ ] 8.4 Run `./gradlew ktlintFormat detekt test integrationTest` in a subagent, on JDK 21. Every test passes.
