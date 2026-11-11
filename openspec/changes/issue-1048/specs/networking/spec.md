## MODIFIED Requirements

### Requirement: Cluster rule changes are reported once

When `up` adds a rule to the cluster security group, the system SHALL emit `Infra.SecurityGroupRuleConfigured` once for that rule, and SHALL emit nothing for a rule that already exists. The provider layer (`VpcService`) SHALL NOT emit user events for rule changes; it SHALL return whether each rule was added or already present. The same holds for the Packer security group path.

#### Scenario: up reports each added rule once

- **WHEN** `up` creates the cluster security group and adds its SSH, VPC tcp, VPC udp and ICMP rules
- **THEN** one `Infra.SecurityGroupRuleConfigured` event is emitted for each added rule, with the same description as before this change

#### Scenario: ingress allow prints one line per CIDR

- **WHEN** `ingress allow` adds a rule
- **THEN** only the `Ingress.RuleAdded` event is emitted for it, not also `Infra.SecurityGroupRuleConfigured`


### Requirement: IPv4 CIDR values are parsed strictly

The system SHALL parse every IPv4 CIDR value given by the operator with one shared parser. A value SHALL be four decimal octets, each 0 to 255 with no sign, no leading zero and no whitespace, optionally followed by `/` and a prefix length 0 to 32. A value with host bits set beyond its prefix SHALL be rejected, and the error SHALL name the network form. A rejected value's error SHALL name the value. IPv6 SHALL be rejected. `ingress allow` and `ingress revoke` SHALL accept a bare address as `/32`. `init --cidr` (the VPC block) SHALL require an explicit prefix and SHALL require a prefix of `/25` or larger, so that its eight subnets are each at least `/28` (see `vpc-cidr-allocation`).

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

Detection of the operator's public IP SHALL ask `https://checkip.amazonaws.com/` first and SHALL fall back to `https://api.ipify.org/` when the first service fails or answers with anything that is not a bare IPv4 address once surrounding whitespace is trimmed. Both URLs SHALL be defined in `Constants`. Each request SHALL use a bounded connect timeout and a bounded read timeout, both defined in `Constants`. A timeout SHALL be reported as a detection failure, never as a wait without end. When both services fail, detection SHALL fail.

#### Scenario: A stalled detection server fails in bounded time

- **WHEN** the public IP service accepts the connection and then sends nothing
- **THEN** detection fails after the read timeout
- **AND** `ingress allow` with no `--cidr` reports `Ingress.PublicIpDetectionFailed`

#### Scenario: The trailing newline is trimmed

- **WHEN** `checkip.amazonaws.com` answers `203.0.113.10` followed by a newline
- **THEN** the detected address is `203.0.113.10` and `api.ipify.org` is not asked

#### Scenario: A failed or invalid first answer falls back

- **WHEN** `checkip.amazonaws.com` fails, or answers with something that is not an IPv4 address
- **THEN** detection uses the answer from `api.ipify.org`

#### Scenario: Both services fail

- **WHEN** both services fail or answer with something that is not an IPv4 address
- **THEN** detection fails, and `ingress allow` with no `--cidr` reports `Ingress.PublicIpDetectionFailed`
