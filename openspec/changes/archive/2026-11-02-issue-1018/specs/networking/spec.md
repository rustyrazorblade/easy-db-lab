## ADDED Requirements

### Requirement: Operator manages SSH ingress on a running cluster

The system SHALL provide `ingress allow` and `ingress revoke`, run from a cluster workspace, that add and remove an SSH ingress rule (tcp, port 22 to 22) on the workspace cluster's security group, found through `InfrastructureState.securityGroupId` in the cluster state. Each action SHALL take a repeatable `--cidr <ip-or-cidr>` option. A bare IPv4 address SHALL mean that address `/32`. With no `--cidr`, the action SHALL use the operator's detected public IP as a `/32`. Every value SHALL be parsed and validated before any AWS call; one invalid value SHALL stop the whole command with nothing changed. Values that are the same CIDR after normalizing SHALL be treated as one value. Each action SHALL change only the tcp/22 rule for each given CIDR, never another SSH rule, the VPC-internal tcp, udp and ICMP rules, or egress. Each per-CIDR outcome and each failure SHALL be reported as a typed `Ingress` event. The command SHALL exit non-zero when any value fails or any precondition is not met. The commands SHALL NOT need the SOCKS tunnel.

#### Scenario: Allow with no value adds the detected public IP

- **WHEN** the operator runs `ingress allow` with no `--cidr` in a workspace whose cluster state has a security group
- **THEN** an SSH ingress rule for the detected public IP `/32` is added to that security group
- **AND** an `Ingress.RuleAdded` event names the CIDR, the security group, and that the CIDR is the detected public IP

#### Scenario: A bare IP becomes a /32

- **WHEN** the operator runs `ingress allow --cidr 203.0.113.7`
- **THEN** the rule added is for `203.0.113.7/32`

#### Scenario: A CIDR is used exactly

- **WHEN** the operator runs `ingress allow --cidr 198.51.100.0/24`
- **THEN** the rule added is for exactly `198.51.100.0/24`

#### Scenario: Repeated values each get a rule

- **WHEN** the operator runs `ingress allow --cidr 203.0.113.7 --cidr 198.51.100.0/24`
- **THEN** a rule is added for each CIDR
- **AND** one event is emitted for each CIDR

#### Scenario: Duplicate values collapse after normalizing

- **WHEN** the operator runs `ingress allow --cidr 203.0.113.7 --cidr 203.0.113.7/32`
- **THEN** one rule change is attempted for `203.0.113.7/32`
- **AND** one event is emitted for it

#### Scenario: Allowing an existing rule changes nothing

- **WHEN** the operator runs `ingress allow` for a CIDR that already has a tcp/22 rule on the security group
- **THEN** the command succeeds and the security group is not changed
- **AND** an `Ingress.RuleAlreadyPresent` event names the CIDR

#### Scenario: Revoke with no value removes the detected public IP

- **WHEN** the operator runs `ingress revoke` with no `--cidr` and the detected public IP `/32` has a tcp/22 rule
- **THEN** that rule is removed
- **AND** an `Ingress.RuleRemoved` event names the CIDR and that it is the detected public IP

#### Scenario: Revoke removes only the matching rule

- **GIVEN** a security group with tcp/22 rules for `203.0.113.7/32` and `198.51.100.0/24`, and the VPC-internal tcp, udp and ICMP rules
- **WHEN** the operator runs `ingress revoke --cidr 203.0.113.7`
- **THEN** only the tcp/22 rule for `203.0.113.7/32` is removed
- **AND** the tcp/22 rule for `198.51.100.0/24`, the VPC-internal rules and egress are unchanged

#### Scenario: Revoke of a missing rule fails and names it

- **WHEN** the operator runs `ingress revoke --cidr 192.0.2.1 --cidr 203.0.113.7` and only `203.0.113.7/32` has a tcp/22 rule
- **THEN** the rule for `203.0.113.7/32` is removed
- **AND** an `Ingress.RuleNotFound` event names `192.0.2.1/32`
- **AND** the command exits non-zero with a message that names `192.0.2.1/32`

#### Scenario: An invalid value stops the command before any AWS call

- **WHEN** the operator runs `ingress allow --cidr 203.0.113.7 --cidr 300.1.1.1 --cidr foo`
- **THEN** no AWS call is made and no rule is added, not even for `203.0.113.7`
- **AND** one `Ingress.InvalidCidrs` event names `300.1.1.1` and `foo`, each with its reason
- **AND** the command exits non-zero

#### Scenario: No cluster security group in state

- **WHEN** the operator runs `ingress allow` or `ingress revoke` outside a workspace, or in a workspace whose cluster state has no security group
- **THEN** no AWS call is made
- **AND** an `Ingress.NotInWorkspace` or `Ingress.NoSecurityGroupInState` event is emitted and the command exits non-zero

#### Scenario: The security group no longer exists in AWS

- **WHEN** the cluster state names a security group that AWS reports does not exist
- **THEN** an `Ingress.SecurityGroupNotFound` event names the security group ID and the command exits non-zero
- **AND** the cluster state is not changed

#### Scenario: Public IP detection fails

- **WHEN** the operator runs `ingress allow` or `ingress revoke` with no `--cidr` and the public IP cannot be detected
- **THEN** no AWS call is made
- **AND** an `Ingress.PublicIpDetectionFailed` event says detection failed and that `--cidr` can be given
- **AND** the command exits non-zero

#### Scenario: An AWS error on one CIDR does not stop the others

- **WHEN** the operator runs `ingress allow` with several values and AWS rejects the rule for one of them
- **THEN** the rules for the other values are still added
- **AND** an `Ingress.RuleChangeFailed` event names the rejected CIDR, the action and AWS's reason
- **AND** the command exits non-zero

#### Scenario: Revoking the operator's own IP is allowed

- **WHEN** the operator runs `ingress revoke` for their own current public IP
- **THEN** the rule is removed with no confirmation prompt

#### Scenario: An open cluster's world rule is a normal CIDR

- **GIVEN** a cluster created with `init --open`
- **WHEN** the operator runs `ingress allow --cidr 203.0.113.7`, then `ingress revoke --cidr 0.0.0.0/0`
- **THEN** the rule for `203.0.113.7/32` is added and the `0.0.0.0/0` rule is removed

#### Scenario: Ingress commands do not start the tunnel

- **WHEN** the operator runs `ingress allow` or `ingress revoke` and no SOCKS tunnel is running
- **THEN** the command does not start a tunnel and makes only AWS API calls

### Requirement: IPv4 CIDR values are parsed strictly

The system SHALL parse every IPv4 CIDR value given by the operator with one shared parser. A value SHALL be four decimal octets, each 0 to 255 with no sign, no leading zero and no whitespace, optionally followed by `/` and a prefix length 0 to 32. A value with host bits set beyond its prefix SHALL be rejected, and the error SHALL name the network form. A rejected value's error SHALL name the value. IPv6 SHALL be rejected. `ingress allow` and `ingress revoke` SHALL accept a bare address as `/32`. `init --cidr` (the VPC block) SHALL require an explicit prefix and SHALL keep its `/20`-or-larger rule.

#### Scenario: Malformed values are rejected

- **WHEN** a value is one of `300.1.1.1`, `10.0.0.0/33`, `foo`, `+1.2.3.4/32`, `01.2.3.4/32`, `1.2.3/24`, ` 1.2.3.4/32` or `::1`
- **THEN** the parser rejects it with an error that names the value

#### Scenario: Host bits set are rejected with the network form

- **WHEN** a value is `198.51.100.7/24`
- **THEN** the parser rejects it with an error that names `198.51.100.0/24`

#### Scenario: init --cidr rejects a bare address with a clear message

- **WHEN** the operator runs `init --cidr 10.0.0.0`
- **THEN** `init` fails before any AWS call with an "Invalid CIDR" message that names `10.0.0.0`, not a number-format error

#### Scenario: init --cidr rejects host bits

- **WHEN** the operator runs `init --cidr 10.0.0.5/16`
- **THEN** `init` fails before any AWS call with a message that names `10.0.0.0/16`

### Requirement: Public IP detection is bounded in time

Detection of the operator's public IP SHALL use a bounded connect timeout and a bounded read timeout, both defined in `Constants`. A timeout SHALL be reported as a detection failure, never as a wait without end.

#### Scenario: A stalled detection server fails in bounded time

- **WHEN** the public IP service accepts the connection and then sends nothing
- **THEN** detection fails after the read timeout
- **AND** `ingress allow` with no `--cidr` reports `Ingress.PublicIpDetectionFailed`

### Requirement: Cluster rule changes are reported once

When `up` adds a rule to the cluster security group, the system SHALL emit `Infra.SecurityGroupRuleConfigured` once for that rule, and SHALL emit nothing for a rule that already exists. The provider layer (`VpcService`) SHALL NOT emit user events for rule changes; it SHALL return whether each rule was added or already present. The same holds for the Packer and account-compactor security group paths.

#### Scenario: up reports each added rule once

- **WHEN** `up` creates the cluster security group and adds its SSH, VPC tcp, VPC udp and ICMP rules
- **THEN** one `Infra.SecurityGroupRuleConfigured` event is emitted for each added rule, with the same description as before this change

#### Scenario: ingress allow prints one line per CIDR

- **WHEN** `ingress allow` adds a rule
- **THEN** only the `Ingress.RuleAdded` event is emitted for it, not also `Infra.SecurityGroupRuleConfigured`

### Requirement: The changed-IP hint names ingress allow

On the `direct` transport, when the cluster is not open, the tunnel connect-failure hint SHALL say that the cluster's security group allows SSH only from the IPs it lists, that a changed public IP is the likely cause, and that `easy-db-lab ingress allow` adds the current public IP.

#### Scenario: The hint names the fix

- **WHEN** a fresh tunnel on the `direct` transport of a cluster that is not open is still connecting when the verify window ends
- **THEN** the error names `easy-db-lab ingress allow` as the way to add the current public IP
- **AND** the error does not say that SSH is allowed only from the IP recorded when `up` ran
