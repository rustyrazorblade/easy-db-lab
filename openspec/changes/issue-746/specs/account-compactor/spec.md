## ADDED Requirements

### Requirement: The compactor task definition is unchanged by the package move

The account compactor's registered ECS task definition SHALL be identical to today's, including the Tempo backend configuration it carries.

#### Scenario: Registered request is unchanged

- **WHEN** the account compactor registers its task definition
- **THEN** the registered request is identical to today's
- **AND** the Tempo backend configuration resource still loads
