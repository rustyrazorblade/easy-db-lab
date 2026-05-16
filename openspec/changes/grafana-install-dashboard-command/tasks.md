## 1. Grafana Persistent Volume

- [x] 1.1 In `GrafanaManifestBuilder.buildVolumes()`, change `DATA_VOLUME` from `withNewEmptyDir()` to `withHostPath()` at `/mnt/db1/grafana`
- [x] 1.2 In `GrafanaUpdateConfig.execute()`, create `/mnt/db1/grafana` on the control node via SSH before applying Grafana resources (same pattern as `applyPyroscopeResources`)

## 2. grafana install Command

- [x] 2.1 Add `Event.Grafana.DashboardInstalled(title: String)` to `events/Event.kt`
- [x] 2.2 Create `GrafanaInstall` command: positional arg for dashboard JSON path, reads cluster state to get control host, POSTs to `http://<controlHost>:3000/api/dashboards/db` using OkHttp with `{"dashboard": {...}, "overwrite": true, "folderId": 0}`
- [x] 2.3 Register `GrafanaInstall` as a subcommand in `Grafana.kt`
- [x] 2.4 Write unit test for `GrafanaInstall` verifying the HTTP payload structure and error handling

## 3. ClickHouse Install Template Dashboards

- [x] 3.1 Create `src/main/resources/com/rustyrazorblade/easydblab/install/clickhouse/dashboards/` and copy `dashboards/clickhouse.json` and `dashboards/clickhouse-logs.json` into it
- [x] 3.2 Update `install/clickhouse/start.sh.template` to add a loop that calls `easy-db-lab grafana install` for each `*.json` in `${SCRIPT_DIR}/dashboards/`

## 4. Quality

- [x] 4.1 Run `./gradlew ktlintFormat && ./gradlew test && ./gradlew detekt` and fix any failures
