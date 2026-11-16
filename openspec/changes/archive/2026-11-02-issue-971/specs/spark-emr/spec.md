## ADDED Requirements

### Requirement: EMR nodes keep their own host name in metrics

The control node's collector SHALL keep the `host.name` that an EMR node's collector sends over OTLP.  In the `metrics/otlp` pipeline, the control node's collector SHALL set aside the `host.name` of each resource whose `node_role` is `spark-master` or `spark-worker` before `resource_detection`, and SHALL restore it after.  Every other OTLP producer SHALL still get the control node's host name from `resource_detection`.

#### Scenario: An EMR node's metrics carry its own host name

- **WHEN** an EMR node's collector sends metrics with its own `host.name` and `node_role=spark-master` to the control node's collector
- **THEN** the metrics in Mimir carry the EMR node's `host_name`, not `control0`

#### Scenario: Other OTLP producers get the control node's host name

- **WHEN** an OTLP producer with no `spark-master` or `spark-worker` `node_role` sends metrics to the control node's collector
- **THEN** the metrics in Mimir carry the control node's `host_name`

### Requirement: EMR nodes ship their EMR and YARN log files to Loki

The collector on each EMR node SHALL read, from the start of each file, the YARN container logs (`/var/log/hadoop-yarn/containers/application_*/container_*/*`), the step logs (`/mnt/var/log/hadoop/steps/*/*`) and the bootstrap action logs (`/emr/instance-controller/log/bootstrap-actions/*/*`), and SHALL skip `.gz` files.  It SHALL send each line to the control node's collector with `source=emr`, the node's `node_role` and host name, and the file path.  The control node's collector SHALL add the cluster label, so the lines reach Loki scoped to the cluster.

#### Scenario: The EMR collector config reads the log files

- **WHEN** easy-db-lab provisions an EMR cluster and uploads the bootstrap script with the collector config
- **THEN** the config has a `file_log/emr` receiver on the three paths, reading from the start
- **AND** the logs pipeline receives from it

#### Scenario: A step stderr line reaches Loki

- **WHEN** a line is written to a step's `stderr` file on an EMR node
- **THEN** the line is in Loki with `source="emr"`, the node's `node_role` and the cluster label

### Requirement: spark submit --wait waits for the final stderr of a failed step

If a step submitted with `spark submit --wait` fails, the command SHALL wait for the copy of the step's `stderr.gz` that EMR uploads after the step ends, before it shows the logs.  The wait SHALL be bounded: up to 28 checks, 15 seconds apart.  A copy SHALL count as final when its S3 `LastModified` is after the step's end time; with no end time, any uploaded copy SHALL count.  If no final copy arrives in time, the command SHALL print the S3 path of the step's `stderr.gz` and the command that fetches it later.

#### Scenario: The final stderr arrives

- **WHEN** a step fails and EMR uploads its `stderr.gz` after the step's end time, within the wait
- **THEN** the command downloads and shows the step's logs from that copy

#### Scenario: Only an earlier copy exists

- **WHEN** a step fails and the only `stderr.gz` in S3 was uploaded before the step ended
- **THEN** the command keeps waiting and does not show that copy

#### Scenario: The final stderr does not arrive in time

- **WHEN** a step fails and no final `stderr.gz` arrives within the wait
- **THEN** the command prints the S3 path where the step's `stderr.gz` will appear
- **AND** it prints `easy-db-lab spark status --step-id <step-id> --logs` to fetch it later
