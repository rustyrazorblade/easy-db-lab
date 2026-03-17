## Why

ClickHouse currently supports two storage policies: `local` (local disk only) and `s3_main` (S3 as primary store with a local read cache). Neither supports tiered storage where data starts on fast local NVMe and automatically migrates to S3 once the local disk approaches capacity.

Tiered storage is valuable for time-series and analytics workloads where recent data is queried frequently but historical data still needs to be available at lower cost. The local disk acts as a hot tier for active data; S3 serves as the cold tier for older or overflow data — keeping query performance high while using cheap object storage for long-term retention.

## What Changes

- Add a new `s3_cold` storage policy to `config.xml` with a hot volume (local `default` disk) and a cold volume (existing `s3_cache` disk)
- Add a configurable `move_factor` that controls when ClickHouse moves data from hot to cold (default: `0.2`, meaning data moves when the hot volume reaches 80% full)
- Expose `--s3-cold-move-factor` on `clickhouse init` to let users tune this threshold
- Store `s3ColdMoveFactor` in `ClickHouseConfig` and pass it through to the K8s manifest and config.xml
- Update user documentation to describe the `s3_cold` policy and when to use it

## Capabilities

### New Capabilities

- **`s3_cold` storage policy**: Users can create ClickHouse tables with `storage_policy = 's3_cold'` to automatically tier data from local disk to S3 based on disk utilization.

### Modified Capabilities

- `clickhouse`: Gains a third storage policy option (`s3_cold`) alongside existing `local` and `s3_main` policies.

## Impact

- **`config.xml`**: New `s3_cold` policy with hot/cold volumes and `move_factor` template variable
- **`ClickHouseConfig`**: Add `s3ColdMoveFactor: Double` field (default `0.2`)
- **`Constants.ClickHouse`**: Add `DEFAULT_S3_COLD_MOVE_FACTOR` constant
- **`ClickHouseManifestBuilder`**: Pass `s3ColdMoveFactor` as environment variable or direct config value
- **`ClickHouseInit`**: Add `--s3-cold-move-factor` CLI option
- **Events**: Add `Event.ClickHouse.ConfigSaved` field or update it to include `s3ColdMoveFactor`
- **Documentation**: Update `docs/user-guide/clickhouse.md` to cover the new policy
- **Spec**: Update `openspec/specs/clickhouse/spec.md` with REQ-CH-002 extension
