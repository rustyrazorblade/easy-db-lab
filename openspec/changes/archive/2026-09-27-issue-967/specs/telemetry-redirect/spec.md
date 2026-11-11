## MODIFIED Requirements

### Requirement: All four signals export to the external stack under redirect

WHEN a cluster is redirecting, the OTel Collector SHALL export metrics, logs, and traces to the supplied external endpoints rather than to the in-cluster `*.default.svc.cluster.local` backends, and every profile producer SHALL ingest to the supplied external Pyroscope rather than the local control-node Pyroscope.  Every metric, log, trace and profile write SHALL carry the cluster's tenant in the `X-Scope-OrgID` header, exactly as on a cluster that does not redirect.

#### Scenario: Collector exports metrics, logs, and traces externally

- **WHEN** a redirecting cluster's OTel Collector is running
- **THEN** its metrics, logs, and traces are exported to the supplied external endpoints and not to any in-cluster service address

#### Scenario: All profile producers ship externally

- **WHEN** a redirecting cluster's profiling paths run (Cassandra async-profiler, Alloy eBPF agent, stress jobs, the Cassandra sidecar agent, EMR Spark, and the Trino and Presto kits)
- **THEN** each ships profiles to the supplied external Pyroscope endpoint and none targets the local control node

#### Scenario: Redirected writes carry the tenant

- **WHEN** a redirecting cluster in tenant `acme` sends metrics, logs, traces and profiles to the external stack
- **THEN** every write carries `X-Scope-OrgID: acme`

### Requirement: A redirecting cluster deploys collectors but no local backends

WHEN a cluster is brought up in redirect mode, its node-level collectors SHALL be deployed and running, and no Mimir, Loki, Tempo, Pyroscope server, or Grafana workload SHALL be stood up on that cluster's control node.  The Pyroscope eBPF agent SHALL still be deployed; only the Pyroscope server is skipped.

#### Scenario: Redirect bring-up runs collectors without local storage

- **WHEN** a cluster is brought up in redirect mode with all four endpoints supplied
- **THEN** the OTel Collector, Fluent Bit, Grafana Alloy, Beyla, ebpf_exporter, YACE, and OTel Java agent are deployed and running
- **AND** no Mimir, Loki, Tempo, Pyroscope server, or Grafana workload exists on the control node

#### Scenario: Normal bring-up is unchanged

- **WHEN** a cluster is brought up without redirect mode
- **THEN** the full local stack including Grafana is stood up and the collectors ship to the in-cluster backends, exactly as before

### Requirement: Stack-dependent commands refuse cleanly on a redirect cluster

WHEN a command that reads from or writes to the local observability stack is run against a redirect cluster, it SHALL report that it does not apply on a redirect cluster and SHALL touch no Grafana or backend — neither the (absent) local one nor the external stack.  This covers `grafana update-config`, `logs query`, and the pre-teardown annotation mirror, flushes and annotations backup that `down` runs.

#### Scenario: grafana update-config refuses on a redirect cluster

- **WHEN** `grafana update-config` is run against a redirect cluster
- **THEN** it reports that it does not apply on a redirect cluster and modifies no Grafana

#### Scenario: logs query refuses on a redirect cluster

- **WHEN** `logs query` is run against a redirect cluster
- **THEN** it reports that the data lives on the external stack and does not apply here, rather than failing with a bare connection error against the absent local backend

#### Scenario: Teardown skips the pre-teardown steps on a redirect cluster

- **GIVEN** a running redirect cluster
- **WHEN** the user tears it down without `--force`
- **THEN** the pre-teardown annotation mirror, flushes and annotations backup are skipped, with the reason reported
- **AND** teardown proceeds
