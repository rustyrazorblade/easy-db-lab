## ADDED Requirements

### Requirement: Kubelet ECR credential provider on every node
The base AMI SHALL install the kubelet ECR credential provider (the `ecr-credential-provider` binary and a `CredentialProviderConfig` that matches the account's ECR registry hosts, `*.dkr.ecr.*.amazonaws.com`), and every K3s node (server and agent) SHALL run its kubelet with that provider. Any pod on any node SHALL pull an image from the account's ECR with the node instance role, with no image pull secret, and the kubelet SHALL get a registry token from the provider when it pulls, so no stored credential can expire. No easy-db-lab code SHALL create an ECR image pull secret.

#### Scenario: Provider installed in the base AMI
- **WHEN** the base image is built, or its install script runs in the packer test container
- **THEN** the `ecr-credential-provider` binary and its `CredentialProviderConfig` are installed where the K3s kubelet reads them
- **AND** the config matches `*.dkr.ecr.*.amazonaws.com`

#### Scenario: A pod pulls from ECR with no pull secret
- **WHEN** a pod with a private image in the account's ECR, `imagePullPolicy: Always` and no `imagePullSecrets` is scheduled on any node
- **THEN** the image pulls and the pod starts
- **AND** the pod does not fail with `no basic auth credentials`

#### Scenario: No pull secret is created
- **WHEN** any easy-db-lab command deploys a workload that uses an ECR image
- **THEN** no `ecr-pull-secret` Secret is created
