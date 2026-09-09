---
name: stress-testing
description: Drive load against the database and watch the results
---
# Stress testing

Run load against the db on the cluster's Kubernetes (application node group). Provision app nodes at init: `--app.count N`. Run from the workspace dir, after the db is started.

Steps:
1. `easy-db-lab cassandra stress list` — workloads. `easy-db-lab cassandra stress info <workload>` — details/fields.
2. `easy-db-lab cassandra stress start` — launch the job on K8s; returns once scheduled.
3. Watch: `easy-db-lab cassandra stress status`, `easy-db-lab cassandra stress logs`. Grafana shows throughput/latency (URLs from `easy-db-lab status`).
4. `easy-db-lab cassandra stress stop` — stop and delete jobs.

For non-Cassandra databases, use a bench kit (e.g. sysbench) instead — see `kits`.

Related: `provisioning`, `cassandra`, `kits`.
