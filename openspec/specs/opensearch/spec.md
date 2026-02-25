# OpenSearch

Manages AWS-managed OpenSearch domain deployment within the cluster VPC.

## Requirements

### REQ-OS-001: OpenSearch Deployment

The system MUST support deploying an AWS-managed OpenSearch domain in the cluster's VPC.

**Scenarios:**

- **GIVEN** a running cluster, **WHEN** the user starts OpenSearch with instance type and count options, **THEN** an OpenSearch domain is created in the cluster VPC.
- **GIVEN** a deployed OpenSearch domain, **WHEN** the user stops it, **THEN** the domain is deleted.
- **GIVEN** a running OpenSearch domain, **WHEN** the user checks status, **THEN** the domain state is displayed.
