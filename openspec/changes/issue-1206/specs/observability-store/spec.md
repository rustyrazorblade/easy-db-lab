## ADDED Requirements

### Requirement: eBPF profiles wait out a Pyroscope outage

Every `pyroscope-ebpf` pod SHALL run a profile relay next to Alloy, and Alloy's `pyroscope.write` SHALL send only to that relay, on `127.0.0.1`.  The relay SHALL accept each push at once, hold it in memory, and forward it unchanged, with the `X-Scope-OrgID` header Alloy sent, to the cluster's Pyroscope: the control node's server, or the redirect's profiles URL.

- It SHALL keep a push and send it again, with no retry limit, when Pyroscope does not answer, times out, answers 5xx, 408 or 429, or answers 422 with a body naming a server that is shutting down or starting ("service is unavailable", "empty ring").
- It SHALL drop a push Pyroscope answers with any other 4xx, and print the status and the tenant, so a malformed push cannot hold the queue.
- Its queue SHALL be bounded by bytes and sized to hold 1 hour of the node's profiles.  When it is full it SHALL drop the oldest pushes and print how many it dropped.
- It SHALL hold everything in memory: no file, no WAL, no persistent queue, no `memory_limiter`.
- It SHALL start before Alloy and stop after Alloy, so a rollout of the DaemonSet loses no push Alloy made.
- It SHALL ship in the distribution and reach the nodes in the DaemonSet's ConfigMap, so it needs no source checkout, node upload or image build, and runs on any Kubernetes cluster.

What the relay holds is lost if the relay itself restarts.

#### Scenario: A Pyroscope outage loses no eBPF profile
- **WHEN** Pyroscope is stopped for 60 seconds while the eBPF profiler runs, and is then started again
- **THEN** every 15-second window of the outage holds eBPF profiles in Pyroscope

#### Scenario: An hour-long Pyroscope outage loses no eBPF profile
- **WHEN** Pyroscope is down for up to 1 hour while the eBPF profiler runs
- **THEN** the relay holds every push it could not send and sends it after Pyroscope starts
- **AND** Pyroscope accepts them, none rejected as too old

#### Scenario: A rejected push is not retried forever
- **WHEN** Pyroscope answers a push with 400
- **THEN** the relay drops that push, prints the status and the tenant, and sends the next one

#### Scenario: A full queue drops the oldest push
- **WHEN** the relay's queue is full and Alloy sends another push
- **THEN** the relay drops the oldest pushes to make room, prints how many, and accepts the new one

#### Scenario: The relay keeps the tenant
- **WHEN** a cluster in tenant `acme` sends eBPF profiles through the relay
- **THEN** each push reaches Pyroscope with `X-Scope-OrgID: acme`

#### Scenario: A new relay jar rolls the DaemonSet
- **WHEN** `up` or `grafana update-config` runs with a relay jar that differs from the one in the ConfigMap
- **THEN** the `pyroscope-ebpf` DaemonSet's configuration hash changes and the DaemonSet rolls

#### Scenario: The relay queues nothing on disk
- **WHEN** the `pyroscope-ebpf` DaemonSet is built
- **THEN** the relay container mounts no writable volume

### Requirement: Pyroscope accepts profiles up to 2 hours old

The cluster's Pyroscope server SHALL set `limits.reject_older_than` to 2 hours, so a profile a sender held through an outage of up to 1 hour is accepted when it is replayed.  Pyroscope's default of 1 hour would reject the oldest profiles of such an outage.  The setting applies to the cluster's own server; a redirected cluster's external Pyroscope keeps its owner's setting.

#### Scenario: A profile held for an hour is accepted
- **WHEN** a sender pushes a profile collected 70 minutes earlier
- **THEN** Pyroscope accepts it

## MODIFIED Requirements

### Requirement: Every observability backend runs native multi-tenancy

Mimir, Loki, Tempo and the Pyroscope server SHALL run with multi-tenancy enabled.  The storage tenant is the cluster's tenant.  Every writer SHALL send the tenant in the `X-Scope-OrgID` header: the OTel collector's metrics, logs and trace exporters, Tempo's metrics generator, the Alloy eBPF profiler (through the profile relay, which forwards the header unchanged), the Pyroscope Java agent in stress jobs, the Cassandra sidecar, EMR Spark, the Trino and Presto kits, the Cassandra JFR shipper, and the annotation mirror.  This SHALL hold on redirected clusters too.  Every reader SHALL send the tenant: the Grafana datasources, `logs query`, `spark logs`, the EMR step log lookup, `metrics stream`, `status`, and the teardown flush.  Mimir SHALL allow queries across tenants (tenant federation) and Loki SHALL allow queries across tenants (`multi_tenant_queries_enabled`), with the tenants listed as `a|b`.

#### Scenario: Every writer carries the tenant
- **WHEN** a cluster initialized with `--tenant acme` writes metrics, logs, traces and profiles
- **THEN** every write carries `X-Scope-OrgID: acme`

#### Scenario: Datasources query the cluster's tenant
- **WHEN** a user opens the Mimir, Loki, Tempo or Pyroscope datasource in the cluster's Grafana
- **THEN** the query carries the cluster's tenant and returns that tenant's data

#### Scenario: Redirected clusters send the tenant too
- **WHEN** a redirected cluster sends metrics, logs, traces and profiles to an external stack
- **THEN** each write carries the cluster's tenant in `X-Scope-OrgID`

#### Scenario: Logs are queried across tenants and clusters
- **WHEN** two clusters of tenants `a` and `b` have written logs and a query runs with `X-Scope-OrgID: a|b`
- **THEN** the result holds the lines of both clusters, each marked with its tenant

#### Scenario: Metrics are queried across tenants within one Mimir
- **WHEN** a Mimir holds series of tenants `a` and `b` and a query runs with `X-Scope-OrgID: a|b`
- **THEN** the result holds the series of both tenants, each with a `__tenant_id__` label

#### Scenario: Tempo's metrics generator writes to Mimir
- **WHEN** Tempo's metrics generator has processors enabled
- **THEN** it remote-writes to Mimir with the cluster's tenant

### Requirement: Observability images are pinned to released versions

Every image the observability stack runs SHALL be pinned to a released version.  No observability image SHALL use `latest`.  The versions SHALL be: Mimir 3.2.1, Loki 3.7.8, Pyroscope 2.3.1, Tempo 3.0.3, Grafana 13.2.2, Grafana image renderer v5.12.4, Alloy v1.20.0, Beyla 3.36.0, Fluent Bit 5.1.2, OTel collector contrib 0.161.0 (cluster, stress-job sidecar, and EMR), and the JRE `eclipse-temurin:21.0.13_9-jre` that runs the profile relay and the account compactor's lease agent.  The `aws-sigv4-proxy` image and the documents web server image in the Grafana pod SHALL each be pinned to a released version.  No VictoriaMetrics, VictoriaLogs, vmbackup or AWS CLI image SHALL be deployed.

#### Scenario: Stack runs the pinned versions
- **WHEN** the observability stack starts
- **THEN** every image runs the version listed in this requirement
- **AND** no image reference uses `latest`

#### Scenario: Documents sidecars are pinned
- **WHEN** the Grafana Deployment is built
- **THEN** the `aws-sigv4-proxy` and documents web server images each name a released version tag

#### Scenario: The JRE image is pinned
- **WHEN** the `pyroscope-ebpf` DaemonSet and the `account-compactor` Deployment are built
- **THEN** the relay container and the lease container run the same JRE image, named by a full release tag

#### Scenario: Collector errors are not hidden
- **WHEN** a `transform` processor statement fails in the collector
- **THEN** the error propagates instead of being silently ignored

#### Scenario: Log queries work on the pinned Loki
- **WHEN** a dashboard panel, dashboard variable, annotation query, or CLI command runs a LogQL query against Loki 3.7.8
- **THEN** the query parses without a syntax error

#### Scenario: Metric queries work on the pinned Mimir
- **WHEN** a dashboard panel or CLI command runs a PromQL query against Mimir 3.2.1
- **THEN** the query parses without a syntax error

#### Scenario: Comparison queries work on the pinned Mimir
- **WHEN** a comparison or Tests dashboard query with a negative `offset`, an `@` modifier or a subquery runs against Mimir 3.2.1
- **THEN** the query parses and returns without an error

#### Scenario: Per-core CPU stays available
- **WHEN** a dashboard queries host CPU time by core
- **THEN** the host metrics carry the per-core label
