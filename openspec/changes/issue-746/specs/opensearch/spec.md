## ADDED Requirements

### Requirement: Missing OpenSearch domain is a value, not an exception

The OpenSearch service SHALL report a domain that does not exist as an absent result, not as an SDK exception, so that callers outside the AWS adapter need no SDK types.

#### Scenario: Status of a domain that does not exist

- **GIVEN** a cluster whose recorded OpenSearch domain does not exist
- **WHEN** the user runs `opensearch status`
- **THEN** the output is the same as today's not-found output
