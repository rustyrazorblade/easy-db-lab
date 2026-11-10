## Why

Every typed kit step (helm-repo, helm, helm-uninstall, namespace, manifest, manifest-url, kustomize, wait, delete, delete-by-selector) and every helm or kubectl call the services make runs over SSH on the control node, with `KUBECONFIG=/etc/rancher/k3s/k3s.yaml`.  Those calls cannot run where there is no SSH host, which blocks the local execution environment (epic #1032).  Kit shell steps already run helm and kubectl as local processes through the workspace wrappers, so the codebase has two ways of running the same tools.  For AWS, this change replaces SSH command strings scattered across two services with one tested seam; behaviour on AWS does not change.

## What Changes

- New `ClusterTooling` interface (`services/ClusterTooling.kt`): `run(tool, args, stdin, echo, shellQuoted)` returns stdout and throws `ClusterToolFailedException(tool, args, exitStatus, stderr, location)` on a non-zero exit.
  - `SshClusterTooling`: today's behaviour.  It prefixes `KUBECONFIG=${Constants.K3s.REMOTE_KUBECONFIG}`, uploads stdin to a temporary file on the control node, replaces the `-` argument with the uploaded path, and removes the file in `finally`.  It resolves the control host from cluster state.
  - `LocalClusterTooling`: runs the tool as a local process in the kit process environment (`KitProcessEnvironment`: `<workspace>/bin` first on `PATH`, `KUBECONFIG=<workspace>/kubeconfig`).  stdin is piped, so no temporary file is written.  Manifests use `apply -f -`; helm values use `--values -`.
- `KubectlService` and `HelmService` keep their public methods, lose the `host` parameter, and run every command through `ClusterTooling`.  `withKubeconfig` and shell quoting move into `SshClusterTooling`.  `createNamespace` drops its shell pipe (dry-run output is applied through stdin).
- Callers updated: `WorkloadStepExecutor`, `KitWorkloadProbe`, `KitStatusCommand`.
- `RemoteCommandFailedException` gains `exitStatus`, which `SSHClient.remoteCommandFailure` fills (today it drops it).
- Behaviour fixes made in the same change: `${VAR}` is interpolated in the `helm-repo` step's `name` and `url` (on AWS too, where it was not), and in the `node-type` of `platform-pvs`, `platform-pvs-delete` and `label`, the `storage-class` and `volume-claim-template-name` of `platform-pvs`, and the keys of `label.labels`, as every other step string field already is.
- Koin binds `ClusterTooling` to `SshClusterTooling`.  Until #1040 records the environment in state, the local form is selected only by a Koin override in tests.  There is no user-facing switch.
- Docs: the `RemoteOperationsService` rule in `CLAUDE.md`, `commands/CLAUDE.md`, `services/CLAUDE.md`, and `docs/development/kits.md` (local runs need helm and kubectl on the host, as shell steps already do).

## Out of scope

- Choosing the form per environment (#1040).
- Node filesystem commands, including the `platform-pvs` and `platform-pvs-delete` steps (#1039).
- `CiliumService`, `CiliumInspectionService` and `K3sService`, which are AWS-only and stay on `RemoteOperationsService`.
- Fabric8 steps (configmap, label, exec, PV objects), which already use the workspace kubeconfig.
- The SOCKS proxy and the `direct` network mode (#1040).

## Capabilities

- New: `cluster-tooling`.
- Modified: `ami-tooling` (HelmService and KubectlService requirements), `typed-install-steps` (helm operations requirement).

## Impact

- `services/KubectlService.kt`, `services/HelmService.kt`, `services/WorkloadStepExecutor.kt`, `services/KitWorkloadProbe.kt`, `commands/install/KitStatusCommand.kt`, `services/ServicesModule.kt`, `exceptions/EasyCassLabExceptions.kt`, `ssh/SSHClient.kt`, `Constants.kt`.
- New: `services/ClusterTooling.kt`, `services/SshClusterTooling.kt`, `services/LocalClusterTooling.kt`.
- Part of epic #1032.  Related: #747 (cloud-agnostic kit engine).
