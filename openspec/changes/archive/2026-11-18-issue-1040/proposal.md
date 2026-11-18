## Why

Nothing records which environment a workspace targets. Each seam merged so far on `local-k8s-edbl` (cluster tooling #1034, node capabilities #1037/#1096, service access #1090, observability hosting #1091, Kubernetes API access #1089) therefore stubs its choice to AWS "until #1040 records the environment". Local users also hit AWS profile setup on `init`, `up`, `down` and kit commands, although they have no AWS account. This change records the provisioner once, resolves every consumer-facing property from it, and adds `init --local` and the production writer for the `direct` network mode.

## What Changes

- New sealed `Environment` recorded in `state.json` as `ClusterState.environment`. It names only the provisioner (owner decision K1):
  - `Ec2K3s`: EC2 nodes running K3s. The default, so a state with no field reads as AWS.
  - `K3sDocker(dataDir)`: K3s in Docker on this machine (owner decision Q1).
  - `Kubeconfig(source)`: a cluster the user brings. The variant is first-class in the data model now; its `init --kubeconfig` belongs to #1082.
  - An unknown recorded value fails with a clear error that names the value and the known ones. It never shows a stack trace.
- New `ClusterContract.of(state)`, a pure function and the only `when` over `Environment` in main code. It resolves the cluster tooling form, the node access form (#1039 slot), the observability store kind (#1035 slot), the network mode, the declared node capabilities, the observability hosting and placement, the namespace and the datacenter name. One injected `ClusterContractSource` replaces the four per-service `capabilitiesOf`/`environmentOf` lambdas. No consumer tests an environment name.
- Every "until #1040" stub reads the contract: `Placement.from`, `ObservabilityHosting.from`, `NodeCapabilities.from` (falls back to the environment's declared set), the `ClusterTooling` Koin binding (no more harness rebind), the KDoc in `KubeApiLocator`/`WorkspaceKubeApiLocator`, `StatusCache`, `Status`, `ObservabilityStackService` and `OtelSyncService`.
- New `NodeCapability.AWS_ACCOUNT`, declared by `Ec2K3s` only and never overridable. AWS-only commands declare `@RequiresCapability(AWS_ACCOUNT)`. In another environment they fail before any side effect with `Event.Environment.CommandUnavailable`, which names the command, the environment and an alternative where one exists.
- `@RequireProfileSetup` applies only when the target environment has `AWS_ACCOUNT`. The target is what `init` is about to record, or else the recorded environment. `ObservabilityStackService` and `OtelSyncService` read the region from cluster state instead of injecting `User`.
- `init --local [--data-dir PATH]`. It makes no AWS API call and loads no AWS profile. The data directory defaults to `<workspace>/observability-data`; a typed path is resolved against the current directory and recorded as an absolute path. AWS-only `init` flags given explicitly are refused by name.
- The `direct` network mode from end to end. The recorded environment decides the network mode. `init --local` writes `EDL_NETWORK_MODE=direct` to the env file, and the proxy preflight re-asserts the key from state. The wrappers need no change (#1077). The Kotlin Kubernetes client is never proxied in direct mode. `socks start` and `@RequiresProxy` commands start no tunnel. `init` stops reading `exposure` from the env file.
- `up` and `down` in a non-AWS workspace fail fast with a typed event naming the missing provisioner (#1041). They show no profile prompt.
- Docs: `help provisioning`, the user-guide provisioning and networking pages, root `CLAUDE.md` (env file keys), `configuration/CLAUDE.md`, `services/CLAUDE.md`, `commands/CLAUDE.md` and `docs/development/kits.md`.

## Capabilities

### New Capabilities
- `execution-environment`: the recorded environment, the resolved cluster contract, the AWS-only command gate and the per-environment profile rule.

### Modified Capabilities
- `cluster-lifecycle`: `init --local`, the data directory, and `up`/`down` in an environment with no provisioner yet.
- `networking`: a direct-mode clause in "SOCKS Proxy". The direct-mode wrapper requirement now has a production writer.

## Impact

- New package `configuration/environment/` (`Environment`, `ClusterContract`, `ClusterContractSource` and the form enums). It imports no AWS SDK, so the #1083 ArchUnit rule holds.
- `ClusterState`, `ClusterStateManager` (unknown-type error), `Init`, `Up`, `Down`, `CommandExecutor` requirement checks, `ServicesModule`/`KubernetesModule` bindings, `KubernetesClientFactory`, `ProxyPreflight`, `SocksStart`, `RecordedTunnelRoutes`, `NodeCapability`, `Placement`, `ObservabilityHosting`, `ObservabilityStackService`, `OtelSyncService`, `Status`, `StatusCache`, `Constants`, `Event.kt`, and the AWS-only command classes.
- AWS workspaces behave exactly as today.
- Out of scope:
  - the K3s-in-Docker provisioner and its `up`/`down` (#1041);
  - node commands without sudo (#1039);
  - per-environment observability storage rendering (#1035);
  - `init --kubeconfig` (#1082);
  - the namespace and datacenter `init` flags (#1094, #1097);
  - the cloud-provider module split (#746).
