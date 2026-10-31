## ADDED Requirements

### Requirement: Filesystem storage compacts in the backends' own processes with retention off
In `Filesystem` observability storage, the account compactor SHALL NOT be created, started or stopped.  Compaction SHALL instead run in the backends' processes:
- Mimir SHALL run its compactor module with the same compactor flags as the account compactor, from one shared definition: `blocks-retention-period=0`, `partial-block-deletion-delay=0`, the 10m/2h/12h/24h ranges, and a 1-minute cleanup interval that writes every tenant's bucket index.
- Loki's compactor SHALL compact every 10 minutes, with retention disabled and deletion mode `disabled`.
- Tempo SHALL run with compaction off.
- Pyroscope SHALL keep its in-process compaction, with retention cleanup off.

No local compactor SHALL remove data except source objects that a merged copy holding their data has replaced.

#### Scenario: Compaction removes only merged sources
- **WHEN** the local compactors run over data under D
- **THEN** the total samples, log lines and profiles readable from D are unchanged
- **AND** no retention or expiry is in effect

#### Scenario: Mimir writes the bucket index locally
- **WHEN** Mimir runs with `Filesystem` storage and ships a block
- **THEN** `D/mimir/<tenant>/bucket-index.json.gz` is written, and the store-gateway can query the block once it leaves the local hold

#### Scenario: The account compactor is not touched
- **WHEN** `up` or `down` runs with `Filesystem` storage
- **THEN** no ECS, IAM or CloudWatch call is made for the account compactor

#### Scenario: AWS and local compactor flags do not drift
- **WHEN** the Mimir compactor flags are rendered for the account compactor and for local Mimir
- **THEN** both carry the same retention, deletion-delay, range and cleanup values
