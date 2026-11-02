# Metrics Stream

`easy-db-lab metrics stream` publishes chosen cluster metrics to a Redis pub/sub channel every 5 seconds, for a live display such as a stream overlay.  It reads the values from the cluster's Mimir, with the same queries as the Grafana dashboards.

## Starting the Stream

Set `EASY_DB_LAB_REDIS_URL`, then run the command in the cluster workspace:

```bash
export EASY_DB_LAB_REDIS_URL=redis://localhost:6379/easydblab-events
easy-db-lab metrics stream
```

To choose the metrics, give a comma-separated list:

```bash
easy-db-lab metrics stream --metrics node.cpu,cassandra.read_p99
```

`easy-db-lab metrics list` prints every accepted name, one per line.  A name that is not in the list, or an empty entry such as the one in `node.cpu,,x`, stops the command before the first cycle; the message names each bad entry and its position.  A repeated name is used once.

The URL has the form `redis://host:port/channel`.  When it has no channel, the stream uses `easydblab-events`.

## Metrics

| Name | Default | Entity | Unit |
|------|---------|--------|------|
| `node.cpu` | yes | host | percent |
| `node.memory` | no | host | bytes used |
| `node.disk_read` | yes | host | bytes per second |
| `node.disk_write` | yes | host | bytes per second |
| `node.filesystem_used` | yes | host | percent |
| `node.network_receive` | yes | host | bytes per second |
| `node.network_transmit` | yes | host | bytes per second |
| `cassandra.read_p99` | yes | `cluster.min`, `cluster.max` | milliseconds |
| `cassandra.write_p99` | yes | `cluster.min`, `cluster.max` | milliseconds |
| `cassandra.read_ops` | yes | `cluster` | requests per second |
| `cassandra.write_ops` | yes | `cluster` | requests per second |
| `cassandra.compaction_pending` | yes | `cluster` | tasks |
| `cassandra.compaction_completed` | no | `cluster` | tasks per second |
| `cassandra.compaction_bytes_written` | no | `cluster` | bytes per second |

Without `--metrics`, the stream publishes the 11 default names.

Notes on the values:

- The `node.*` metrics publish one sample per host that reports, with the host's alias as the entity, for example `db0`, `app0` or `control0`.  EMR hosts use their EC2 host name.
- `node.disk_read` and `node.disk_write` sum the host's whole disks; partitions and loop devices are left out.
- `node.network_receive` and `node.network_transmit` sum the `ens*` and `eth*` interfaces; `lo` and the Cilium interfaces are left out.
- `node.filesystem_used` covers only the data mount, `/mnt/db1`.  A host without it publishes no sample.
- Rates use a 1-minute window.
- `cassandra.compaction_bytes_written` counts the bytes that compaction reads (its input bytes), because that is what Cassandra exports.

### Latency: minimum and maximum

Each Cassandra node computes its own p99, and per-node percentiles cannot be combined into a cluster p99.  So each cycle, `cassandra.read_p99` and `cassandra.write_p99` each publish two samples:

- `cluster.min`: the lowest node value above 0.
- `cluster.max`: the highest node value.

A node that reports 0 served no requests of that type, so `cluster.min` leaves it out.  When every node reports 0, no `cluster.min` sample is published, and `cluster.max` is 0.

### Gauge age

The gauges (`cassandra.read_p99`, `cassandra.write_p99` and `cassandra.compaction_pending`) use only samples younger than 30 seconds.  When Cassandra stops, they stop publishing within 30 seconds.  For up to 30 seconds after `cassandra use` switches builds, the series of both builds can count.

When Cassandra is not running, the `cassandra.*` names publish nothing and report no failure.  When it starts, they publish from the next cycle; the stream does not need a restart.

## Samples on the Channel

Each value is one event envelope on the channel:

```json
{
  "event": {
    "type": "Metrics.Sample",
    "name": "node.cpu",
    "entity": "db0",
    "value": 34.2
  },
  "timestamp": "2026-11-01T14:22:05.123Z",
  "commandName": "stream"
}
```

A query that returns no value, NaN or Inf publishes nothing for that host or the cluster in that cycle; it never publishes 0 in its place.

Samples go to Redis only.  The terminal shows one line per cycle with the number of samples published and the number of metrics that failed:

```
metrics stream: published 18 samples, 0 metrics failed
```

## Failures

A query fails when Mimir does not answer, answers with a status other than 200, or answers 200 with a body status other than `success`.  The other metrics are still published in that cycle, and the failed one is queried again in the next cycle.

When a metric starts failing, the stream emits one `Metrics.MetricFailing` event that names the metric and the reason.  While it keeps failing, nothing more is emitted for it.  When it next succeeds, the stream emits one `Metrics.MetricRecovered` event.  Both events reach the terminal and the Redis channel.

When the cluster is torn down while the stream runs, each metric fails once with the reason that the cluster is not running, and the stream keeps running until you stop it.

## Redis

- At start, the command needs `EASY_DB_LAB_REDIS_URL`.  When Redis does not answer, the tool exits with status 1 before the first cycle, and the message names `EASY_DB_LAB_REDIS_URL` and the URL.
- The stream pings Redis at the start of every cycle, so a lost Redis is found within one cycle even when nothing is published.
- When a ping or a publish fails, the stream drops the rest of that cycle's samples and tries to reconnect: 3 attempts, 1 second and then 2 seconds apart.  Samples that could not be published are not queued or replayed.
- When a reconnect succeeds, the stream goes on from the next cycle.  When all 3 attempts fail, it emits `Metrics.RedisLost` and exits non-zero with a message that names the Redis URL.

## Tunnel and Tailscale

Before the first cycle, the stream starts or reuses the SOCKS tunnel, as every command that needs the proxy does.  After that, it never starts, stops or replaces a tunnel.  Each cycle reads the recorded tunnel from the workspace and queries Mimir through it.

- When no live tunnel is recorded, each metric fails once with a reason that says to run `easy-db-lab socks start`.
- When another command records a new tunnel on another port, the stream uses it from the next cycle.
- When Tailscale is active for the cluster, the stream queries Mimir directly.

## Stopping

The first Ctrl-C or SIGTERM stops the stream cleanly, also in the middle of a cycle.  It publishes nothing more and exits with status 0.  A second Ctrl-C or SIGTERM while the stream is stopping exits at once with status 130 or 143.

| Situation | Exit status |
|-----------|-------------|
| First Ctrl-C or SIGTERM | 0 |
| Second Ctrl-C or SIGTERM while stopping | 130 or 143 |
| Bad names, `EASY_DB_LAB_REDIS_URL` not set, no running cluster, or Redis lost after 3 attempts | 1 |
| Redis does not answer at start | 1 |
| Cluster telemetry redirected to an external stack | non-zero |
