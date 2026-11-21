## Why

Every typed kit step (helm-repo, helm, helm-uninstall, namespace, manifest, manifest-url, kustomize, wait, delete, delete-by-selector) and every helm or kubectl call the services make built its own SSH command string and ran it on the control node through `RemoteOperationsService`, with a `host` the caller had to find.  This change puts those calls behind one tested seam, `ClusterTooling`, so a later environment (epic #1032) can run the same tools elsewhere by adding one implementation.  Behaviour on AWS does not change.

## What Changes

- New `ClusterTooling` interface (`services/ClusterTooling.kt`): `run(tool, args, stdin, echo)` returns stdout and throws `ClusterToolFailedException(tool, args, exitStatus, location, stderr, stdout)` on a non-zero exit.
- `SshClusterTooling`, the only implementation: it prefixes `KUBECONFIG=${Constants.K3s.REMOTE_KUBECONFIG}`, quotes each argument by its characters (`shellQuote()`), uploads stdin to a temporary file on the control node, replaces the `-` argument with the uploaded path, and removes the file afterwards.  It resolves the control host from cluster state.
- `KubectlService` and `HelmService` keep their public methods, lose the `host` parameter, and run every command through `ClusterTooling`.  `createNamespace` applies a fabric8 `Namespace` through stdin in one call, instead of a dry run piped into apply.
- Callers updated: `WorkloadStepExecutor`, `KitWorkloadProbe`, `KitStatusCommand`, `KitRunnerCommand`.
- `RemoteCommandFailedException` gains `exitStatus`, which `SSHClient.remoteCommandFailure` fills (it dropped it before).
- Behaviour fixes made in the same change: `${VAR}` is interpolated in the `helm-repo` step's `name` and `url`, and in the `node-type` of `platform-pvs`, `platform-pvs-delete` and `label`, the `storage-class` and `volume-claim-template-name` of `platform-pvs`, and the keys of `label.labels`, as every other step string field already is.
- Koin binds `ClusterTooling` to `SshClusterTooling`.
- Docs: the `RemoteOperationsService` rule in `CLAUDE.md`, `commands/CLAUDE.md`, `services/CLAUDE.md`, and `docs/development/kits.md`.

## Out of scope

- A form that runs helm and kubectl on the developer's machine for a local cluster, and choosing the form per environment: deferred to #1163.
- Node filesystem commands, including the `platform-pvs` and `platform-pvs-delete` steps (#1039).
- `CiliumService`, `CiliumInspectionService` and `K3sService`, which are AWS-only and stay on `RemoteOperationsService`.
- Fabric8 steps (configmap, label, exec, PV objects), which already use the workspace kubeconfig.

## Capabilities

- New: `cluster-tooling`.
- Modified: `ami-tooling` (HelmService and KubectlService requirements), `typed-install-steps` (helm operations requirement).

## Impact

- `services/KubectlService.kt`, `services/HelmService.kt`, `services/WorkloadStepExecutor.kt`, `services/KitWorkloadProbe.kt`, `commands/install/KitStatusCommand.kt`, `commands/install/KitRunnerCommand.kt`, `services/ServicesModule.kt`, `exceptions/EasyCassLabExceptions.kt`, `ssh/SSHClient.kt`, `Constants.kt`.
- New: `services/ClusterTooling.kt`, `services/SshClusterTooling.kt`.
- Part of epic #1032.  Related: #747 (cloud-agnostic kit engine).
