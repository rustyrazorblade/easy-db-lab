## ADDED Requirements

### Requirement: Helm is installed on cluster nodes via AMI
Helm SHALL be installed on all cluster nodes at AMI build time. The version SHALL be pinned in the packer install script. The binary SHALL be available at `/usr/local/bin/helm`.

#### Scenario: Helm available on control node
- **WHEN** a cluster is provisioned using the new AMI
- **THEN** `helm version` executes successfully on the control node via SSH

### Requirement: kubectl is installed on cluster nodes via AMI
kubectl SHALL be installed on all cluster nodes at AMI build time. The version SHALL be pinned. The binary SHALL be available in the system PATH.

#### Scenario: kubectl available on control node
- **WHEN** a cluster is provisioned using the new AMI
- **THEN** `kubectl version --client` executes successfully on the control node via SSH

### Requirement: Cilium CLI is installed on cluster nodes via AMI
The cilium CLI SHALL be installed on all cluster nodes at AMI build time. The version SHALL be pinned to match the Cilium CNI version deployed on the cluster. The binary SHALL be available in the system PATH.

#### Scenario: Cilium CLI available on control node
- **WHEN** a cluster is provisioned using the new AMI
- **THEN** `cilium version` executes successfully on the control node via SSH

### Requirement: Cilium is installed via cilium CLI, not Helm
The `CiliumService` SHALL install Cilium CNI using `cilium install` executed on the control node via SSH, rather than using `helm upgrade --install` locally.

#### Scenario: Cilium install runs on control node
- **WHEN** `CiliumService.install()` is called
- **THEN** `cilium install` is executed on the control node via SSH with the configured Cilium version
- **AND** the command completes successfully and Cilium pods reach Running state

#### Scenario: Cilium install fails if cilium CLI not available
- **WHEN** `CiliumService.install()` is called and `cilium` is not on the control node PATH
- **THEN** the command fails with an informative error (exit code 127)
- **AND** the failure is propagated to the caller

### Requirement: HelmService executes helm on the control node via SSH
`HelmService` SHALL run `helm` commands on the control node via `RemoteOperationsService`, not via `ProcessBuilder` locally.

#### Scenario: helm upgrade --install runs remotely
- **WHEN** `HelmService.upgradeInstall()` is called with a chart and values
- **THEN** the helm command is executed on the control node via SSH
- **AND** the KUBECONFIG on the control node is used (not a locally-passed env var)

### Requirement: KubectlService executes kubectl on the control node via SSH
`KubectlService` SHALL run `kubectl` commands on the control node via `RemoteOperationsService`, not via `ProcessBuilder` locally.

#### Scenario: kubectl apply runs remotely
- **WHEN** `KubectlService.apply()` is called with a manifest
- **THEN** the manifest is uploaded to the control node and `kubectl apply -f` is executed via SSH
