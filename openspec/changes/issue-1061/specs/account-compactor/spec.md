## MODIFIED Requirements

### Requirement: Only the compactor's task role can delete observability objects

The compactor's task role `EasyDBLabCompactorTaskRole` SHALL be allowed to list the account bucket and to get, put and delete objects under `mimir/`, `loki/` and `tempo/`, and SHALL have no access to `grafana/` or `pyroscope/`.  When the store prefix is not empty, the same access SHALL also apply under `<prefix>/mimir/`, `<prefix>/loki/` and `<prefix>/tempo/`, and to no other path.  With an empty store prefix, the task role's policy SHALL be byte-identical to the document in use before the store prefix existed.  The task SHALL use the execution role `EasyDBLabCompactorExecutionRole`.  The user IAM policies SHALL grant what `up`, `down` and the compactor commands need to manage the service, its roles, its log group and its network.

#### Scenario: The task role may delete under the compacted roots
- **WHEN** the compactor task deletes a source object under `mimir/`, `loki/` or `tempo/` after merging it
- **THEN** S3 accepts the delete

#### Scenario: The task role cannot touch other roots
- **WHEN** the compactor task tries to delete an object under `grafana/` or `pyroscope/`
- **THEN** S3 refuses the delete

#### Scenario: The task role policy is unchanged with an empty prefix
- **WHEN** the task role's policy is built with an empty store prefix
- **THEN** it is byte-identical to its golden file captured before the change

#### Scenario: The task role reaches the compacted roots under a prefix
- **WHEN** the task role's policy is built with the store prefix `team-a`
- **THEN** it allows get, put and delete under `team-a/mimir/`, `team-a/loki/` and `team-a/tempo/` as well as the unprefixed roots
- **AND** it allows nothing under `grafana/`, `pyroscope/`, `team-a/grafana/` or `team-a/pyroscope/`
