# Design: S3 Tier Support for ClickHouse

## Disk naming

Disks are renamed for clarity:
- `default` → `local` (explicit `type=local`, path `/mnt/db1/clickhouse/`)
- `s3_cache` → `s3` (cache layer over `s3_disk`, path `/mnt/db1/clickhouse/disks/s3/`)

These names make the storage policies self-documenting: `local` uses `local`, `s3_main` uses `s3`.

## New storage policy: `s3_tier`

```xml
<s3_tier>
    <volumes>
        <hot><disk>local</disk></hot>
        <cold><disk>s3</disk></cold>
    </volumes>
    <move_factor from_env="CLICKHOUSE_S3_TIER_MOVE_FACTOR"/>
</s3_tier>
```

`move_factor` is read from an environment variable so it can be configured per-cluster without rebuilding the config.

## Configuration flow

1. `clickhouse init --s3-tier-move-factor 0.1` → stored in `ClickHouseConfig.s3TierMoveFactor`
2. `clickhouse start` reads `s3TierMoveFactor` and passes to `ClickHouseManifestBuilder.buildAllResources()`
3. `buildClusterConfigMap()` stores value as `s3-tier-move-factor` in the `clickhouse-cluster-config` ConfigMap
4. `buildServerContainer()` injects `CLICKHOUSE_S3_TIER_MOVE_FACTOR` env var from the ConfigMap
5. ClickHouse reads the env var via `from_env` in `config.xml`

## Changed files

- `Constants.kt` — `DEFAULT_S3_TIER_MOVE_FACTOR = 0.2`
- `ClusterState.kt` — `ClickHouseConfig.s3TierMoveFactor: Double`
- `ClickHouseInit.kt` — `--s3-tier-move-factor` option
- `Event.kt` — `ClickHouse.ConfigSaved.s3TierMoveFactor` field
- `ClickHouseManifestBuilder.kt` — ConfigMap entry + env var
- `config.xml` — renamed disks + `s3_tier` policy
