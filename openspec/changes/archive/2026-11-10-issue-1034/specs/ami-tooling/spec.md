## MODIFIED Requirements

### Requirement: HelmService executes helm on the control node via SSH
`HelmService` SHALL run `helm` commands through `ClusterTooling`, not by calling `RemoteOperationsService` or `ProcessBuilder` directly.  On AWS the SSH form runs helm on the control node with the control-node KUBECONFIG; the local form runs helm as a local process in the kit process environment.

#### Scenario: helm upgrade --install runs remotely
- **WHEN** `HelmService.upgradeInstall()` is called with a chart and values and the SSH form is bound
- **THEN** the helm command is executed on the control node via SSH
- **AND** the KUBECONFIG on the control node is used (not a locally-passed env var)

#### Scenario: helm upgrade --install runs locally
- **WHEN** `HelmService.upgradeInstall()` is called with a chart and values and the local form is bound
- **THEN** helm runs as a local process against the workspace kubeconfig
- **AND** the values are passed on stdin with `--values -`

### Requirement: KubectlService executes kubectl on the control node via SSH
`KubectlService` SHALL run `kubectl` commands through `ClusterTooling`, not by calling `RemoteOperationsService` or `ProcessBuilder` directly.  On AWS the SSH form runs kubectl on the control node with the control-node KUBECONFIG; the local form runs kubectl as a local process in the kit process environment.

#### Scenario: kubectl apply runs remotely
- **WHEN** `KubectlService.applyContent()` is called with a manifest and the SSH form is bound
- **THEN** the manifest is uploaded to the control node and `kubectl apply -f` is executed via SSH

#### Scenario: kubectl apply runs locally
- **WHEN** `KubectlService.applyContent()` is called with a manifest and the local form is bound
- **THEN** kubectl runs `apply -f -` as a local process with the manifest on stdin
