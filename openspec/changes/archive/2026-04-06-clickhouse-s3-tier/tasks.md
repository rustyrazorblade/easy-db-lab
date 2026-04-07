# Tasks: ClickHouse S3 Tier Storage Policy

## Implementation

- [x] Add `DEFAULT_S3_TIER_MOVE_FACTOR = 0.2` to `Constants.ClickHouse`
- [x] Add `s3TierMoveFactor: Double` field to `ClickHouseConfig`
- [x] Add `--s3-tier-move-factor` option to `ClickHouseInit`
- [x] Update `Event.ClickHouse.ConfigSaved` to include `s3TierMoveFactor`
- [x] Rename disks in `config.xml`: `default` → `local`, `s3_cache` → `s3`
- [x] Add `s3_tier` policy to `config.xml`
- [x] Update `ClickHouseManifestBuilder.buildClusterConfigMap` to store `s3-tier-move-factor`
- [x] Update `ClickHouseManifestBuilder.buildServerContainer` to inject `CLICKHOUSE_S3_TIER_MOVE_FACTOR` env var
- [x] Update `ClickHouseManifestBuilder.buildAllResources` signature
- [x] Update `buildServerInitDataDirContainer` to create `/mnt/db1/clickhouse/disks/s3`
- [x] Wire `ClickHouseStart` to pass `s3TierMoveFactor` from config to builder

## Tests

- [x] Update `ClickHouseManifestBuilderTest` to pass `s3TierMoveFactor`
- [x] Add assertion for `s3-tier-move-factor` in cluster config ConfigMap test
- [x] Add test asserting `s3_tier` policy appears in `config.xml`
- [x] Add `step_clickhouse_s3_tier_test` to `bin/end-to-end-test`:
  - Creates table with `s3_tier` policy
  - Inserts 10,000 rows
  - Forces move to S3 disk via `ALTER TABLE ... MOVE PARTITION tuple() TO DISK 's3'`
  - Verifies S3 bucket contains objects under `clickhouse/` prefix
