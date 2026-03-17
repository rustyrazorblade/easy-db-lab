# Tasks: S3 Tier Support for ClickHouse

## Completed

- [x] Add `DEFAULT_S3_TIER_MOVE_FACTOR = 0.2` to `Constants.ClickHouse`
- [x] Add `s3TierMoveFactor: Double` to `ClickHouseConfig`
- [x] Add `--s3-tier-move-factor` option to `ClickHouseInit`
- [x] Add `s3TierMoveFactor` field to `Event.ClickHouse.ConfigSaved`
- [x] Update `ClickHouseManifestBuilder.buildAllResources()` to accept `s3TierMoveFactor`
- [x] Update `ClickHouseManifestBuilder.buildClusterConfigMap()` to store `s3-tier-move-factor`
- [x] Update `ClickHouseManifestBuilder.buildServerContainer()` to inject `CLICKHOUSE_S3_TIER_MOVE_FACTOR` env var
- [x] Rename `default` → `local` and `s3_cache` → `s3` disks in `config.xml`
- [x] Add `s3_tier` storage policy to `config.xml`
- [x] Update init container mkdir path from `s3_cache` to `s3`
- [x] Update `ClickHouseStart` to pass `s3TierMoveFactor`
- [x] Update `ClickHouseManifestBuilderTest` with new parameter and assertions
- [x] Update `K8sServiceIntegrationTest` with new parameter
