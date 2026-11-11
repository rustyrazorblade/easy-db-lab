## Overrides existing behavior

None — this change only adds new requirements.

Behavior that changes without a `MODIFIED` section, because no baseline requirement states it:

- `init --cidr` now rejects a bare address (with "Invalid CIDR" instead of a `NumberFormatException`) and rejects host bits (naming the network form). No baseline requirement specifies `init --cidr` parsing.
- `Infra.SecurityGroupRuleConfigured` is now emitted by `AwsInfrastructureService`, not the provider. The baseline `networking: Cluster security group permits ICMP within the VPC` scenario "ICMP rule is described correctly" still holds: the event is still emitted once on add, with the same "all ICMP types" description.

## Conflicts with other in-flight changes

- `issue-1000` also touches `networking` — its ADDED requirement "SOCKS tunnel reconnects on its own" has the scenario "A fresh tunnel still connecting when the verify window ends names the stage", which says "the error says the cluster's security group allows SSH only from the public IP recorded when `up` ran". This change's "The changed-IP hint names ingress allow" requirement replaces that wording. Not a hard conflict: the requirement is not in the baseline yet, so this change cannot `MODIFY` it. When `issue-1000` is archived first, that one `AND` line must be updated to match this change's hint before or while `issue-1018` is archived.
- `ssm-ssh-transport` also touches `networking` — no actual conflict; it adds "SSH transport over SSM Session Manager", a different requirement.
