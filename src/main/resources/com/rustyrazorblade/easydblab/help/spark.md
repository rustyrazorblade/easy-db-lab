---
name: spark
description: Provision EMR and submit Spark jobs
---
# Spark

Provision an EMR cluster and submit Spark jobs. Requires an active cluster (after `up`). Example jobs built separately in `../spark-examples` repo.

Steps:
1. `easy-db-lab spark init` — provisions EMR on the existing cluster's VPC. Defaults: 3 m5.xlarge workers and an m5.xlarge master; change them with `--worker.instance.count`, `--worker.instance.type` and `--master.instance.type`.
2. `easy-db-lab spark submit --jar <path> --main-class <class> --args <arg>` — submit a job. `--jar` takes a local path or an `s3://` URI; `--args` takes the application arguments. Add `--wait` to block until the job finishes.
3. `easy-db-lab spark status` — state, start time, and duration of the most recent job. Add `--step-id <id>` for another job, `--verbose` for the full step detail.
4. `easy-db-lab spark logs` — the job's logs from Loki. Defaults to the most recent job; `--step-id <id>` picks another, `--since 1h` sets the time range.
5. `easy-db-lab spark jobs` — list recent jobs with status and step IDs.
6. `easy-db-lab spark stop` — cancel the most recent job if it is running or pending; `--step-id <id>` cancels another.
7. `easy-db-lab spark down` — terminate EMR cluster.

Notes:
- Build example jobs in `../spark-examples`, then point `spark submit` at the built jar.
- Logs ingested to Loki; query via Grafana, `easy-db-lab spark logs` or `easy-db-lab logs query`.

Related: `provisioning`, `observability`.
