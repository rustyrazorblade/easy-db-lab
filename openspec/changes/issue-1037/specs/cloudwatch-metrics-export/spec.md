## ADDED Requirements

### Requirement: YACE runs only where CloudWatch exists

The system SHALL deploy YACE only in an environment whose capabilities include CloudWatch. Elsewhere it SHALL NOT be deployed, and the deploy SHALL report that it was skipped and why.

#### Scenario: YACE skipped without CloudWatch

- **GIVEN** an environment without CloudWatch
- **WHEN** the observability stack deploys
- **THEN** no YACE ConfigMap or Deployment is applied
- **AND** the output says YACE was skipped because the environment has no CloudWatch

#### Scenario: YACE deployed on AWS

- **GIVEN** an AWS environment
- **WHEN** the observability stack deploys
- **THEN** the YACE ConfigMap and Deployment are applied, as today
