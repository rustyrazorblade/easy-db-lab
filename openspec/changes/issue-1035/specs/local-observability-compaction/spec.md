## ADDED Requirements

### Requirement: A filesystem store compacts in the backends' own processes with retention off
On a filesystem store, the account compactor SHALL NOT be deployed, and its stage SHALL be reported as skipped. Compaction SHALL instead run in the backends' own processes:
- Mimir SHALL run its compactor module with the same flags as the account compactor, from one shared definition: `blocks-retention-period=0`, `partial-block-deletion-delay=0`, the 10m/2h/12h/24h ranges, and a 1-minute cleanup interval that writes every tenant's bucket index.
- Loki's compactor SHALL compact every 10 minutes, with retention disabled and deletion mode `disabled`.
- Tempo SHALL run with compaction off.
- Pyroscope SHALL keep its in-process compaction, with retention cleanup off.

No local compactor SHALL remove data except source objects that a merged copy holding their data has replaced.

#### Scenario: Compaction removes only merged sources
- **WHEN** the local compactors run over data under D
- **THEN** the total samples, log lines and profiles readable from D are unchanged
- **AND** no retention or expiry is in effect

#### Scenario: Mimir writes the bucket index locally
- **WHEN** Mimir runs on a filesystem store and ships a block
- **THEN** `D/mimir/<tenant>/bucket-index.json.gz` is written, and the store-gateway can query the block after it leaves the local hold

#### Scenario: The account compactor is not deployed
- **WHEN** `up` runs on a filesystem store
- **THEN** no account-compactor workload or lease is created, and the stage is reported as skipped

#### Scenario: Local and account compactor flags do not drift
- **WHEN** the Mimir compactor flags are rendered for the account compactor and for local Mimir
- **THEN** both carry the same retention, deletion-delay, range and cleanup values
