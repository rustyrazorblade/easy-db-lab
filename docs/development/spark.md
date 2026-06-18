# Spark Development

The example Spark jobs — the bulk writers and the Spark Cassandra Connector
read/write examples — have moved to their own repository:
**[`spark-examples`](https://github.com/rustyrazorblade/spark-examples)** (locally `../spark-examples`).

That repo is where you:

- build and test the job modules (`common`, `bulk-writer-sidecar`, `bulk-writer-s3-iam`,
  `connector-writer`, `connector-read-write`),
- build [Apache Cassandra Analytics](https://github.com/apache/cassandra-analytics)
  (the bulk-writer modules depend on it), and
- publish the shadow (fat) job jars.

This repository keeps the **`spark` CLI commands** that provision EMR and run jobs:
`easy-db-lab spark init/submit/status/logs/jobs/down`. They submit a pre-built job
jar to EMR — download the published jar from the
[`spark-examples` releases](https://github.com/rustyrazorblade/spark-examples/releases),
then point `spark submit --jar <path>` at it. See the
[Spark user guide](../user-guide/spark.md) for provisioning EMR, submitting jobs, and
debugging failed steps.
