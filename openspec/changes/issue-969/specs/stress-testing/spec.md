## ADDED Requirements

### Requirement: Stress jobs pull custom ECR images through the node credential provider
A stress job that runs a custom image from the account's ECR (`cassandra stress start --image=...`) SHALL pull it through the node's kubelet ECR credential provider. The stress job path SHALL NOT create an `ecr-pull-secret` Secret and SHALL NOT set `imagePullSecrets` on the job's pod spec.

#### Scenario: Custom ECR stress image pulls with no pull secret
- **WHEN** the user runs `cassandra stress start --image=<account>.dkr.ecr.<region>.amazonaws.com/<repo>:<tag>`
- **THEN** the stress pod pulls the image and runs
- **AND** no `ecr-pull-secret` Secret exists and the job's pod spec has no `imagePullSecrets`
