## 1. OTel Collector Config: Add `__NODE_ROLE__` Placeholder

- [ ] 1.1 In `src/main/resources/com/rustyrazorblade/easydblab/configuration/emr/otel-collector-config.yaml`, change the `resource/role` processor's `value` from `spark` to `__NODE_ROLE__`

## 2. Bootstrap Script: Detect Role and Patch Config

- [ ] 2.1 In `bootstrap-otel.sh`, add role detection block after the `set -euo pipefail` header:
  ```bash
  if [ "${IS_MASTER:-false}" = "true" ]; then
    NODE_ROLE="spark-master"
  else
    NODE_ROLE="spark-worker"
  fi
  ```
- [ ] 2.2 After the `COLLECTOR_CONFIG` file is written (after `CONFIGEOF`), add `sed` substitution:
  ```bash
  sed -i "s/__NODE_ROLE__/$NODE_ROLE/g" "$COLLECTOR_CONFIG"
  ```
- [ ] 2.3 After the `otel-collector.service` is started, append role-specific env vars to `/etc/spark/conf/spark-env.sh`:
  ```bash
  echo "export OTEL_SERVICE_NAME=$NODE_ROLE" >> /etc/spark/conf/spark-env.sh
  echo "export PYROSCOPE_APPLICATION_NAME=$NODE_ROLE" >> /etc/spark/conf/spark-env.sh
  ```

## 3. EMRProvisioningService: Remove Static Service Name

- [ ] 3.1 In `buildSparkDefaultsConfiguration()`, remove `"OTEL_SERVICE_NAME" to "spark"` from the `otelEnvVars` map (it is now set per-node by the bootstrap script)
- [ ] 3.2 In `buildSparkDefaultsConfiguration()`, remove `-Dpyroscope.application.name=spark` from the `pyroscopeFlags` list (it is now set via `PYROSCOPE_APPLICATION_NAME` env var)

## 4. EMR Dashboard: Master/Worker Sections and Pyroscope Panels

- [ ] 4.1 Replace the single "Host Metrics" row in `emr.json` with four rows: "Master — OS Stats", "Master — Spark JVM", "Worker — OS Stats", "Worker — Spark JVM"
- [ ] 4.2 Duplicate existing OS stat panels (CPU, memory, disk I/O, network I/O, load average, filesystem) into both "Master — OS Stats" (filter `node_role="spark-master"`) and "Worker — OS Stats" (filter `node_role="spark-worker"`)
- [ ] 4.3 Duplicate existing Spark JVM panels (heap usage, GC duration, thread count, classes loaded) into both "Master — Spark JVM" (filter `node_role="spark-master"`) and "Worker — Spark JVM" (filter `node_role="spark-worker"`)
- [ ] 4.4 Add a "Profiling" row at the bottom of the dashboard
- [ ] 4.5 Add a Pyroscope flamegraph panel for `spark-master` in the Profiling row (datasource: Pyroscope, query: `{app="spark-master"}`)
- [ ] 4.6 Add a Pyroscope flamegraph panel for `spark-worker` in the Profiling row (datasource: Pyroscope, query: `{app="spark-worker"}`)
- [ ] 4.7 Update the `$hostname` template variable queries to remain role-agnostic (query across all `node_role=~"spark-.*"`) or add per-role hostname variables if needed

## 5. Spec Update

- [ ] 5.1 Update `openspec/specs/spark-emr/spec.md` to add a requirement for per-node role labeling:
  - Master nodes tagged `node_role=spark-master`, worker nodes tagged `node_role=spark-worker`
  - Bootstrap script resolves role at runtime using `IS_MASTER`
  - Static `OTEL_SERVICE_NAME=spark` and `-Dpyroscope.application.name=spark` removed from cluster-wide defaults
