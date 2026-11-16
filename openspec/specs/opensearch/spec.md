# OpenSearch

## Purpose

Manages AWS-managed OpenSearch domain deployment within the cluster VPC.
## Requirements
### Requirement: OpenSearch Deployment

The system MUST support deploying an AWS-managed OpenSearch domain in the cluster's VPC.

#### Scenario: Start OpenSearch domain

- **GIVEN** a running cluster
- **WHEN** the user starts OpenSearch with instance type and count options
- **THEN** an OpenSearch domain is created in the cluster VPC.

#### Scenario: Stop OpenSearch domain

- **GIVEN** a deployed OpenSearch domain
- **WHEN** the user stops it
- **THEN** the domain is deleted.

#### Scenario: Check OpenSearch status

- **GIVEN** a running OpenSearch domain
- **WHEN** the user checks status
- **THEN** the domain state is displayed.

### Requirement: Missing OpenSearch domain is a value, not an exception

The OpenSearch service SHALL report a domain that does not exist as an absent result, not as an SDK exception, so that callers outside the AWS adapter need no SDK types.

#### Scenario: Status of a domain that does not exist

- **GIVEN** a cluster whose recorded OpenSearch domain does not exist
- **WHEN** the user runs `opensearch status`
- **THEN** it prints `OpenSearch domain '<domain>' no longer exists.` and then `Clearing local state.`
- **AND** it removes the domain from the cluster state and saves it
- **AND** with `--endpoint` it prints nothing but still clears the domain from the cluster state

