## 1. Packer Install Script

- [ ] 1.1 Create `packer/cassandra/install/install_jhiccup.sh` — downloads the jHiccup jar (use a pinned version constant like `JHICCUP_VERSION="2.0.10"`) from `https://github.com/giltene/jHiccup/releases/download/` and installs to `/usr/local/jhiccup/jhiccup.jar`. Follow the pattern in `install_pyroscope_agent.sh`: create directory with `sudo mkdir -p`, `wget -q` to `/tmp/`, `sudo mv` to final path, `sudo chmod 644`.

- [ ] 1.2 Add the install script to `packer/cassandra/cassandra.pkr.hcl` — add a new `provisioner "shell"` block after the existing agent installs (after `install_axon.sh`), referencing `install/install_jhiccup.sh`.

## 2. JVM Agent Activation

- [ ] 2.1 Add jHiccup agent activation to `packer/cassandra/cassandra.in.sh` — follow the Pyroscope activation pattern: check `[ -f "$JHICCUP_JAR" ]`, then append to `JVM_OPTS`. Set the output file to `/mnt/db1/cassandra/logs/hiccup.hlog` and use a 1000ms (1 second) reporting interval. jHiccup agent arguments: `-javaagent:/usr/local/jhiccup/jhiccup.jar=d1000,0,${CASSANDRA_LOG_DIR}/hiccup.hlog`. If the jar is not found, emit an `INFO` message to stderr (do not fail).

## 3. OTel Collector Config

- [ ] 3.1 Add a `filelog/jhiccup` receiver to `src/main/resources/.../configuration/otel/otel-collector-config.yaml`. Configure it to include `/mnt/db1/cassandra/logs/hiccup.hlog`, `start_at: end`, `include_file_path: true`. Add operators:
  - A `filter` operator to drop comment lines (matching `^#` or `^"`)
  - A `regex_parser` operator to parse data lines: `^(?P<interval_start>[0-9.]+),(?P<interval_length>[0-9.]+),(?P<hiccup_max_us>[0-9]+),` — extracting `hiccup_max_us` as a string attribute
  - Set `attributes.source: jhiccup`

- [ ] 3.2 Add `filelog/jhiccup` to the `logs/local` pipeline receivers list in the same config file.

## 4. Grafana Dashboard

- [ ] 4.1 Add `JHICCUP` entry to `GrafanaDashboard` enum in `src/main/kotlin/.../configuration/grafana/GrafanaDashboard.kt`. Use `configMapName = "jhiccup-dashboard"`, an appropriate `volumeName`, `mountPath = "/var/lib/grafana/dashboards/jhiccup"`, `jsonFileName = "jhiccup.json"`, `resourcePath` pointing to the dashboard JSON, and `optional = true`.

- [ ] 4.2 Create `src/main/resources/.../configuration/grafana/dashboards/jhiccup.json` — a Grafana dashboard using the VictoriaLogs datasource (victoriametrics-logs-datasource). The dashboard should include:
  - A time series panel showing `hiccup_max_us` over time per node (y-axis in milliseconds: divide by 1000)
  - A `host.name` template variable for node filtering
  - LogsQL query filtering on `source:jhiccup`
  - Title: "JVM Hiccups"

## 5. Testing

- [ ] 5.1 Run `./gradlew testPackerScript -Pscript=cassandra/install/install_jhiccup.sh` to verify the install script works correctly in Docker.

- [ ] 5.2 Add `JHICCUP` to `K8sServiceIntegrationTest.collectAllResources()` — verify the updated OTel DaemonSet applies successfully with the new receiver config.

- [ ] 5.3 Verify that the `GrafanaDashboard.JHICCUP` entry is covered by any existing enum completeness tests.

## 6. Documentation

- [ ] 6.1 Update `docs/reference/observability.md` (or equivalent observability reference doc) to document jHiccup as a new signal: what it measures, where the data appears in Grafana, and the log file location.

- [ ] 6.2 Update `CLAUDE.md` observability section to list jHiccup under "Collectors" running on db nodes.
