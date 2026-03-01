# Spark Observability Debugging

Diagnostic commands for troubleshooting Spark observability on EMR nodes. These require SSH access to the EMR master node (`ssh hadoop@<master-public-dns>`).

## OTel Collector

```bash
# Check collector is running
sudo systemctl status otel-collector

# View collector config (verify control node IP)
cat /opt/otel/config.yaml

# Test connectivity to control node collector
curl -s -o /dev/null -w '%{http_code}' http://<control-ip>:4318
```

## Spark Configuration

```bash
# Verify -javaagent flags and OTel env vars are present
cat /etc/spark/conf/spark-defaults.conf

# Verify agent JARs exist
ls -la /opt/otel/opentelemetry-javaagent.jar
ls -la /opt/pyroscope/pyroscope.jar
```

## Runtime Verification (while a job is running)

```bash
# Confirm agents are attached to Spark JVMs
ps aux | grep javaagent
```

## Pyroscope API (from any node that can reach control0)

```bash
# List all label names
curl \
  -H "Content-Type: application/json" \
  -d '{
      "end": '$(date +%s)000',
      "start": '$(expr $(date +%s) - 3600)000'
    }' \
  http://localhost:4040/querier.v1.QuerierService/LabelNames

# List values for a specific label
curl \
  -H "Content-Type: application/json" \
  -d '{
      "end": '$(date +%s)000',
      "name": "hostname",
      "start": '$(expr $(date +%s) - 3600)000'
    }' \
  http://localhost:4040/querier.v1.QuerierService/LabelValues

# Diff two profiles (compare workloads)
# POST to /querier.v1.QuerierService/Diff with left/right profile selectors
# See: left.labelSelector, right.labelSelector, profileTypeID, start/end
```

## Grafana Explore Queries

```promql
# All metrics from Spark nodes
{node_role="spark"}

# JVM metrics only
{node_role="spark", __name__=~"jvm_.*"}

# List distinct JVM metric names
group({node_role="spark", __name__=~"jvm_.*"}) by (__name__)

# Filesystem usage (raw)
system_filesystem_usage_bytes{state="used", node_role="spark", mountpoint="/"}
```

## JFR Format Reference

The Java Flight Recorder format is used by JVM-based profilers and supported by the Pyroscope Java integration.

When JFR format is used, query parameters behave differently:

- `format` should be set to `jfr`
- `name` contains the prefix of the application name. Since a single request may contain multiple profile types, the final application name is created by concatenating this prefix and the profile type. For example, if you send cpu profiling data and set name to `my-app{}`, it will appear in Pyroscope as `my-app.cpu{}`
- `units` is ignored — actual units depend on the profile types in the data
- `aggregationType` is ignored — actual aggregation type depends on the profile types in the data

### Supported JFR Profile Types

- `cpu` — samples from runnable threads only
- `itimer` — similar to cpu profiling
- `wall` — samples from any thread regardless of state
- `alloc_in_new_tlab_objects` — number of new TLAB objects created
- `alloc_in_new_tlab_bytes` — size in bytes of new TLAB objects created
- `alloc_outside_tlab_objects` — number of new allocated objects outside any TLAB
- `alloc_outside_tlab_bytes` — size in bytes of new allocated objects outside any TLAB

### JFR with Dynamic Labels

To ingest JFR data with dynamic labels:

1. Use `multipart/form-data` Content-Type
2. Send JFR data in a form file field called `jfr`
3. Send `LabelsSnapshot` protobuf message in a form file field called `labels`

```protobuf
message Context {
    // string_id -> string_id
    map<int64, int64> labels = 1;
}
message LabelsSnapshot {
    // context_id -> Context
    map<int64, Context> contexts = 1;
    // string_id -> string
    map<int64, string> strings = 2;
}
```

Where `context_id` is a parameter set in async-profiler.

### Ingestion Examples

Simple profile upload:

```bash
printf "foo;bar 100\n foo;baz 200" | curl \
  -X POST \
  --data-binary @- \
  'http://localhost:4040/ingest?name=curl-test-app&from=1615709120&until=1615709130'
```

JFR profile with labels:

```bash
curl -X POST \
  -F jfr=@profile.jfr \
  -F labels=@labels.pb \
  "http://localhost:4040/ingest?name=curl-test-app&units=samples&aggregationType=sum&sampleRate=100&from=1655834200&until=1655834210&spyName=javaspy&format=jfr"
```

### Future: Ad-hoc Profiling with async-profiler

async-profiler can capture JFR profiles on demand and upload them to Pyroscope with labels. This enables targeted profiling of specific Spark jobs or Cassandra operations to inspect exactly what is happening at the JVM level.

## Common Issues

- **No JVM metrics**: Check `ps aux | grep javaagent` — if `-javaagent` flags are missing, `spark.driver.extraJavaOptions` may be overridden at job submission time (replaces spark-defaults.conf entirely).
- **Collector retry errors at startup**: Normal if the control node collector isn't ready yet. Should stabilize within a minute.
- **Spark profiles missing hostname label**: `PYROSCOPE_LABELS` env var must be set via `spark-env` classification with `hostname=$(hostname -s)`.
