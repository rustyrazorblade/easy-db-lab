## MODIFIED Requirements

### Requirement: Cluster rule changes are reported once

When `up` adds a rule to the cluster security group, the system SHALL emit `Infra.SecurityGroupRuleConfigured` once for that rule, and SHALL emit nothing for a rule that already exists. The provider layer (`VpcService`) SHALL NOT emit user events for rule changes; it SHALL return whether each rule was added or already present. The same holds for the Packer security group path.

#### Scenario: up reports each added rule once

- **WHEN** `up` creates the cluster security group and adds its SSH, VPC tcp, VPC udp and ICMP rules
- **THEN** one `Infra.SecurityGroupRuleConfigured` event is emitted for each added rule, with the same description as before this change

#### Scenario: ingress allow prints one line per CIDR

- **WHEN** `ingress allow` adds a rule
- **THEN** only the `Ingress.RuleAdded` event is emitted for it, not also `Infra.SecurityGroupRuleConfigured`

