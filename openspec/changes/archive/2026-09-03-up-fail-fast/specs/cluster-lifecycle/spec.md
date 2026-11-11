## MODIFIED Requirements

### REQ-CL-004: Cluster Status

The system MUST provide comprehensive status of cluster resources including nodes, networking, Kubernetes pods, and running workloads.

`status` is the command a user reaches for when the cluster is not behaving, so it MUST remain useful when parts of the cluster are unreachable. Unlike every other command, `status` MUST NOT fail when the SOCKS proxy cannot be established. It MUST instead report every section it can still observe, and mark each unavailable section with the reason it could not be read.

A section is unavailable only when the transport it actually depends on is unavailable. Sections sourced from cluster state, the AWS APIs, or direct SSH to the nodes MUST still be reported when the SOCKS proxy is down — SSH never traverses the tunnel. Only sections sourced from the private Kubernetes API may be marked unavailable on a proxy failure.

This is the single deliberate exception to the rule that an unreachable cluster API fails the command. It is permitted because `status` is read-only: it changes no state, and reporting partial state cannot leave the cluster in an unexpected condition. No command that mutates state may take this exception.

#### Scenario: Full status on a healthy cluster
- **GIVEN** a running cluster
- **WHEN** the user checks status
- **THEN** the state of EC2 instances, VPC networking, K8s pods, stress jobs, and database versions SHALL be displayed

#### Scenario: Status degrades when the cluster API is unreachable
- **GIVEN** a running cluster whose SOCKS proxy cannot be established
- **WHEN** the user checks status
- **THEN** `status` SHALL report EC2 instance state, VPC networking state, and database versions, none of which depend on the tunnel
- **AND** only the Kubernetes and workload sections SHALL be marked unavailable
- **AND** each unavailable section SHALL state why it could not be read, referencing the proxy failure
- **AND** `status` SHALL NOT abort before reporting the sections it can observe

#### Scenario: Degraded and healthy reports share one rendering path
- **WHEN** `status` renders a report, degraded or not
- **THEN** every section not sourced from the private Kubernetes API SHALL be rendered by the same code path in both cases
- **AND** a section added to the healthy report SHALL appear in the degraded report without further change, unless it depends on the private Kubernetes API

#### Scenario: Degraded status is still an unsuccessful command
- **GIVEN** `status` could not read one or more sections
- **WHEN** the command completes
- **THEN** it SHALL exit non-zero, so scripts do not read a partial report as a healthy cluster

## ADDED Requirements

### Requirement: Provisioning reports success only when it fully succeeded

The system MUST NOT report successful provisioning unless every provisioning step succeeded. When any provisioning step fails, `up` MUST abort at the point of failure and exit non-zero.

There is no provisioning step whose failure may be logged and stepped over. A step that is skipped because there is nothing for it to do has not failed; a step that was attempted and threw has failed, and MUST abort provisioning.

This applies to every step invoked during provisioning, including nested commands whose exit codes were previously discarded, and to operations whose results were previously reduced to a log line.

#### Scenario: A failing provisioning step aborts `up`
- **WHEN** any provisioning step fails during `up` — writing configuration files, node setup, K3s cluster setup, StorageClass creation, node labeling, Tailscale startup, IAM policy application, or observability deployment
- **THEN** `up` SHALL abort at that step
- **AND** `up` SHALL exit non-zero
- **AND** the reported error SHALL identify the step that failed

#### Scenario: A failing nested command aborts `up`
- **WHEN** `up` invokes a nested command and that command returns a non-zero exit code
- **THEN** `up` SHALL abort
- **AND** `up` SHALL exit non-zero

#### Scenario: Unreachable cluster API aborts `up`
- **GIVEN** the cluster's Kubernetes API cannot be reached
- **WHEN** `up` attempts to apply node labels, StorageClasses, or the observability stack
- **THEN** `up` SHALL abort and exit non-zero
- **AND** `up` SHALL NOT report a successfully provisioned cluster

#### Scenario: Progress events assert only verified outcomes
- **WHEN** a provisioning step emits an event announcing its completion
- **THEN** that event SHALL be emitted only if the step actually succeeded

#### Scenario: Explicit opt-out is not a failure
- **WHEN** the user passes `--no-setup`
- **THEN** node setup SHALL be skipped
- **AND** `up` SHALL exit zero if all remaining steps succeeded

### Requirement: Cluster shape invariants are validated before provisioning

The system MUST validate the cluster's required shape before any AWS resource is provisioned, so that an unsatisfiable configuration fails before EC2 instances are launched rather than being discovered part-way through `up`.

A cluster MUST have a control node. An S3 bucket MUST be configured. Neither may be treated as an optional condition that causes provisioning steps to be silently skipped.

#### Scenario: Missing control node fails before provisioning
- **GIVEN** a cluster configuration that would produce no control node
- **WHEN** the user runs `up`
- **THEN** the command SHALL fail before any EC2 instance is launched
- **AND** the error SHALL state that a control node is required

#### Scenario: Missing S3 bucket fails before provisioning
- **GIVEN** no S3 bucket is configured
- **WHEN** the user runs `up`
- **THEN** the command SHALL fail before any EC2 instance is launched
- **AND** the error SHALL state that an S3 bucket is required

#### Scenario: Provisioning steps do not defend against missing invariants
- **WHEN** provisioning proceeds past validation
- **THEN** no provisioning step SHALL skip its work on the grounds that a control node or S3 bucket is absent

### Requirement: A cluster with zero database nodes is a valid configuration

The system MUST support provisioning a cluster with no database nodes. Workloads such as Trino with OpenSearch require a control node and application nodes but no database nodes.

Work that exists solely to serve database nodes MUST be skipped without warning when there are none. A supported configuration MUST NOT produce warning output.

#### Scenario: Zero database nodes provisions successfully
- **GIVEN** a cluster configuration with a control node, application nodes, and zero database nodes
- **WHEN** the user runs `up`
- **THEN** provisioning SHALL complete successfully and exit zero

#### Scenario: Database node labeling is skipped silently
- **GIVEN** a cluster with zero database nodes
- **WHEN** provisioning reaches database node labeling
- **THEN** the step SHALL be skipped
- **AND** no warning SHALL be emitted to the user

### Requirement: Control node SSH readiness is confirmed before it is used

The system MUST confirm that the control node is accepting SSH connections before any provisioning step depends on connecting to it.

#### Scenario: Provisioning waits for control node SSH
- **WHEN** `up` waits for instances to become reachable
- **THEN** the wait SHALL include the control node, not only database nodes
- **AND** provisioning SHALL NOT proceed to node setup or tunnel establishment until the control node accepts SSH connections

#### Scenario: Control node that never accepts SSH aborts `up`
- **GIVEN** the control node does not accept SSH connections within the retry window
- **WHEN** `up` waits for SSH readiness
- **THEN** `up` SHALL exit non-zero
