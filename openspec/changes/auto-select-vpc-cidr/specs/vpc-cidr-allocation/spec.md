## ADDED Requirements

### Requirement: Auto-select VPC CIDR when not specified
The system SHALL automatically select a non-conflicting `10.X.0.0/16` CIDR block when the user does not provide `--cidr` at init time. Selection SHALL occur at `up` time by querying existing VPCs in the target region and choosing the first unused second octet (0–254).

#### Scenario: CIDR auto-selected when --cidr is omitted
- **WHEN** the user runs `init` without `--cidr`
- **THEN** `state.json` stores a null CIDR (auto-select deferred to up time)

#### Scenario: Auto-selection chooses first non-conflicting block
- **WHEN** `up` runs with a null CIDR and existing VPCs occupy `10.0.0.0/16` and `10.1.0.0/16`
- **THEN** the new VPC is created with CIDR `10.2.0.0/16`

#### Scenario: User is informed of auto-selected CIDR
- **WHEN** a CIDR is auto-selected during `up`
- **THEN** an event is emitted displaying the chosen CIDR (e.g. `Auto-selected CIDR: 10.2.0.0/16`)

#### Scenario: Explicit --cidr overrides auto-selection
- **WHEN** the user passes `--cidr 172.16.0.0/16` to `init`
- **THEN** that CIDR is used as-is; no auto-selection occurs

#### Scenario: Exhausted CIDR space fails fast
- **WHEN** all second octets 0–254 are already occupied by existing VPCs in the region
- **THEN** `up` fails with a clear error message indicating no available CIDR blocks remain
