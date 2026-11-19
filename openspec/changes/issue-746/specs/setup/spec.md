## ADDED Requirements

### Requirement: Profile setup checks the region and creates the key pair

Profile setup SHALL accept only a region the AWS SDK knows, and SHALL create the EC2 key pair as today. The key-pair creation SHALL live in the AWS services layer, not in the user configuration model.

#### Scenario: Unknown region is asked for again

- **GIVEN** a region the AWS SDK does not know, such as a blank or misspelt name, entered or saved in the profile
- **WHEN** profile setup reaches the credential check
- **THEN** it reports that the region is not an AWS region, before any AWS call
- **AND** the next attempt asks for the region again

#### Scenario: Unknown region on every attempt

- **GIVEN** an unknown region on every attempt of the credential retry loop
- **WHEN** the last attempt ends
- **THEN** profile setup fails, naming the region, and makes no AWS call

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
