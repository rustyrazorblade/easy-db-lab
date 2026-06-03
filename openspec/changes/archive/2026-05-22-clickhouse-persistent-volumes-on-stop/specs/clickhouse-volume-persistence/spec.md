## ADDED Requirements

### Requirement: PVs are created lazily at start time
The system SHALL create ClickHouse PersistentVolumes as the first step of `clickhouse start`, not during `clickhouse install`. The `platform-pvs` step in `config.yaml` MUST appear in the `start` lifecycle and MUST NOT appear in the `install` lifecycle.

#### Scenario: First start creates PVs
- **WHEN** `clickhouse start` is run after a fresh install with no existing PVs
- **THEN** the system creates one PV per db node before deploying the ClickHouseInstallation

#### Scenario: Install does not create PVs
- **WHEN** `clickhouse install` completes
- **THEN** no PVs exist for the clickhouse workload

### Requirement: Start is idempotent with respect to PVs
The `platform-pvs` step in `start` MUST be idempotent: if PVs already exist (from a previous run), the system SHALL clear any stale claimRef UIDs and continue without error, not attempt to recreate the PVs.

#### Scenario: Restart after stop clears stale claimRef UIDs
- **WHEN** `clickhouse stop` is run (deleting the CHI and PVCs) and then `clickhouse start` is run again
- **THEN** the system detects the Released PVs, clears the stale claimRef UID, and the new PVCs bind successfully

#### Scenario: Restart after stop resumes data
- **WHEN** data is written to ClickHouse, then `clickhouse stop` is run, then `clickhouse start` is run again
- **THEN** the data written before the stop is still accessible after the restart

### Requirement: PVs survive stop
The system MUST NOT delete ClickHouse PVs or data during `clickhouse stop`. PVs SHALL persist until `clickhouse uninstall` is executed.

#### Scenario: Stop does not destroy PVs
- **WHEN** `clickhouse stop` is run
- **THEN** the PVs for the clickhouse workload still exist in the cluster

### Requirement: Uninstall cleans up PVs
`clickhouse uninstall` MUST delete all PVs and the on-disk data for the clickhouse workload, even if `clickhouse start` was never run (no PVs to delete is not an error).

#### Scenario: Uninstall after start cleans up PVs
- **WHEN** `clickhouse start` has been run and then `clickhouse uninstall` is run
- **THEN** all clickhouse PVs are deleted and the data directory is removed from cluster nodes

#### Scenario: Uninstall without prior start is a no-op for storage
- **WHEN** `clickhouse install` is run but `clickhouse start` is never run, then `clickhouse uninstall` is run
- **THEN** the uninstall completes successfully with zero PVs deleted
