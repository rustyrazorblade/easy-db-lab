## MODIFIED Requirements

### Requirement: PVs are created lazily at start time

The system SHALL create ClickHouse PersistentVolumes as the first step of `clickhouse start`, not during `clickhouse install`. The `kit-storage` step in `kit.yaml` MUST appear in the `start` lifecycle and MUST NOT appear in the `install` lifecycle. On a provisioned kit StorageClass the step creates no PV; the cluster provisions the claims' volumes.

#### Scenario: PVs created on start after fresh install

- **WHEN** `clickhouse start` is run after a fresh install with no existing PVs, on local volumes
- **THEN** the system creates one PV of the class `edl-local-clickhouse` per db node before deploying the ClickHouseInstallation.

#### Scenario: No PVs after install

- **WHEN** `clickhouse install` completes
- **THEN** no PVs exist for the clickhouse workload.

### Requirement: Start is idempotent with respect to PVs

The `kit-storage` step in `start` MUST be idempotent: if PVs already exist (from a previous run), the system SHALL clear any stale claimRef UIDs and continue without error, not attempt to recreate the PVs.

#### Scenario: Released PVs rebind on restart

- **WHEN** `clickhouse stop` is run (deleting the CHI and PVCs) and then `clickhouse start` is run again
- **THEN** the system detects the Released PVs, clears the stale claimRef UID, and the new PVCs bind successfully.

#### Scenario: Data survives a stop/start cycle

- **WHEN** data is written to ClickHouse, then `clickhouse stop` is run, then `clickhouse start` is run again
- **THEN** the data written before the stop is still accessible after the restart.

#### Scenario: Released volumes rebind on a provisioned class

- **GIVEN** a recorded kit StorageClass that reclaims with `Retain`
- **WHEN** `clickhouse stop` is run (the operator deletes the claims) and then `clickhouse start`
- **THEN** the `kit-storage` step (`rebind-released: true`) clears the claimRef UID of the class's Released volumes in the kit namespace, and the new claims of the same names bind them.
