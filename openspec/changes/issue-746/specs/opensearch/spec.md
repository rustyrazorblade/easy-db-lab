## ADDED Requirements

### Requirement: Missing OpenSearch domain is a value, not an exception

The OpenSearch service SHALL report a domain lookup as a project-owned value: found, not found, or unavailable with a reason. It SHALL NOT surface an SDK exception for a missing domain or an AWS failure, so that callers outside the AWS adapter need no SDK types.

#### Scenario: Status of a domain that does not exist

- **GIVEN** a cluster whose recorded OpenSearch domain does not exist
- **WHEN** the user runs `opensearch status`
- **THEN** the output is the same as today's not-found output

#### Scenario: Status when AWS cannot be reached

- **GIVEN** a cluster with a recorded OpenSearch domain
- **AND** the domain lookup fails with an AWS error
- **WHEN** the user runs `opensearch status`
- **THEN** the output is today's warning, carrying the AWS error's message
