# Live Stream Metrics

## Purpose

Real-time cluster metrics streaming via Redis pub/sub, queried from VictoriaMetrics using dashboard-consistent PromQL expressions.
## Requirements
### Requirement: Mimir PromQL query capability

The system SHALL support querying Mimir via its Prometheus HTTP API (`/prometheus/api/v1/query`), sending the cluster's tenant in the `X-Scope-OrgID` header.  The metrics stream SHALL reach Mimir through the recorded SOCKS tunnel without starting or replacing one, or directly when Tailscale is active.

#### Scenario: Instant query returns metric values

- **WHEN** a PromQL instant query is executed against Mimir
- **THEN** the system returns parsed metric results with label sets and numeric values

#### Scenario: Query fails gracefully

- **WHEN** a PromQL query fails (network error, invalid query, Mimir unreachable)
- **THEN** the system returns a failure result without throwing an exception
- **AND** the caller can handle the failure independently of other queries

#### Scenario: Stream query uses the recorded tunnel

- **WHEN** the metrics stream runs a PromQL query and Tailscale is not active
- **THEN** the HTTP request goes through the recorded SOCKS tunnel's port to the cluster-internal Mimir
- **AND** no tunnel is started or replaced for it

#### Scenario: Stream query goes direct over Tailscale

- **WHEN** the metrics stream runs a PromQL query and Tailscale is active
- **THEN** the HTTP request goes directly to the control node's private address

#### Scenario: Query carries the tenant

- **WHEN** a PromQL query is executed for a cluster in tenant `acme`
- **THEN** the request carries `X-Scope-OrgID: acme`

### Requirement: Metrics stream command

`easy-db-lab metrics stream [--metrics <name>,<name>,...]` SHALL query each selected metric from Mimir every 5 seconds.  It SHALL publish each value as one `Metrics.Sample` event on the Redis channel that `EASY_DB_LAB_REDIS_URL` names.  It SHALL run until it is stopped.

A `Metrics.Sample` SHALL carry three fields:
- `name`: the metric name, for example `node.cpu`.
- `entity`: a host alias (for example `db0`), `cluster` for a cluster-wide value, or `cluster.min` / `cluster.max` for the p99 metrics.
- `value`: a number, in the Grafana dashboards' unit for that metric.

Each sample SHALL be published as a normal event envelope, with its timestamp and the command name.  The stream SHALL refuse a cluster whose telemetry is redirected to an external stack, as the other local telemetry readers do.

#### Scenario: Chosen metrics are published every cycle
- **WHEN** `metrics stream --metrics node.cpu,cassandra.read_p99` runs against a running cluster
- **THEN** each cycle publishes one `Metrics.Sample` with `name=node.cpu` for each reporting host, with `entity` set to that host's alias, for example `db0`
- **AND** each cycle publishes two samples with `name=cassandra.read_p99`: one with `entity=cluster.min` and one with `entity=cluster.max`

#### Scenario: A new cycle starts every 5 seconds
- **WHEN** the stream is running
- **THEN** a new cycle starts every 5 seconds

#### Scenario: Sample envelope on the wire
- **WHEN** a Redis subscriber receives a sample
- **THEN** the message is an event envelope with a `timestamp`, `commandName` set to the stream command, and an `event` whose `type` is `Metrics.Sample` and whose fields are `name`, `entity` and `value`

#### Scenario: Redirected cluster is refused
- **WHEN** `metrics stream` runs in a workspace whose telemetry is redirected to an external stack
- **THEN** the command exits non-zero before the first cycle

### Requirement: Metric catalogue and defaults

The stream SHALL accept exactly these 14 names:
- `node.cpu`, `node.memory`, `node.disk_read`, `node.disk_write`, `node.filesystem_used`, `node.network_receive`, `node.network_transmit`.
- `cassandra.read_p99`, `cassandra.write_p99`, `cassandra.read_ops`, `cassandra.write_ops`, `cassandra.compaction_pending`, `cassandra.compaction_completed`, `cassandra.compaction_bytes_written`.

Each name SHALL use the query of the matching Grafana dashboard panel and the metric names that the OTel Java agent's JMX rules export.  Each query SHALL be scoped to the current cluster.  Rates SHALL use a 1-minute window.  The units SHALL be:
- `node.cpu` and `node.filesystem_used`: percent.
- `node.memory`: bytes used.
- `node.disk_read`, `node.disk_write`, `node.network_receive`, `node.network_transmit`: bytes per second.
- `cassandra.read_p99`, `cassandra.write_p99`: milliseconds.
- `cassandra.read_ops`, `cassandra.write_ops`: requests per second.
- `cassandra.compaction_pending`: tasks.
- `cassandra.compaction_completed`: tasks per second.
- `cassandra.compaction_bytes_written`: bytes per second.

When `--metrics` is not given, the stream SHALL use these 11 default names: `node.cpu`, `node.disk_read`, `node.disk_write`, `node.network_receive`, `node.network_transmit`, `node.filesystem_used`, `cassandra.read_p99`, `cassandra.write_p99`, `cassandra.read_ops`, `cassandra.write_ops`, `cassandra.compaction_pending`.

`cassandra.compaction_bytes_written` SHALL count the bytes that compaction reads (its input bytes).  The user docs SHALL say so.

#### Scenario: Default names are streamed
- **WHEN** `metrics stream` runs without `--metrics`
- **THEN** the 11 default names are streamed
- **AND** `node.memory`, `cassandra.compaction_completed` and `cassandra.compaction_bytes_written` are not streamed

#### Scenario: A non-default name can be chosen
- **WHEN** `--metrics` names `node.memory`
- **THEN** `node.memory` is streamed

#### Scenario: CPU does not depend on the per-core label
- **WHEN** a host has several cores
- **THEN** `node.cpu` for that host is a percentage between 0 and 100

#### Scenario: Latency is in milliseconds
- **WHEN** a node reports a p99 read latency of 2,500 microseconds
- **THEN** the value used for `cassandra.read_p99` from that node is 2.5

#### Scenario: Compaction bytes count input bytes
- **WHEN** a user reads the metrics stream docs for `cassandra.compaction_bytes_written`
- **THEN** the docs say it counts the bytes compaction reads

#### Scenario: Every catalogue query parses on the pinned Mimir
- **WHEN** each of the 14 queries is sent to the pinned Mimir version
- **THEN** Mimir answers each one with HTTP 200

### Requirement: Metric name validation

The stream SHALL check `--metrics` before the first cycle.  It SHALL name every bad entry, with its position, and exit non-zero.  A bad entry is a name that is not in the catalogue or an empty name.  A repeated name SHALL be used once.

#### Scenario: Unknown and empty names are refused
- **WHEN** `--metrics` is `node.cpu,,x`
- **THEN** the command names the empty entry and the unknown entry `x`
- **AND** it exits non-zero before the first cycle

#### Scenario: A repeated name is used once
- **WHEN** `--metrics` is `node.cpu,node.cpu`
- **THEN** `node.cpu` is queried once per cycle
- **AND** its samples are published once per cycle

### Requirement: Per-host system metrics

Each `node.*` name SHALL publish one sample per reporting host, for every host that reports: db, app, control and EMR hosts.  The `entity` SHALL be the host's alias, its OS hostname.  A series with no host name SHALL be skipped.

`node.disk_read` and `node.disk_write` SHALL sum the host's whole disks.  Loop devices and partitions SHALL be left out (the dashboard filter `device!~"loop.*|.*p[0-9]+"`).

`node.network_receive` and `node.network_transmit` SHALL sum the host's `ens*` and `eth*` interfaces.  `lo` and the Cilium interfaces SHALL be left out (the dashboard filter `device=~"ens.*|eth.*"`).

`node.filesystem_used` SHALL cover only the data mount, `/mnt/db1`.

#### Scenario: Several disks give one sample
- **WHEN** a host has several whole disks, some partitions and a loop device
- **THEN** `node.disk_read` and `node.disk_write` each publish one sample for that host
- **AND** each value is the sum across the whole disks only

#### Scenario: Several interfaces give one sample
- **WHEN** a host has `ens5`, `ens6`, `lo` and Cilium interfaces
- **THEN** `node.network_receive` and `node.network_transmit` each publish one sample for that host
- **AND** each value is the sum across `ens5` and `ens6` only

#### Scenario: Filesystem use covers only the data mount
- **WHEN** a host has `/mnt/db1` and other mounts
- **THEN** `node.filesystem_used` publishes one sample for that host, for `/mnt/db1` only

#### Scenario: Host without the data mount
- **WHEN** a host has no `/mnt/db1`
- **THEN** no `node.filesystem_used` sample is published for that host

### Requirement: Cassandra cluster metrics

Each node computes its own p99, and the stream SHALL NOT combine them into a cluster p99.  Each cycle, `cassandra.read_p99` and `cassandra.write_p99` SHALL each publish two samples:
- `entity=cluster.min`: the lowest node value above 0.
- `entity=cluster.max`: the highest node value.

There SHALL be no way to request only one of them.  A node that reports 0 served no requests of that type, so `cluster.min` SHALL leave it out.  Each p99 name SHALL be one query, so it fails and recovers as one unit.

`cassandra.read_ops`, `cassandra.write_ops`, `cassandra.compaction_pending`, `cassandra.compaction_completed` and `cassandra.compaction_bytes_written` SHALL each publish one sample with `entity=cluster`, summed across the nodes.

#### Scenario: Min and max across nodes
- **WHEN** three nodes report a read p99 of 2 ms, 5 ms and 9 ms
- **THEN** the cycle publishes `cassandra.read_p99` with `entity=cluster.min` and value 2
- **AND** it publishes `cassandra.read_p99` with `entity=cluster.max` and value 9

#### Scenario: Idle nodes are left out of the minimum
- **WHEN** some nodes report a write p99 of 0 and the others report values above 0
- **THEN** `cluster.min` for `cassandra.write_p99` is the lowest value above 0

#### Scenario: Every node reports 0
- **WHEN** every node reports a read p99 of 0
- **THEN** no `cluster.min` sample is published for `cassandra.read_p99`
- **AND** the `cluster.max` sample is published with value 0

#### Scenario: Throughput is cluster-wide
- **WHEN** `cassandra.read_ops` is streamed
- **THEN** each cycle publishes one sample with `entity=cluster`

### Requirement: Gauge sample age limit

The gauge queries (`cassandra.read_p99`, `cassandra.write_p99` and `cassandra.compaction_pending`) SHALL use only samples younger than 30 seconds.  A series whose newest sample is 30 seconds old or older SHALL drop out.  They SHALL NOT wait for Mimir's 5-minute lookback.

For up to 30 seconds after `cassandra use` switches builds, the series of both builds can count.  The docs SHALL say so.

#### Scenario: Cassandra is not running
- **WHEN** Cassandra is not running
- **THEN** the `cassandra.*` names are still accepted
- **AND** they publish nothing and report no failure

#### Scenario: Cassandra stops
- **WHEN** Cassandra stops on every node while the stream runs
- **THEN** the gauges stop publishing within 30 seconds, not after Mimir's 5-minute lookback

#### Scenario: Cassandra switches builds
- **WHEN** `cassandra use` switches to another build while the stream runs
- **THEN** the gauges stop using the old JVM's series within 30 seconds

#### Scenario: Cassandra starts mid-stream
- **WHEN** Cassandra starts while the stream runs
- **THEN** the `cassandra.*` names publish from the next cycle after their series reach Mimir
- **AND** the stream does not need a restart

#### Scenario: Stale sample next to a fresh one
- **WHEN** one node's newest `cassandra_compaction_tasks_pending` sample is 40 seconds old and another node's is 5 seconds old
- **THEN** `cassandra.compaction_pending` counts only the fresh node

#### Scenario: Build-switch overlap is documented
- **WHEN** a user reads the metrics stream docs
- **THEN** they state the 30-second sample age limit
- **AND** they say that for up to 30 seconds after `cassandra use` both builds' series can count

### Requirement: Missing values publish nothing

When a query succeeds but returns no value, NaN or Inf for a host or for the cluster, the stream SHALL publish no sample for it in that cycle.  It SHALL NOT count it as a failure.  It SHALL NOT publish 0 in its place.

#### Scenario: Empty result
- **WHEN** a query succeeds and returns no series
- **THEN** no sample is published for that metric in that cycle
- **AND** no failure is reported

#### Scenario: NaN or Inf value
- **WHEN** a query succeeds and returns NaN or Inf for a host
- **THEN** no sample is published for that host in that cycle
- **AND** no 0 is published in its place
- **AND** no failure is reported

### Requirement: Query failure isolation and reporting

A query failure SHALL be no HTTP response, a non-200 status, or a 200 whose body status is not `success`.  When one metric's query fails, the stream SHALL still publish every other selected metric in that cycle.  It SHALL try the failed metric again in the next cycle.

Every metric SHALL start healthy.  When a metric starts failing, the stream SHALL emit one `Metrics.MetricFailing` event that names the metric and the reason.  While it keeps failing, the stream SHALL emit nothing more for it.  When it next succeeds, the stream SHALL emit one `Metrics.MetricRecovered` event.

#### Scenario: One failed query does not block the others
- **WHEN** one metric's query fails in a cycle
- **THEN** every other selected metric is still published in that cycle
- **AND** the failed metric is queried again in the next cycle

#### Scenario: Each kind of failure counts
- **WHEN** a query gets no HTTP response, a non-200 status, or a 200 whose body status is not `success`
- **THEN** that query counts as a failure

#### Scenario: Failure reported once
- **WHEN** a metric fails in three cycles in a row
- **THEN** one `Metrics.MetricFailing` event is emitted for it, naming the metric and the reason

#### Scenario: Recovery reported once
- **WHEN** a failing metric next succeeds
- **THEN** one `Metrics.MetricRecovered` event is emitted for it

### Requirement: Terminal summary per cycle

After each cycle, the stream SHALL print one line on the terminal.  The line SHALL give the number of samples published and the number of metrics that failed.  It SHALL be printed, not emitted as an event.  Individual samples SHALL NOT be printed.

#### Scenario: One line per cycle
- **WHEN** a cycle completes
- **THEN** the terminal shows one line with the number of samples published and the number of metrics that failed
- **AND** no individual sample is printed

### Requirement: Redis configuration at start

The stream SHALL need `EASY_DB_LAB_REDIS_URL`.  It SHALL check Redis before the first cycle.

#### Scenario: Redis URL not set
- **WHEN** `metrics stream` runs and `EASY_DB_LAB_REDIS_URL` is not set
- **THEN** the command exits non-zero before the first cycle
- **AND** its message names `EASY_DB_LAB_REDIS_URL`

#### Scenario: Redis does not answer at start
- **WHEN** `EASY_DB_LAB_REDIS_URL` is set and Redis does not answer at start
- **THEN** the command exits non-zero before the first cycle
- **AND** its message names `EASY_DB_LAB_REDIS_URL` and the URL that failed

### Requirement: Redis loss during the stream

The stream SHALL ping Redis at the start of every cycle.  When a ping or a publish fails, the stream SHALL try to reconnect with the network retry settings: 3 attempts, 1 second then 2 seconds apart.  It SHALL drop the rest of that cycle's samples.  Samples that could not be published SHALL NOT be queued or replayed.  When a reconnect succeeds, streaming SHALL go on from the next tick, the command SHALL emit `Metrics.RedisReconnected` with the redacted Redis URL, the failure reason and the number of samples dropped, and that cycle's summary line SHALL show the dropped count.  When all 3 attempts fail, the command SHALL emit `Metrics.RedisLost` and exit non-zero with a message that names the Redis URL.

The stream SHALL check its stop flag before a ping or publish failure counts as Redis lost.

#### Scenario: Loss found within one cycle
- **WHEN** Redis is lost while the stream runs and no sample is published in the next cycle
- **THEN** the stream finds the loss at the start of the next cycle, through its ping

#### Scenario: Reconnect succeeds
- **WHEN** Redis is lost and comes back before the third attempt
- **THEN** the stream reconnects and goes on streaming
- **AND** the samples it could not publish are dropped

#### Scenario: Reconnect is reported
- **WHEN** a publish fails mid-cycle and the reconnect succeeds
- **THEN** one `Metrics.RedisReconnected` event is emitted with the redacted Redis URL, the failure reason and the number of samples dropped
- **AND** that cycle's summary line shows the number of samples dropped

#### Scenario: Reconnect fails
- **WHEN** Redis is lost and all 3 reconnect attempts fail
- **THEN** the attempts were 1 second and then 2 seconds apart
- **AND** the command exits non-zero with a message that names the Redis URL

#### Scenario: Stop during a reconnect
- **WHEN** the stream is stopped while it waits to reconnect to Redis
- **THEN** the command exits with status 0, not as Redis lost

### Requirement: Running cluster required at start

The stream SHALL need a running cluster in the workspace at start.  Each cycle SHALL check the cluster state.  When the cluster is not running or has no control host, every selected metric SHALL fail in that cycle with the reason that the cluster is not running, and no query SHALL be sent.

#### Scenario: No running cluster at start
- **WHEN** `metrics stream` runs in a workspace with no running cluster
- **THEN** the command exits non-zero before the first cycle

#### Scenario: Cluster torn down mid-stream
- **WHEN** the cluster is torn down while the stream runs
- **THEN** the command keeps running
- **AND** it reports each metric's failure once, until the caller stops it

### Requirement: Stream reuses the recorded tunnel

Before the first cycle, the stream SHALL start or reuse the SOCKS tunnel, as every command that needs the proxy does.  After that, the stream SHALL never start, stop or replace a tunnel.

At the start of each cycle, the stream SHALL read the recorded tunnel from the workspace's sourceable proxy env file (`EDL_SOCKS_PORT` and `EDL_SOCKS_PID`).  A recorded tunnel SHALL count as live only while its process is running and is the workspace's tunnel script for that port.  The stream SHALL send its queries through that port, with an explicit per-connection SOCKS proxy and the tenant header.  It SHALL NOT use a JVM-wide proxy setting.  Where Tailscale is active, the stream SHALL query Mimir directly.

When no live tunnel is recorded, every selected metric SHALL fail in that cycle with a reason that says to run `easy-db-lab socks start`.

#### Scenario: Tunnel stops carrying traffic
- **WHEN** the recorded SOCKS tunnel stops carrying traffic while the stream runs
- **THEN** the stream does not start or replace a tunnel
- **AND** each affected metric reports one failure
- **AND** each recovers when the tunnel reconnects on its own

#### Scenario: A new tunnel is recorded on another port
- **WHEN** another command or `socks start` records a new tunnel on a different port
- **THEN** the stream uses the new port from the next cycle

#### Scenario: No live tunnel recorded
- **WHEN** no tunnel is recorded, or the recorded tunnel process has exited
- **THEN** each metric reports one failure whose reason says to run `socks start`

#### Scenario: Recorded PID now belongs to another program
- **WHEN** the recorded tunnel PID is now held by a program that is not the tunnel script
- **THEN** each metric reports one failure whose reason says to run `socks start`

#### Scenario: Tailscale is active
- **WHEN** Tailscale is active for the cluster
- **THEN** the stream queries Mimir directly, with no SOCKS proxy

### Requirement: Cycle scheduling

Cycles SHALL start on 5-second ticks of the stream's start time.  When a cycle is still running at the next tick, the stream SHALL skip that tick.  Cycles SHALL NOT overlap.  The interval is fixed and cannot be configured.

#### Scenario: Slow cycle skips a tick
- **WHEN** a cycle is still running at the next 5-second tick
- **THEN** that tick is skipped
- **AND** the next cycle starts on the following tick

#### Scenario: Cycle waiting on a Redis reconnect
- **WHEN** a cycle waits on a Redis reconnect past the next tick
- **THEN** that tick is skipped
- **AND** no second cycle runs at the same time

### Requirement: Stopping the stream

The first Ctrl-C or SIGTERM SHALL stop the stream cleanly.  The command SHALL exit with status 0, including when the signal arrives in the middle of a cycle.  After the signal, the stream SHALL publish nothing more and emit no further health event.  A second Ctrl-C or SIGTERM while it is stopping SHALL exit at once with the default status (130 or 143).

#### Scenario: Stopped between cycles
- **WHEN** the stream gets Ctrl-C or SIGTERM between cycles
- **THEN** the command exits with status 0

#### Scenario: Stopped in the middle of a cycle
- **WHEN** the stream gets Ctrl-C or SIGTERM in the middle of a cycle
- **THEN** the command exits with status 0
- **AND** it publishes nothing more

#### Scenario: Second signal while stopping
- **WHEN** a second Ctrl-C or SIGTERM arrives while the stream is stopping
- **THEN** the command exits at once with status 130 for Ctrl-C or 143 for SIGTERM

### Requirement: metrics list

`easy-db-lab metrics list` SHALL print every name that `metrics stream` accepts, one per line.  It is a read-only display command.  It SHALL emit no events and need no cluster.

#### Scenario: Names listed
- **WHEN** `metrics list` runs
- **THEN** it prints each of the 14 accepted names, one per line

### Requirement: Stress job completion

Each cycle, `metrics stream` SHALL list the stress jobs in the cluster through the same reuse-only route as its queries; it SHALL never start or replace a tunnel to do so.  For each job it saw running in an earlier cycle that has now finished, it SHALL emit `Stress.JobCompleted(jobName, host, outcome, durationSeconds)` once.  `host` is the alias of the node the stress pod ran on.  `outcome` is `succeeded` or `failed`, from the Kubernetes job status.  `durationSeconds` is the job's start time to its completion time.  A failure to list the jobs SHALL be logged and that cycle's check skipped; it SHALL NOT count as a metric failure or change the cycle's samples.

#### Scenario: Job succeeds
- **WHEN** a stress job the stream saw running finishes successfully
- **THEN** one `Stress.JobCompleted` is emitted with its job name, host, `outcome=succeeded` and its duration in seconds

#### Scenario: Job fails
- **WHEN** a stress job the stream saw running finishes as failed
- **THEN** one `Stress.JobCompleted` is emitted with `outcome=failed`

#### Scenario: Job already finished at start
- **WHEN** a stress job had already finished before the stream started
- **THEN** no `Stress.JobCompleted` is emitted for it

#### Scenario: Job deleted while running
- **WHEN** a running stress job is deleted, for example by `cassandra stress stop`
- **THEN** no `Stress.JobCompleted` is emitted for it

#### Scenario: Job list fails
- **WHEN** listing the stress jobs fails in a cycle
- **THEN** the failure is logged, no `Stress.JobCompleted` is emitted in that cycle, and the cycle's samples and metric failures are unchanged
- **AND** a job that finished during the failure is reported in the next cycle whose list succeeds

