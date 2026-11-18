## MODIFIED Requirements

### Requirement: Stress job start reports its host

When `cassandra stress start` has the stress pod running, the `Stress.JobStarted` event it emits SHALL carry a `host` field: the alias of the node the stress pod runs on, for example `app0`.  The existing fields (`jobName`, `image`, `contactPoint`, `promPort`, `args`) SHALL stay.  `promPort` SHALL be the run's metrics port, in the window 9500-9599 that node tuning reserves: the Nth run takes 9500 + (N mod 100).  Run N and run N+100 on one node therefore share a port and the metrics `instance` label, and run N+100 cannot bind its metrics port when run N is still running on that node.  `metrics stream` SHALL NOT emit `Stress.JobStarted`, so each start is reported once.

#### Scenario: Start reports the host
- **WHEN** `cassandra stress start` starts a job and its pod is running on `app0`
- **THEN** the `Stress.JobStarted` event carries `host=app0` along with its existing fields

#### Scenario: The metrics port stays in the reserved window
- **WHEN** the 100th stress run starts
- **THEN** its `Stress.JobStarted` event carries `promPort=9500`
