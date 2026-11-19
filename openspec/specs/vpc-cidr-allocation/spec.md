# vpc-cidr-allocation Specification

## Purpose
TBD - created by archiving change auto-select-vpc-cidr. Update Purpose after archive.
## Requirements
### Requirement: Auto-select VPC CIDR when not specified
The system SHALL automatically select a non-conflicting `10.X.0.0/16` CIDR block when the user does not provide `--cidr` at init time. Selection SHALL occur at `up` time by querying existing VPCs in the target region and choosing a random unused second octet (0–254). If creating the VPC fails, the system SHALL retry with a new random unused second octet, excluding the one that failed, for a small fixed number of attempts. A CIDR given with `--cidr` SHALL be used as-is and SHALL NOT be retried.

#### Scenario: CIDR auto-selected when --cidr is omitted
- **WHEN** the user runs `init` without `--cidr`
- **THEN** `state.json` stores a null CIDR (auto-select deferred to up time)

#### Scenario: Auto-selection chooses a random non-conflicting block
- **WHEN** `up` runs with a null CIDR and existing VPCs occupy `10.0.0.0/16` and `10.1.0.0/16`
- **THEN** the new VPC is created with a CIDR `10.X.0.0/16` where X is chosen at random from the unused second octets (never 0 or 1)

#### Scenario: Failed VPC creation retries with a new random block
- **WHEN** the CIDR was auto-selected and creating the VPC fails
- **THEN** `up` retries with a different random unused block, excluding the one that failed, up to the fixed attempt limit

#### Scenario: User is informed of auto-selected CIDR
- **WHEN** a CIDR is auto-selected during `up`
- **THEN** an event is emitted displaying the chosen CIDR (e.g. `Auto-selected CIDR: 10.2.0.0/16`)

#### Scenario: Explicit --cidr overrides auto-selection
- **WHEN** the user passes `--cidr 172.16.0.0/16` to `init`
- **THEN** that CIDR is used as-is; no auto-selection occurs AND a failed VPC creation is not retried

#### Scenario: Exhausted CIDR space fails fast
- **WHEN** all second octets 0–254 are already occupied by existing VPCs in the region
- **THEN** `up` fails with a clear error message indicating no available CIDR blocks remain

### Requirement: The VPC is split into eight equal subnets, one per availability zone

The system SHALL create one subnet per availability zone of a cluster. The subnets SHALL come from a fixed split of the VPC CIDR into eight equal subnets, each with a prefix three bits longer than the VPC prefix, so the default `/16` VPC gives `/19` subnets. The split SHALL NOT depend on how many availability zones the cluster uses. The subnet for zone index `i` (0 to 7) SHALL start at the VPC network address plus `i * 2^(32 - subnetPrefix)`. A cluster SHALL use at most eight availability zones; `init` SHALL reject a ninth before any AWS call, with a message that names the limit. The VPC prefix SHALL be `/25` or larger, so each subnet is at least `/28`, the smallest subnet AWS allows. The Packer build VPC SHALL use the first subnet of the default VPC CIDR. The subnets are this large because Cilium's ENI IPAM takes pod IPs from the same subnets as the nodes, so a cluster of 1,000 or more nodes needs thousands of addresses per zone.

#### Scenario: A /16 VPC gives eight /19 subnets

- **WHEN** the VPC CIDR is `10.0.0.0/16`
- **THEN** the subnets for zone indexes 0 to 7 are `10.0.0.0/19`, `10.0.32.0/19`, `10.0.64.0/19`, `10.0.96.0/19`, `10.0.128.0/19`, `10.0.160.0/19`, `10.0.192.0/19` and `10.0.224.0/19`

#### Scenario: A three-zone cluster uses the first three subnets

- **WHEN** `up` creates a cluster in zones `a`, `b` and `c` with VPC CIDR `172.16.0.0/16`
- **THEN** the subnets are `172.16.0.0/19`, `172.16.32.0/19` and `172.16.64.0/19`

#### Scenario: The split carries across octets for other VPC sizes

- **WHEN** the VPC CIDR is `10.0.0.128/25`
- **THEN** the subnet for zone index 7 is `10.0.0.240/28`

#### Scenario: A ninth availability zone fails fast

- **WHEN** the operator runs `init --azs abcdefghi`
- **THEN** `init` fails before any AWS call with a message that a cluster can use at most 8 availability zones

#### Scenario: A VPC prefix longer than /25 is rejected

- **WHEN** the operator runs `init --cidr 10.0.0.0/26`
- **THEN** `init` fails before any AWS call with a message that the CIDR prefix must be `/25` or larger

#### Scenario: The Packer VPC uses the first default subnet

- **WHEN** the Packer build infrastructure is created
- **THEN** its single subnet is `10.0.0.0/19`

