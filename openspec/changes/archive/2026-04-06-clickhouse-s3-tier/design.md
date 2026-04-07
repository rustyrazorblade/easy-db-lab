# Design: ClickHouse S3 Tier Storage Policy

## Data Flow

```
clickhouse init --s3-tier-move-factor 0.1
  → ClickHouseConfig.s3TierMoveFactor = 0.1
  → saved to state.json

clickhouse start
  → ClickHouseManifestBuilder.buildClusterConfigMap(s3TierMoveFactor = 0.1)
      → ConfigMap key: "s3-tier-move-factor" = "0.1"
  → ClickHouseManifestBuilder.buildServerContainer()
      → env var: CLICKHOUSE_S3_TIER_MOVE_FACTOR from ConfigMap
  → Pod reads env var → config.xml reads <move_factor from_env="CLICKHOUSE_S3_TIER_MOVE_FACTOR"/>
```

## config.xml Changes

### Disk renames
- `default` → `local` (explicit `type=local`, path `/mnt/db1/clickhouse/`)
- `s3_cache` → `s3` (cache layer over `s3_disk`, path `/mnt/db1/clickhouse/disks/s3/`)

### New policy
```xml
<s3_tier>
    <volumes>
        <hot><disk>local</disk></hot>
        <cold><disk>s3</disk></cold>
    </volumes>
    <move_factor from_env="CLICKHOUSE_S3_TIER_MOVE_FACTOR"/>
</s3_tier>
```

## Kotlin Changes

### Constants.ClickHouse
```kotlin
const val DEFAULT_S3_TIER_MOVE_FACTOR = 0.2
```

### ClickHouseConfig (ClusterState.kt)
```kotlin
data class ClickHouseConfig(
    ...
    val s3TierMoveFactor: Double = Constants.ClickHouse.DEFAULT_S3_TIER_MOVE_FACTOR,
)
```

### ClickHouseInit
New option: `--s3-tier-move-factor`

### ClickHouseManifestBuilder
- `buildClusterConfigMap`: adds `"s3-tier-move-factor"` key
- `buildServerContainer`: adds `CLICKHOUSE_S3_TIER_MOVE_FACTOR` env var from ConfigMap
- `buildAllResources` + `buildClusterConfigMap`: accept `s3TierMoveFactor` param
- `buildServerInitDataDirContainer`: creates `/mnt/db1/clickhouse/disks/s3` (renamed from `s3_cache`)

### ClickHouseStart
Passes `clickHouseConfig.s3TierMoveFactor` to `buildAllResources`.

### Event.ClickHouse.ConfigSaved
New field: `s3TierMoveFactor: Double`
