## ADDED Requirements

### Requirement: Kit node-type requirement field
A kit MAY declare `type: db` or `type: app` in `kit.yaml`; when declared, the cluster MUST have at least
one node of that type before installation proceeds. The field is optional — a kit without it has no
node-type requirement. The check runs at the start of `install`, before any templates are rendered or
files are written, so a failure leaves nothing on disk.

#### Scenario: Install fails when required node pool is absent
- **GIVEN** a kit declares `type: app`
- **WHEN** the user runs `easy-db-lab install <kit>` and the cluster has no app nodes
- **THEN** the install fails immediately with `Event.Kit.RequirementNotMet`
- **THEN** no files are written to disk

#### Scenario: Install proceeds when required node pool is present
- **GIVEN** a kit declares `type: db`
- **WHEN** the user runs `easy-db-lab install <kit>` and the cluster has at least one db node
- **THEN** the install proceeds normally

#### Scenario: No type field means no requirement check
- **GIVEN** a kit declares no `type` field
- **WHEN** the user runs `easy-db-lab install <kit>`
- **THEN** no node-type check is performed
