## ADDED Requirements

### Requirement: Raw output and command chatter are not events

The event hierarchy MUST NOT hold an event type whose purpose is to carry raw output text (the output of a command run on a cluster node, CQL query rows, log query lines, a stress job's output or a pod's logs), to format that text (a header or separator around it), or to announce that a remote command is executing or a file is uploading.  Such output MUST be printed as plain text: node and pod lines tagged with their source (see `node-output`), the rest with `println`.  Events MUST be kept for facts an external system needs, such as a node being ready or failing, or an attempt left running.

#### Scenario: No SSH output events
- **WHEN** the event hierarchy is listed
- **THEN** it holds no `Ssh.CommandOutput`, `Ssh.ExecutingCommand`, `Ssh.ExecutingHiddenCommand`, `Ssh.UploadingFile`, `Ssh.UploadingDirectory`, `Cassandra.NodetoolOutput`, `Cassandra.PatchOutput`, `Cassandra.PatchStderr`, `Cassandra.CqlQueryOutput`, `Logs.QueryResults`, `Stress.CommandOutput`, `Stress.PodLogOutput`, `Stress.PodLogHeader`, `Stress.PodLogsEmpty` or `Stress.PodLogSeparator`

#### Scenario: Node facts stay events
- **GIVEN** `EASY_DB_LAB_REDIS_URL` is set
- **WHEN** one node of an `up` becomes Ready and another fails
- **THEN** the Redis channel receives `Provision.NodeReady` and `Provision.NodeFailed`, each naming its node

#### Scenario: An abandoned attempt is an event
- **WHEN** a bring-up attempt does not stop within the grace after its interrupt
- **THEN** the bus receives `Provision.AttemptAbandoned` with the node, the step, the attempt number and the grace in milliseconds
