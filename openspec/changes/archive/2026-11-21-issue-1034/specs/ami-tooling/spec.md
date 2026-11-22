## MODIFIED Requirements

### Requirement: HelmService executes helm on the control node via SSH
`HelmService` SHALL run `helm` commands through `ClusterTooling`, not by calling `RemoteOperationsService` or `ProcessBuilder` directly.  The SSH form runs helm on the control node with the control-node KUBECONFIG.

#### Scenario: helm upgrade --install runs remotely
- **WHEN** `HelmService.upgradeInstall()` is called with a chart and a values file
- **THEN** the helm command is executed on the control node via SSH
- **AND** the KUBECONFIG on the control node is used (not a locally-passed env var)
- **AND** the values reach helm through `--values` and the uploaded input file

### Requirement: KubectlService executes kubectl on the control node via SSH
`KubectlService` SHALL run `kubectl` commands through `ClusterTooling`, not by calling `RemoteOperationsService` or `ProcessBuilder` directly.  The SSH form runs kubectl on the control node with the control-node KUBECONFIG.

#### Scenario: kubectl apply runs remotely
- **WHEN** `KubectlService.applyContent()` is called with a manifest
- **THEN** the manifest is uploaded to the control node and `kubectl apply -f` is executed via SSH
