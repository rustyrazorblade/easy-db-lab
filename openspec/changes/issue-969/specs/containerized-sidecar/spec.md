## ADDED Requirements

### Requirement: Sidecar pulls custom ECR images through the node credential provider
A sidecar DaemonSet that runs a custom image from the account's ECR (`cassandra start --sidecar-image=...`) SHALL pull it through the node's kubelet ECR credential provider. The sidecar deploy path SHALL NOT create an `ecr-pull-secret` Secret and SHALL NOT set `imagePullSecrets` on the DaemonSet's pod spec.

#### Scenario: Custom ECR sidecar image pulls with no pull secret
- **WHEN** the user runs `cassandra start --sidecar-image=<account>.dkr.ecr.<region>.amazonaws.com/<repo>:<tag>`
- **THEN** every sidecar pod pulls the image and runs
- **AND** no `ecr-pull-secret` Secret exists and the DaemonSet's pod spec has no `imagePullSecrets`
