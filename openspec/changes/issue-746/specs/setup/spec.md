## ADDED Requirements

### Requirement: Profile setup region and key pair behave as today

Profile setup SHALL validate the region and create the EC2 key pair exactly as today. The key-pair creation SHALL live in the AWS services layer, not in the user configuration model.

#### Scenario: Invalid region is rejected as today

- **GIVEN** a region value that profile setup rejects today
- **WHEN** the user enters it during `easy-db-lab profile setup`
- **THEN** it is rejected with the same error as today

#### Scenario: Valid region creates the key pair

- **GIVEN** a valid region
- **WHEN** profile setup reaches the key-pair step
- **THEN** it creates the key pair and saves the private key as today

### Requirement: Setup classifies credential failures like any command

Profile setup's credential check SHALL classify an AWS failure with the same provider error handler as command failures: credentials errors show the credentials guidance, and permission errors show the IAM policy guidance.

#### Scenario: Setup with expired SSO credentials

- **GIVEN** an SSO-backed profile whose session has expired
- **WHEN** profile setup validates credentials
- **THEN** it shows the credentials guidance once, not the permission guidance
