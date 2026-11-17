## Context

Epic #1032 (Kubernetes-forward), phase B. Architecture: `docs/plans/2026-11-11-kubernetes-forward-architecture.md`.

What exists on `local-k8s-edbl` (`2295c49c`):

- **Env file and wrappers.** `ProxyEnvFile.recordDirect` (`proxy/ProxyEnvFile.kt:78`) writes `EDL_NETWORK_MODE=direct` (`Constants.kt:1222-1225`), and `tool-wrapper.sh` honours it (#1077). No production code calls the writer (`openspec/specs/networking/spec.md:1128`).
- **`init` reads the key backwards.** It reads the key from the env file to decide `exposure` (`commands/Init.kt:510`).
- **Tunnel need.** `reachedBySocksTunnel()` (`proxy/SocksTunnelNeed.kt:12`) is `exposure == Socks && !tailscale`. `ProxyPreflight` and `Up.startProxyIfNeeded` skip the tunnel when exposure is not Socks.
- **Kubernetes API access.** `WorkspaceKubeApiLocator` reads `<workspace>/kubeconfig` and `ClusterState.kubeContext` (#1089). This is already the same for all three environments.
- **Four separate capability seams.** `environmentOf`/`StackEnvironment.from` (`ObservabilityStackService.kt:108,440`), and `capabilitiesOf` in `OtelSyncService.kt:48`, `Status.kt:86` and `StatusCache.kt:52`.
- **AWS stubs.** `Placement.from` (`Placement.kt:75`), `ObservabilityHosting.from` (`ObservabilityHosting.kt:231`) and `NodeCapabilities.from` (`NodeCapability.kt:92`) return AWS. The `ClusterTooling` binding (`ServicesModule.kt:75`) is remote, and the integration harness rebinds it to the local form (`LocalClusterToolingIntegrationTest.kt:291`).
- **Hidden AWS dependencies on local paths:**
  - `OtelSyncService` and `ObservabilityStackService` inject `User` only for `user.region` (`OtelSyncService.kt:69`, `ObservabilityStackService.kt:223,406`). `User` is `single { getUserConfig() }` (`di/ContextModule.kt:33`), which throws without `settings.yaml`.
  - `init` reads `userConfig` (`Init.kt:492,500`) and calls EC2 `describeInstanceType` (`Init.kt:524`).

Owner decisions that bind this design: Q1 (K3s-in-Docker is the first local runtime; kind is only a cluster the user brings), Q2 (an explicit `direct` network mode; `EDL_TAILSCALE_ACTIVE` is not reused), K1 (the environment names only the provisioner), and "three implementations per seam" (AWS, K3s in Docker, a cluster the user brings).

## Goals / Non-Goals

**Goals:**
- Record the provisioner once in `state.json`. Resolve every consumer-facing property from it through one pure function.
- Make each "until #1040" stub read it.
- `init --local` with no AWS call and no profile.
- The production writer of the direct network mode, honoured by the wrappers and the Kotlin clients.
- AWS-only commands fail fast in other environments.
- AWS behaves exactly as today.

**Non-Goals:**
- The K3s-in-Docker provisioner (#1041). `up`/`down` in a local workspace fail fast until it lands.
- Node commands without sudo (#1039). This change only adds the `NodeAccessForm` slot.
- The Volume rendering of observability storage (#1035). This change only adds the `StoreKind` slot.
- `init --kubeconfig` (#1082). The `Kubeconfig` variant exists in the data model only.
- The namespace and datacenter flags (#1094, #1097). They are contract fields with fixed per-environment values now.
- Moving `state.json` from Jackson to kotlinx.serialization.
- The provider module split (#746).

## Decisions

The owner decided D1 to D9 at spec review on 2026-11-16. Each took option (a), the architect's recommendation. The rejected options are kept for the record.

### 1. Data model: a sealed provisioner record

```kotlin
sealed interface Environment {
    val displayName: String                       // messages only
    val declaredCapabilities: NodeCapabilities
    data object Ec2K3s : Environment               // "ec2-k3s", "EC2 + K3s", NodeCapabilities.AWS
    data class K3sDocker(val dataDir: String) : Environment   // "k3s-docker", "K3s in Docker", LOCAL_DOCKER
    data class Kubeconfig(val source: String) : Environment   // "kubeconfig", "your Kubernetes cluster", none declared; #1082 probes
}
```

- **Field:** `ClusterState.environment: Environment = Environment.Ec2K3s`.
- **Serialization:** the record uses a `type` discriminator. `state.json` is Jackson (`ClusterStateManager.kt:29`), so the type carries Jackson `@JsonTypeInfo`/`@JsonSubTypes` and kotlinx `@SerialName` with equal names, as `NodeCapability` does.
- **Unknown value:** `ClusterStateManager.load` catches `InvalidTypeIdException` before its generic wrapper. It throws an `IllegalStateException` that names the `state.json` path, the recorded id and the known ids. The executor's failure reporter shows it as an error.
- **Kubeconfig context:** it stays in `ClusterState.kubeContext`, which the locator already reads.

*Alternatives:* an enum plus loose fields (rejected: the `dataDir` and `source` parameters belong to their variant); recording capability data in the environment (rejected by K1).

### 2. `ClusterContract`: capability data, never names

`ClusterContract.of(state)` lives in `configuration/environment/`. It is pure and has no `services` or SDK imports. It holds the only `when (state.environment)` in main code. Small enums keep the package free of the `services` layer; Koin maps each one to its implementation.

| Field | `Ec2K3s` | `K3sDocker` | `Kubeconfig` |
| --- | --- | --- | --- |
| `toolingForm` (#1034) | `Ssh` | `Local` | `Local` |
| `nodeAccessForm` (#1039) | `Ssh` | `ContainerExec` | `None` |
| `storeKind` (#1035) | `ObjectStore` | `ObjectStore` until #1035 renders `Volume` | `ObjectStore` until #1035 (#1082 may refine) |
| `networkMode` | `Tunnel` (Tailscale keeps its own flag) | `Direct` | `Direct` |
| `nodeCapabilities` | recorded, else AWS | recorded, else LOCAL_DOCKER | recorded (probed, #1096) |
| `hosting.placement` | `CONTROL_NODE` | `CONTROL_NODE`, not published on the node | `ANY_NODE` plus `--node-label` |
| `namespace` | `default` | `default` | `easy-db-lab` |
| `datacenter` | `initConfig.region`, else the profile region (fails when none) | `dc1` | `dc1` |

`exposure` stays a recorded `ClusterState` field (#1090), written by `init` from the environment (Decision 5).

A test seam binds a fixed contract through `ClusterContractSource` (`fun interface { fun current(): ClusterContract }`). Its production implementation, `StateClusterContractSource`, reads the current state. With no `state.json` it resolves a default `Ec2K3s` state.

### 3. Where the namespace, the datacenter and the other recorded facts live: decided (D1, owner, 2026-11-16): option (a)

- **(a) Chosen:** `environment` holds only the provisioner and its own parameters. `capabilities`, `exposure` and `kubeContext` stay top-level. Namespace and datacenter are contract fields resolved from the environment; #1094 and #1097 add their flags and nullable recorded overrides. This is the least churn on the merged #1090/#1096 fields, and builds nothing unused.
- (b) Rejected: One nested `environment` block holding the provisioner, capabilities, exposure, namespace and datacenter. Closest to the amendment's "record them with the environment", but it moves fields that about 15 merged call sites read.
- (c) Rejected: Add non-null `namespace` and `datacenter` fields now. Pins the values at `init`, but adds fields nothing can set yet.

### 4. How "once per command" is enforced: decided (D2, owner, 2026-11-16): option (a)

- **(a) Chosen:** the pure `ClusterContract.of(state)`, evaluated on each call through `ClusterContractSource`. Seams the environment selects are bound as Koin `factory`. It is deterministic, survives an `init` inside Repl or Server, and is safe on `down`'s Phase B thread pool. `ExposureServiceAccess` already resolves on each call for the same reason.
- (b) Rejected: A per-command memo the executor clears, like `ProxyPreflight`'s `ThreadLocal`. It breaks on the Phase B pool.
- (c) Rejected: Koin `single`s. They go stale after an `init` inside Repl.

### 5. Service exposure for K3s in Docker: decided (D3, owner, 2026-11-16): option (a)

The network mode is `Direct` either way. This decision is how the CLI reaches cluster Services.

- **(a) Chosen:** `PortForward` on loopback, with a free local port chosen on each use, never a fixed host port. `init --local` records `exposure = PortForward`. It works on Docker Desktop for macOS, and it meets #1041's "Grafana when port 3000 is taken" criterion with no extra work. CQL is a single-endpoint session through the existing #1090 path (`CqlSessionService.kt:102`); this change adds no CQL code.
- (b) Rejected: `Direct`, with #1041 recording `127.0.0.1` per role and publishing every NodePort. Fixed host ports can clash.
- (c) Rejected: `Direct` to container IPs. Docker Desktop for macOS cannot reach them from the host.

### 6. `up` and `down` before #1041: decided (D4, owner, 2026-11-16): option (a)

- **(a) Chosen:** a guard at the top of `Up.execute` and `Down.execute`. For an environment with no provisioner yet it emits `Event.Environment.ProvisionerUnavailable(command, environment)` and fails, with no profile prompt. #1041 owns the `Provisioner` extraction.
- (b) Rejected: Define the `Provisioner` interface here, with `Ec2K3s` wrapping today's code. This takes over #1041's scope.

### 7. Region on a local `InitConfig`: decided (D5, owner, 2026-11-16): option (a)

- **(a) Chosen:** `""`. Only the S3 and EC2 paths read it, and those are AWS-gated.
- (b) Rejected: Move `region` into `Ec2K3s(region)`. Cleaner under K1 and aligned with #746, but it touches 8 readers. Better done in #1097 or #746.
- (c) Rejected: Make it nullable.

### 8. How a command declares it is AWS-only: decided (D6, owner, 2026-11-16): option (a)

- **(a) Chosen:** `NodeCapability.AWS_ACCOUNT` plus a class annotation `@RequiresCapability(vararg capabilities, alternative)`, checked in `checkRequirements` before the profile check. It shares one vocabulary with the kit `requires:` terms, and needs no environment-name check. `CapabilityOverrides` refuses it, because it is declared, not probed.
- (b) Rejected: A separate `EnvironmentFeature` enum. Two vocabularies.
- (c) Rejected: An `@AwsOnly` annotation. This is branching on a name.

The gated set is `cassandra use`, `cassandra install`, `build-image`, `build-base`, `build-cassandra`, `spark *`, `opensearch *`, `tailscale *`, `ingress *`, `aws *`, `prune-amis`, `show-iam-policies` and `observability compactor *`. `cassandra use` also requires `NODE_SSH`. Outside a workspace the target is `Ec2K3s`, so `build-image` with no workspace still runs.

### 9. Profile requirement: decided (D7, owner, 2026-11-16): option (a)

- **(a) Chosen:** keep `@RequireProfileSetup`. The executor applies it only when the target environment has `AWS_ACCOUNT`. The target is `(command as? DeclaresEnvironment)?.declaredEnvironment()` (implemented by `Init`), else the recorded environment. `DeclaresEnvironment` lives in `kernel/`, which depends only on `configuration`. This one rule covers all 76 annotated commands.
- (b) Rejected: Remove the annotation from `init`, `up` and `down` and check inside each. The other 73 commands still prompt in a local workspace.

### 10. Direct mode for an AWS workspace: decided (D8, owner, 2026-11-16): option (a)

- **(a) Chosen:** not in this change. Only `init --local` writes the key. `Init.kt:510` stops reading it, so the environment alone decides.
- (b) Rejected: Add `init --network direct` for AWS over a VPN. This is a feature nobody has asked for, so it needs the owner's go-ahead.

### 11. Data directory default name: decided (D9, owner, 2026-11-16): option (a)

- **(a) Chosen:** `<workspace>/observability-data` (`Constants.Local.DEFAULT_DATA_DIRECTORY`). It cannot be mistaken for kit data. `clean` never lists it, and `init` does not refuse an existing one.
- (b) Rejected: `<workspace>/data`. Shorter and more generic.

### 12. `init --local`: what it records

The local branch of `Init.createClusterState` calls no `User`, no `EC2InstanceService` and no `deriveArch`. It records:

- `environment = K3sDocker(absolute dataDir)`
- `exposure = PortForward` (D3)
- `tailscaleActive = false`
- `capabilities = overrides.applyTo(LOCAL_DOCKER)`
- `initConfig` with:
  - the db and app counts, `tenant`, `telemetryRedirect` and `nodeLabels`
  - `cni = Flannel`, because Cilium ENI is EC2-only
  - `region = ""` (D5)
  - architectures from the Docker daemon's `Architecture` (`docker info`), not the JVM's `os.arch`: an x86_64 JVM under Rosetta on Apple Silicon reports x86_64 while Docker Desktop runs arm64

Then it writes `ProxyEnvFile(workspace).recordDirect(true)` and emits `Event.Setup.LocalWorkspaceInitialized(dataDir, dbCount, appCount)`.

Explicitly given AWS-only flags are refused by name, using Picocli's `ParseResult.hasMatchedOption`:

- `--instance`, `--db.instance-type`, `--azs`, `--ebs.*`
- `--vpc`, `--cidr`, `--cni`
- `--spark.*`, `--opensearch.*`
- `--ami`, `--open`

`--data-dir` without `--local`, and `--local --up`, fail like a refused AWS-only option: a typed event (`Setup.InitOptionConflict`) the user reads, then a `CommandFailedException`, so every refusal of `init` reads the same.

### 13. Direct mode from end to end

| Layer | Behaviour |
| --- | --- |
| Env file | Written by `init --local`. Re-asserted from state by `ProxyPreflight` (next to `recordTailscale`) and by `WorkspaceShellTools.prepare`. The state is the source; the file follows. |
| Wrappers, `env.sh` | Unchanged (#1077). Proxy variables the caller set pass through. |
| `@RequiresProxy` | `ProxyPreflight` returns early when `networkMode == Direct`. |
| `socks start` | `TunnelNotNeeded` when Tailscale is on or `networkMode == Direct`. |
| `RecordedTunnelRoutes` | `Usable(Direct)` when `networkMode == Direct`. |
| Kubernetes client | `ProxiedKubernetesClientFactory` sets `httpsProxy` only when `networkMode == Tunnel`. `socksProxyHost` and `socksProxyPort` are never touched. |
| Service clients | Unchanged. They go through `ServiceAccess` and `exposure` (#1090). |

### 14. Koin selection

The pattern follows `SSHModule.kt:25-30`: a `when` over the contract inside the binding, bound as `factory`.

- `ClusterTooling` is selected on `toolingForm`: `SshClusterTooling` or `LocalClusterTooling`. The integration harness rebind is removed.
- #1039 plugs `NodeAccess` in the same way, on `nodeAccessForm`.
- #1041 plugs `Provisioner` in the same way, on the environment.

## Risks / Trade-offs

- **[Risk]** A service on a local command path still injects `User` (48 sites) and throws "run profile setup". → A unit test builds the Koin graph for the local command set with no `settings.yaml` and fails on any `User` load. `user.region` is removed from the stack and OTel services.
- **[Risk]** A `single` that captures `ClusterTooling` freezes it across an `init` inside Repl. → Audit every holder when the binding becomes a `factory`. The test runs `init --local` after an AWS state in one Koin graph.
- **[Risk]** A local kit `start` still fails on S3 storage until #1035 lands (`StoreBackend.from` requires `s3Bucket`). → Expected. This change removes the profile requirement; #1035 renders `Volume`. The scenario for local kit commands asserts only that no profile is required.
- **[Assumption about #1035]** #1035 merges `ObservabilityStorage.from(state)` as currently drafted (`S3 | Volume`, returning `S3` "until #1040"). Whichever change merges second makes it `when (ClusterContract.of(state).storeKind)`. Neither change compiles against the other.
- **[Risk]** Docker Desktop for macOS cannot reach container IPs from the host. → D3 chose `PortForward` on loopback.
- **[Trade-off]** The default data directory sits inside the workspace, so deleting the workspace deletes the data. → The docs say so plainly. `clean` never removes it, and nothing in easy-db-lab deletes it.
- **[Risk]** An unknown environment from a newer easy-db-lab. → A clear error that names the value and the known ones.

## Migration Plan

None. Clusters are ephemeral. A state with no `environment` field reads as `Ec2K3s`.

## Open Questions

None. D1 to D9 were decided by the owner on 2026-11-16 (Decisions 3 to 11).
