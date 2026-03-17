## 1. Constants

- [ ] 1.1 Add `DEFAULT_S3_COLD_MOVE_FACTOR: Double = 0.2` to `Constants.ClickHouse`

## 2. ClickHouseConfig

- [ ] 2.1 Add `s3ColdMoveFactor: Double = Constants.ClickHouse.DEFAULT_S3_COLD_MOVE_FACTOR` to `ClickHouseConfig` data class in `ClusterState.kt`

## 3. ClickHouseInit Command

- [ ] 3.1 Add `--s3-cold-move-factor` option (type `Double`, default `Constants.ClickHouse.DEFAULT_S3_COLD_MOVE_FACTOR`) to `ClickHouseInit`
- [ ] 3.2 Pass `s3ColdMoveFactor` into `ClickHouseConfig(...)` constructor call
- [ ] 3.3 Add `s3ColdMoveFactor` to `Event.ClickHouse.ConfigSaved(...)` emit call

## 4. Domain Events

- [ ] 4.1 Add `s3ColdMoveFactor: Double` field to `Event.ClickHouse.ConfigSaved` data class in `Event.kt`
- [ ] 4.2 Update `ConsoleEventListener` display string for `ConfigSaved` to include `s3ColdMoveFactor`

## 5. ClickHouseManifestBuilder

- [ ] 5.1 Add `s3ColdMoveFactor: Double` parameter to `buildAllResources()` (and any intermediate methods that need it)
- [ ] 5.2 In `buildClusterConfigMap()`, add `"s3-cold-move-factor" to s3ColdMoveFactor.toString()`
- [ ] 5.3 In `buildServerContainer()`, add `CLICKHOUSE_S3_COLD_MOVE_FACTOR` env var sourced from the cluster ConfigMap key `s3-cold-move-factor`

## 6. config.xml

- [ ] 6.1 Add `s3_cold` storage policy to `config.xml` with:
  - hot volume using `default` disk
  - cold volume using `s3_cache` disk
  - `<move_factor from_env="CLICKHOUSE_S3_COLD_MOVE_FACTOR"/>`

## 7. ClickHouseStart Command

- [ ] 7.1 Pass `s3ColdMoveFactor` from `ClickHouseConfig` into `ClickHouseManifestBuilder.buildAllResources()`

## 8. Spec Update

- [ ] 8.1 Update `openspec/specs/clickhouse/spec.md` — extend REQ-CH-002 to include the `s3_cold` tiered storage policy requirement

## 9. Documentation

- [ ] 9.1 Update `docs/user-guide/clickhouse.md`:
  - Add `s3_cold` to the storage policy comparison table
  - Document `--s3-cold-move-factor` option on `clickhouse init`
  - Add a table creation example using `storage_policy = 's3_cold'`
  - Clarify difference between `s3_main` (S3 as primary) and `s3_cold` (S3 as overflow)

## Tests

- [ ] `ClickHouseInitTest` — verify `s3ColdMoveFactor` is stored in `ClickHouseConfig` when `--s3-cold-move-factor` is passed; verify default value when omitted
- [ ] `ClickHouseManifestBuilderTest` — verify `s3-cold-move-factor` key appears in cluster ConfigMap; verify `CLICKHOUSE_S3_COLD_MOVE_FACTOR` env var is set in server container
- [ ] `EventSerializationTest` — verify `ConfigSaved` with `s3ColdMoveFactor` serializes/deserializes correctly
- [ ] `K8sServiceIntegrationTest` — verify updated `buildAllResources()` applies cleanly to K3s
