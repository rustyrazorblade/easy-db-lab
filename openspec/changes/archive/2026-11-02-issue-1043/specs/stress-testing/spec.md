## ADDED Requirements

### Requirement: Stress job start reports its host

When `cassandra stress start` has the stress pod running, the `Stress.JobStarted` event it emits SHALL carry a `host` field: the alias of the node the stress pod runs on, for example `app0`.  The existing fields (`jobName`, `image`, `contactPoint`, `promPort`, `args`) SHALL stay.  `metrics stream` SHALL NOT emit `Stress.JobStarted`, so each start is reported once.

#### Scenario: Start reports the host
- **WHEN** `cassandra stress start` starts a job and its pod is running on `app0`
- **THEN** the `Stress.JobStarted` event carries `host=app0` along with its existing fields
