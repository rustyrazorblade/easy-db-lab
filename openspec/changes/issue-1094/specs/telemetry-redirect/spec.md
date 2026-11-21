## MODIFIED Requirements

### Requirement: All four signals export to the external stack under redirect

WHEN a cluster is redirecting, the OTel Collector SHALL export metrics, logs, and traces to the supplied external endpoints rather than to the in-cluster `*.<namespace>.svc` backends of the cluster namespace, and every profile producer SHALL ingest to the supplied external Pyroscope rather than the local control-node Pyroscope.  Every metric, log, trace and profile write SHALL carry the cluster's tenant in the `X-Scope-OrgID` header, exactly as on a cluster that does not redirect.

#### Scenario: Collector exports metrics, logs, and traces externally

- **WHEN** a redirecting cluster's OTel Collector is running
- **THEN** its metrics, logs, and traces are exported to the supplied external endpoints and not to any in-cluster service address

#### Scenario: All profile producers ship externally

- **WHEN** a redirecting cluster's profiling paths run (Cassandra async-profiler, Alloy eBPF agent, stress jobs, the Cassandra sidecar agent, EMR Spark, and the Trino and Presto kits)
- **THEN** each ships profiles to the supplied external Pyroscope endpoint and none targets the local control node

#### Scenario: Redirected writes carry the tenant

- **WHEN** a redirecting cluster in tenant `acme` sends metrics, logs, traces and profiles to the external stack
- **THEN** every write carries `X-Scope-OrgID: acme`

