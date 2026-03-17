## Context

ClickHouse supports multi-volume tiered storage policies where data automatically moves between volumes based on disk utilization. Currently `config.xml` defines two policies:

- **`local`** — single volume using the `default` local disk
- **`s3_main`** — single volume using the `s3_cache` disk (S3 as primary store with a local read cache)

Neither policy uses local disk as a hot tier with S3 as overflow. A `s3_cold` policy fills this gap: recent or actively-written data stays on fast local NVMe, and as the disk fills up beyond the configured threshold, ClickHouse automatically moves parts to the `s3_cache` disk (backed by S3). Users opt in by specifying `storage_policy = 's3_cold'` at table creation time.

The `s3_disk` and `s3_cache` disks already exist in `config.xml` and are fully configured via environment variables. No new disk definitions are needed — only a new policy entry and the `move_factor` parameter.

## Goals / Non-Goals

**Goals:**
- Add a `s3_cold` storage policy to `config.xml` with a configurable `move_factor`
- Expose `--s3-cold-move-factor` on `clickhouse init` for user control
- Store `s3ColdMoveFactor` in `ClickHouseConfig` and propagate it to the K8s environment
- Update the ClickHouse spec and user documentation

**Non-Goals:**
- Automatic migration of existing tables between storage policies
- Adding TTL-based movement rules (that's a ClickHouse DDL concern, not a cluster config concern)
- Changing how `s3_disk` or `s3_cache` disks are defined

## Decisions

### 1. Policy configuration: `move_factor` via environment variable

The `move_factor` value is a floating-point number between 0.0 and 1.0. It controls the disk utilization threshold at which ClickHouse begins moving parts to the cold tier. A value of `0.2` means: start moving when only 20% of the hot volume's space remains free (i.e., when the disk is 80% full).

`move_factor` will be injected via a new environment variable `CLICKHOUSE_S3_COLD_MOVE_FACTOR`, following the same pattern as `CLICKHOUSE_S3_CACHE_SIZE` and `CLICKHOUSE_S3_CACHE_ON_WRITE`. The `config.xml` template uses `from_env="CLICKHOUSE_S3_COLD_MOVE_FACTOR"` to read it at startup.

**Alternative considered**: Hardcoding `0.2` directly in `config.xml`. Rejected because the right threshold varies with workload — a user with write-heavy workloads may want lower; a user focused on minimizing S3 costs may want higher. Since we're already plumbing cache configuration through env vars, the pattern is established.

### 2. Default value: `0.2`

A `move_factor` of `0.2` (move when the hot volume is 80% full) is a reasonable default. It keeps most recent data local while preventing the disk from filling entirely. This matches common ClickHouse tiered storage examples.

### 3. ClickHouseConfig stores `s3ColdMoveFactor: Double`

The field is added to `ClickHouseConfig` alongside `s3CacheSize`, `s3CacheOnWrite`, and `replicasPerShard`. Default is `Constants.ClickHouse.DEFAULT_S3_COLD_MOVE_FACTOR` = `0.2`.

### 4. ClickHouseManifestBuilder passes it as an environment variable via ConfigMap

The `buildClusterConfigMap()` method already stores `s3-cache-size` and `s3-cache-on-write`. A new entry `s3-cold-move-factor` will be added to the ConfigMap, and `buildServerContainer()` will expose it as `CLICKHOUSE_S3_COLD_MOVE_FACTOR`.

### 5. Event update: add `s3ColdMoveFactor` to `Event.ClickHouse.ConfigSaved`

The existing `ConfigSaved` event captures all init parameters. Adding `s3ColdMoveFactor` keeps the event consistent with the config data class.

## Risks / Trade-offs

- **Existing clusters**: Clusters initialized before this change have no `s3ColdMoveFactor` in their stored state. Since `ClusterState` uses kotlinx.serialization with default values, the field will default to `0.2` on deserialization of old state files. This is safe and expected (fail fast is not required here — old clusters can be restarted without re-running `clickhouse init`).

- **User confusion between `s3_main` and `s3_cold`**: Both policies use S3, but with different topologies. Documentation must clearly differentiate: `s3_main` = S3 is primary (local disk is just a cache), `s3_cold` = local disk is primary (S3 is overflow).
