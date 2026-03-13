## Why

The OTel Collector config on EMR nodes hardcodes `node_role: spark` for all nodes, making it impossible to distinguish master from worker metrics in Grafana. The Pyroscope application name is similarly set to `spark` cluster-wide. The EMR dashboard has no master/worker sections and no Pyroscope panels, so there is no way to isolate master vs worker OS stats or profiling data.

## What Changes

- Update `otel-collector-config.yaml` to use a `__NODE_ROLE__` placeholder instead of the hardcoded `spark` value
- Update `bootstrap-otel.sh` to detect the EMR `IS_MASTER` environment variable at runtime, resolve `__NODE_ROLE__` to `spark-master` or `spark-worker` via `sed`, and append role-specific `OTEL_SERVICE_NAME` and `PYROSCOPE_APPLICATION_NAME` to `/etc/spark/conf/spark-env.sh`
- Remove the static `OTEL_SERVICE_NAME=spark` and `-Dpyroscope.application.name=spark` entries from the cluster-wide `spark-defaults` EMR classification in `EMRProvisioningService`, since these are now set per-node at bootstrap time
- Restructure the EMR Grafana dashboard into four sections: **Master — OS Stats**, **Master — Spark JVM**, **Worker — OS Stats**, **Worker — Spark JVM**, each filtered by `node_role="spark-master"` or `node_role="spark-worker"` respectively
- Add a **Profiling** section to the dashboard with two Pyroscope panels: one for `spark-master` and one for `spark-worker`

## Capabilities

### Modified Capabilities

- `emr-otel-bootstrap`: Bootstrap script sets `node_role` and Pyroscope/OTel service name per node based on `IS_MASTER`; static cluster-wide service name removed from `spark-defaults`
- `observability`: EMR dashboard gains master/worker sections with OS stats, Spark JVM metrics, and Pyroscope profiling panels per role

## Impact

- **`bootstrap-otel.sh`**: Adds `IS_MASTER` detection block; patches `__NODE_ROLE__` in collector config via `sed`; appends `OTEL_SERVICE_NAME` and `PYROSCOPE_APPLICATION_NAME` to spark-env.sh
- **`otel-collector-config.yaml`**: `node_role` value changes from `spark` to `__NODE_ROLE__` placeholder
- **`EMRProvisioningService`**: `buildSparkDefaultsConfiguration` removes `OTEL_SERVICE_NAME` from env vars and removes `-Dpyroscope.application.name` from JVM flags
- **`emr.json`**: Dashboard rebuilt with master/worker row sections and Pyroscope profiling panels
