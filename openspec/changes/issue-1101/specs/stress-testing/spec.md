## MODIFIED Requirements

### Requirement: Stress Job Lifecycle

The system MUST support starting, stopping, and monitoring stress jobs against a Cassandra cluster. Stress pods SHALL run on the pod network with no `hostNetwork` and no `hostPath`, on nodes matching the app role's selector. The target SHALL be a `StressTarget`: the running `cassandra-pods` kit (its NodePort Service, port and `--dc`) when installed and running, otherwise the VM db hosts on port 9042; with neither, `stress start` SHALL fail naming both. `stress start` SHALL NOT require a control host.

#### Scenario: Start a stress job

- **GIVEN** a running Cassandra cluster
- **WHEN** the user starts a stress job with parameters
- **THEN** the job runs on nodes matching the app role's selector, on the pod network.

#### Scenario: Stress targets the Cassandra kit

- **GIVEN** the `cassandra-pods` kit is running and no VM db hosts exist
- **WHEN** the user starts a stress job
- **THEN** its contact point is the kit's Service, its port the kit's NodePort and its DC the kit's `--dc`.

#### Scenario: No stress target

- **GIVEN** neither the `cassandra-pods` kit nor VM db hosts
- **WHEN** the user starts a stress job
- **THEN** it fails naming both targets.

#### Scenario: Stop a stress job

- **GIVEN** a running stress job
- **WHEN** the user stops it
- **THEN** the job is terminated.

#### Scenario: Check stress job status

- **GIVEN** active stress jobs
- **WHEN** the user checks status
- **THEN** running jobs and their states are displayed.
