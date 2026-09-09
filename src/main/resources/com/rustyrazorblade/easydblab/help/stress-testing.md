---
name: stress-testing
description: Drive load against the database and watch the results
---
# Stress testing

This topic explains how to run a load test against your database and how to watch it while it
runs. Stress jobs run on the cluster's Kubernetes, on the application node group.

Provision application nodes for the load to run on. Set their count at `init` time:

```bash
easy-db-lab init my-cluster --db.count 3 --app.count 2
```

Run these commands from the cluster's workspace directory, after the database is started.

## Step 1: Pick a workload

List the workloads you can run:

```bash
easy-db-lab cassandra stress list
```

Read what a workload does and which fields it uses:

```bash
easy-db-lab cassandra stress info <workload>
```

## Step 2: Start the load

```bash
easy-db-lab cassandra stress start
```

This launches the stress job on Kubernetes. It returns once the job is scheduled; the load then
runs on the application nodes.

## Step 3: Watch it run

Check the job state:

```bash
easy-db-lab cassandra stress status
```

Follow the output:

```bash
easy-db-lab cassandra stress logs
```

Open Grafana to watch throughput and latency in real time. The observability URLs are printed by
`easy-db-lab status`.

## Step 4: Stop the load

```bash
easy-db-lab cassandra stress stop
```

This stops and deletes the stress jobs.

## Benchmarking other databases

To drive load against a non-Cassandra database (ClickHouse, TiDB, and so on), use a bench kit
such as sysbench instead. See the `kits` topic.

## Related topics

- `provisioning` — create the cluster and its application nodes.
- `configs` — tune the database before you measure it.
- `kits` — benchmark other databases with a bench kit.
