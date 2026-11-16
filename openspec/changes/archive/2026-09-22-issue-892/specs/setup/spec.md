# Setup Spec

## MODIFIED Requirements

### Requirement: Profile Setup

The system MUST provide an interactive workflow to create a user profile with AWS credentials,
region, and key pair configuration.

The workflow SHALL be reached as `easy-db-lab profile setup`. The former top-level names
`setup-profile` and `setup` SHALL NOT resolve.

#### Scenario: First-time user runs setup

- **GIVEN** a first-time user
- **WHEN** they run `easy-db-lab profile setup`
- **THEN** they are prompted for AWS profile or credentials, region, and key pair selection.

#### Scenario: Existing profile reviewed on re-run

- **GIVEN** a completed profile
- **WHEN** the user runs `easy-db-lab profile setup` again
- **THEN** existing settings are available for review and modification.

#### Scenario: Former top-level names no longer resolve

- **WHEN** the user runs `easy-db-lab setup-profile` or `easy-db-lab setup`
- **THEN** the CLI does not resolve either as a top-level command.

#### Scenario: Guidance names the current command

- **GIVEN** any CLI message that directs the user to configure their profile
- **WHEN** that message is shown
- **THEN** it names `easy-db-lab profile setup`.
