---
name: spark
description: Provision EMR and submit Spark jobs
---
# Spark

Provision an EMR cluster and submit Spark jobs. Requires an active cluster (after `up`). Example jobs built separately in `../spark-examples` repo.

Steps:
1. `easy-db-lab spark init` — provisions EMR on the existing cluster's VPC. Defaults: 3 workers, instance type from cluster profile.
2. `easy-db-lab spark submit <jar-path> [args]` — submit job. Jar built in separate `spark-examples` repo.
3. `easy-db-lab spark status [<job-id>]` — defaults to most recent job. Shows state, start time, duration.
4. `easy-db-lab spark logs [<job-id>]` — stderr/stdout. Defaults to most recent.
5. `easy-db-lab spark jobs` — list recent jobs with status.
6. `easy-db-lab spark stop [<job-id>]` — cancel running/pending job.
7. `easy-db-lab spark down` — terminate EMR cluster.

Notes:
- Build example jobs in `../spark-examples`, then point `spark submit` at the built jar.
- Logs ingested to VictoriaLogs; query via Grafana or `easy-db-lab logs query`.

Related: `provisioning`, `observability`.
