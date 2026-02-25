# Setup

Manages user profile creation, AWS credential configuration, and IAM resource initialization required before cluster provisioning.

## Requirements

### REQ-SU-001: Profile Setup

The system MUST provide an interactive workflow to create a user profile with AWS credentials, region, and key pair configuration.

**Scenarios:**

- **GIVEN** a first-time user, **WHEN** they run the setup workflow, **THEN** they are prompted for AWS profile or credentials, region, and key pair selection.
- **GIVEN** a completed profile, **WHEN** the user runs setup again, **THEN** existing settings are available for review and modification.

### REQ-SU-002: AWS Resource Initialization

The system MUST initialize required AWS resources (IAM roles, S3 bucket, VPC) as part of setup.

**Scenarios:**

- **GIVEN** valid AWS credentials, **WHEN** the user completes setup, **THEN** IAM roles, an S3 bucket, and networking resources are created or verified.
- **GIVEN** partially configured AWS resources, **WHEN** setup runs, **THEN** missing resources are created and existing ones are reused.

### REQ-SU-003: IAM Policy Visibility

The system MUST allow users to view the IAM policies required for operation.

**Scenarios:**

- **GIVEN** a user troubleshooting permissions, **WHEN** they request IAM policy display, **THEN** the required policies are shown with account-specific values substituted.

### REQ-SU-004: AWS Reconfiguration

The system MUST allow reconfiguring AWS resources without full profile re-setup.

**Scenarios:**

- **GIVEN** an existing profile, **WHEN** the user reconfigures AWS resources, **THEN** IAM roles and infrastructure are updated without modifying credential settings.
