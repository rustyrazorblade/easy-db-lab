## Context

EMR bootstrap actions run on all nodes (master and core/task). The `bootstrap-otel.sh` script already installs the OTel Collector, OTel Java agent, and Pyroscope Java agent on every node. However:
- The OTel Collector config hardcodes `node_role: spark` for all nodes via the `resource/role` processor
- The Pyroscope application name and `OTEL_SERVICE_NAME` are set statically to `spark` in the `spark-defaults` EMR classification
- EMR sets the `IS_MASTER` environment variable to `"true"` on the master node at bootstrap execution time

The control node IP is already embedded in the OTel collector config at S3 upload time via `TemplateService`. The `__NODE_ROLE__` approach extends this pattern: the placeholder is left unresolved at upload time and is resolved per-node by the bootstrap script using `sed`.

## Goals / Non-Goals

**Goals:**
- `node_role` set to `spark-master` on the master node, `spark-worker` on all core/task nodes
- Pyroscope application name (`PYROSCOPE_APPLICATION_NAME`) set per node based on role
- `OTEL_SERVICE_NAME` set per node based on role
- EMR dashboard split into master and worker sections with OS stats and JVM metrics
- Pyroscope profiling panels in dashboard (one for master, one for workers)

**Non-Goals:**
- Changing the per-job `OTEL_SERVICE_NAME=spark-{jobName}` override in `EMRSparkService` (this is a separate concern and remains unchanged)
- Task node support beyond `spark-worker` (task nodes behave the same as core nodes for observability purposes)
- Changing the Pyroscope profiling labels beyond application name (hostname labels remain as-is)

## Decisions

### D1: Runtime `sed` substitution in bootstrap script for `__NODE_ROLE__`

The OTel collector config is embedded inline into `bootstrap-otel.sh` at S3 upload time by `TemplateService`. The `__NODE_ROLE__` placeholder is not in the `substitute()` call's map, so `TemplateService` leaves it unchanged in the embedded config text.

At bootstrap execution time, the script detects `IS_MASTER` and runs:
```bash
if [ "${IS_MASTER:-false}" = "true" ]; then
  NODE_ROLE="spark-master"
else
  NODE_ROLE="spark-worker"
fi
sed -i "s/__NODE_ROLE__/$NODE_ROLE/g" "$COLLECTOR_CONFIG"
```

**Alternative considered:** Two separate config files (one for master, one for worker) uploaded separately to S3 and selected by the bootstrap script. Rejected — adds complexity, two S3 objects to upload, and logic to select. The `sed` approach reuses existing template substitution infrastructure cleanly.

**Alternative considered:** EMR instance group bootstrap actions (separate bootstrap per instance group). Rejected — AWS EMR bootstrap actions don't support per-instance-group targeting directly. All bootstrap actions run on all nodes.

### D2: Append role env vars to `/etc/spark/conf/spark-env.sh`

The `spark-env.sh` file is sourced by Spark/YARN before launching JVM processes. The bootstrap script appends role-specific values after determining `NODE_ROLE`:
```bash
echo "export OTEL_SERVICE_NAME=$NODE_ROLE" >> /etc/spark/conf/spark-env.sh
echo "export PYROSCOPE_APPLICATION_NAME=$NODE_ROLE" >> /etc/spark/conf/spark-env.sh
```

The static `OTEL_SERVICE_NAME=spark` is removed from `EMRProvisioningService.buildSparkDefaultsConfiguration()` so the per-node env var takes precedence without conflict.

Note: The per-job override `OTEL_SERVICE_NAME=spark-{jobName}` set by `EMRSparkService` at step submission time still overrides the per-node value for JVM telemetry. The per-node value in spark-env.sh acts as the default when no job is running.

**Alternative considered:** Using EMR instance fleet launch specifications or custom AMI with pre-baked scripts. Rejected — out of scope; bootstrap action is the established pattern.

### D3: Remove `-Dpyroscope.application.name=spark` from `extraJavaOptions`

Currently `EMRProvisioningService.buildSparkDefaultsConfiguration` sets `-Dpyroscope.application.name=spark` as a JVM system property. JVM system properties (`-D`) take precedence over env vars for the Pyroscope agent. Removing the `-D` flag allows `PYROSCOPE_APPLICATION_NAME` from spark-env.sh to take effect.

The `-javaagent:/opt/pyroscope/pyroscope.jar` flag and other Pyroscope system properties (`-Dpyroscope.server.address`, `-Dpyroscope.format`, `-Dpyroscope.profiler.*`) remain unchanged.

### D4: Dashboard structure — four metric sections plus profiling

Dashboard layout:
1. **Row: Master — OS Stats** — CPU, memory, disk I/O, network I/O (filtered by `node_role="spark-master"`)
2. **Row: Master — Spark JVM** — Heap usage, GC duration/count, thread count, classes loaded (filtered by `node_role="spark-master"`)
3. **Row: Worker — OS Stats** — Same panels as master OS stats (filtered by `node_role="spark-worker"`)
4. **Row: Worker — Spark JVM** — Same panels as master JVM stats (filtered by `node_role="spark-worker"`)
5. **Row: Profiling** — Pyroscope flamegraph panel for `spark-master`, Pyroscope flamegraph panel for `spark-worker`

The existing `$hostname` template variable is retained for drilling into individual nodes. Panels that currently use `node_role="spark"` are duplicated and re-filtered per role.

For Pyroscope panels: Grafana's native Pyroscope datasource (`grafana-pyroscope-datasource`) is used. The query selects `{app="spark-master"}` or `{app="spark-worker"}`. The Pyroscope datasource UID references the existing datasource variable pattern.

**Alternative considered:** Adding a `$node_role` template variable to avoid duplicating panels. Rejected — having separate rows per role makes the comparison clearer and allows both to be visible simultaneously.

## Risks / Trade-offs

- **`spark-env.sh` append ordering** — The bootstrap script appends to `spark-env.sh` after EMR has written its classifications. If EMR overwrites `spark-env.sh` after the bootstrap script runs, the appended values would be lost. → Mitigation: EMR bootstrap actions run after EMR classifications are applied. Append is safe.

- **IS_MASTER env var availability** — `IS_MASTER` is an EMR-specific env var set in the bootstrap environment. If it is ever unset (e.g., task nodes), we default to `spark-worker`. → Mitigation: The `${IS_MASTER:-false}` default handles missing values gracefully.

- **Pyroscope datasource in dashboard** — The Pyroscope datasource must be provisioned in Grafana for profiling panels to render. If Pyroscope is not available on a cluster, panels show a datasource error. → Mitigation: Pyroscope is already part of the standard observability stack; this is an existing assumption.
