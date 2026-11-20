## MODIFIED Requirements

### Requirement: TiDB Kit Component Placement

TiKV and TiFlash SHALL be scheduled on the db role's nodes. TiDB SQL layer and PD SHALL be scheduled on the app role's nodes. Scheduling SHALL be enforced by `nodeSelector` in the `TidbCluster` manifest, set from `__DB_NODE_SELECTOR__` and `__APP_NODE_SELECTOR__`: `type=<role>` on the nodes easy-db-lab builds, the `init --role-nodes` mapping, or no constraint when the role is unconstrained. The manifest SHALL NOT name a `type` label itself.

#### Scenario: TiKV scheduled on db nodes

- **WHEN** the TidbCluster is applied on an AWS cluster
- **THEN** TiKV pods are scheduled exclusively on nodes labelled `type=db`.

#### Scenario: TiFlash scheduled on db nodes

- **WHEN** the TidbCluster is applied on an AWS cluster
- **THEN** TiFlash pods are scheduled exclusively on nodes labelled `type=db`.

#### Scenario: TiDB and PD scheduled on app nodes

- **WHEN** the TidbCluster is applied on an AWS cluster
- **THEN** TiDB SQL layer pods and PD pods are scheduled exclusively on nodes labelled `type=app`.

#### Scenario: Unconstrained roles schedule anywhere

- **WHEN** the TidbCluster is rendered with both roles unconstrained
- **THEN** no component carries a role `nodeSelector` or node affinity.
