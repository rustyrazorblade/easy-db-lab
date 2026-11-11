## ADDED Requirements

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
