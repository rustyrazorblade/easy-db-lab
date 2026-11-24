# ClickHouse Volume Persistence

## Purpose

PersistentVolumes for ClickHouse are created lazily at `start` time and survive `stop`, so stop/start cycles resume existing data. Only `uninstall` deletes PVs and on-disk data.
## Requirements
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

#### Scenario: Claims stay bound across a restart

- **WHEN** `clickhouse stop` is run (deleting the CHI, whose data claim template declares `reclaimPolicy: Retain`) and then `clickhouse start` is run again
- **THEN** the operator deleted neither the claims nor any table, the existing PVs are left as they are, and the new pods mount the same claims.

#### Scenario: Data survives a stop/start cycle

- **WHEN** data is written to ClickHouse, then `clickhouse stop` is run, then `clickhouse start` is run again
- **THEN** the data written before the stop is still accessible after the restart.

#### Scenario: Claims stay bound on a provisioned class

- **GIVEN** a recorded kit StorageClass that reclaims with `Retain`
- **WHEN** `clickhouse stop` is run and then `clickhouse start`
- **THEN** the claims were never deleted, so their volumes never went `Released`, and the data written before the stop is accessible.

### Requirement: PVs survive stop

The system MUST NOT delete ClickHouse PVs or data during `clickhouse stop`. PVs SHALL persist until `clickhouse uninstall` is executed.

#### Scenario: PVs persist after stop

- **WHEN** `clickhouse stop` is run
- **THEN** the PVs for the clickhouse workload still exist in the cluster.

### Requirement: Uninstall cleans up PVs

`clickhouse uninstall` MUST delete all PVs and the on-disk data for the clickhouse workload, even if `clickhouse start` was never run (no PVs to delete is not an error).

#### Scenario: Uninstall after start removes PVs and data

- **WHEN** `clickhouse start` has been run and then `clickhouse uninstall` is run
- **THEN** the retained data claims (`clickhouse.altinity.com/chi=clickhouse`) are deleted first, then all clickhouse PVs, and the data directory is removed from cluster nodes.

#### Scenario: Uninstall without prior start succeeds

- **WHEN** `clickhouse install` is run but `clickhouse start` is never run, then `clickhouse uninstall` is run
- **THEN** the uninstall completes successfully with zero PVs deleted.

