## MODIFIED Requirements

### Requirement: Scripts source a gitignored .env file at project root
`bin/easy-db-lab` SHALL source `$PROJECT_ROOT/.env` at startup if the file exists. Variables already set in the shell environment SHALL NOT be overwritten. The file SHALL be loaded with `set -a` so all assignments are automatically exported.

#### Scenario: .env file exists with AWS_PROFILE set
- **WHEN** a developer creates `.env` containing `AWS_PROFILE=my-profile` and runs `bin/easy-db-lab`
- **THEN** the script uses `AWS_PROFILE=my-profile` for all AWS operations

#### Scenario: .env file does not exist
- **WHEN** no `.env` file is present at project root
- **THEN** the script starts normally without error; environment variables come from the shell

#### Scenario: variable already set in shell takes precedence
- **WHEN** the shell has `AWS_PROFILE=shell-profile` and `.env` contains `AWS_PROFILE=env-profile`
- **THEN** the script uses `AWS_PROFILE=shell-profile` (shell wins; `.env` is only a fallback)

### Requirement: .env.example documents all supported variables
A committed `.env.example` file SHALL list every variable that `bin/easy-db-lab` recognises from the environment, with a comment explaining each one and a safe placeholder value.

#### Scenario: developer sets up a new workstation
- **WHEN** a developer clones the repository
- **THEN** they can copy `.env.example` to `.env` and fill in their values without reading the scripts

#### Scenario: new environment variable is added to a script
- **WHEN** a developer adds a new environment variable to `bin/easy-db-lab`
- **THEN** they SHALL also add it to `.env.example` with a comment and placeholder
