## ADDED Requirements

### Requirement: The compactor keeps merged-away Tempo blocks for an export and polls every minute

Tempo's backend-worker in the account compactor SHALL keep a block it has merged away for 48 hours before removing it (`compacted_block_retention: 48h`), so a tenant export that listed the block still finds it.  Its block list poll SHALL run every minute (`blocklist_poll: 1m`), so the tenant index soon lists the last blocks of a cluster that was just torn down.  Removing a merged-away block after its merged copy exists is compaction, not deletion.

#### Scenario: Merged-away blocks are kept for 48 hours
- **WHEN** the compactor's Tempo configuration is built
- **THEN** `compacted_block_retention` is 48 hours and `blocklist_poll` is 1 minute

#### Scenario: An export finds a block merged during the copy
- **WHEN** a tenant export lists a Tempo block and the compactor merges it away an hour later, before the export copies it
- **THEN** the export still copies the block
