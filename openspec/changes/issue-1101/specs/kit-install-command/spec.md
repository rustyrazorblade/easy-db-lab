## MODIFIED Requirements

### Requirement: Kit node-type requirement field
A kit MAY declare `type: db` or `type: app` in `kit.yaml`; when declared, the cluster MUST have at least
one node of that role before installation proceeds. The role's nodes are the nodes matching its role
selector (`type=<role>` on the nodes easy-db-lab builds, the `init --role-nodes` mapping, or every node
when the role is unconstrained), read from the Kubernetes API, not hosts in `state.json`. The field is
optional — a kit without it has no node-type requirement. The check runs at the start of `kit install`,
before any templates are rendered or files are written, so a failure leaves nothing on disk.

#### Scenario: Install fails when required node pool is absent
- **GIVEN** a kit declares `type: app`
- **WHEN** the user runs `easy-db-lab kit install <kit>` and no node matches the app role's selector
- **THEN** the install fails immediately with `Event.Kit.RequirementNotMet`, naming the selector that matched no node
- **THEN** no files are written to disk

#### Scenario: Install proceeds when required node pool is present
- **GIVEN** a kit declares `type: db`
- **WHEN** the user runs `easy-db-lab kit install <kit>` and at least one node matches the db role's selector
- **THEN** the install proceeds normally

#### Scenario: An unconstrained role is met by any node
- **GIVEN** a kit declares `type: app` and the app role is unconstrained (`init --role-nodes app=`)
- **WHEN** the user runs `easy-db-lab kit install <kit>` on a cluster with one unlabelled node
- **THEN** the install proceeds normally

#### Scenario: No type field means no requirement check
- **GIVEN** a kit declares no `type` field
- **WHEN** the user runs `easy-db-lab kit install <kit>`
- **THEN** no node-type check is performed
