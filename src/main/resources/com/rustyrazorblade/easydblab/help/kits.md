---
name: kits
description: Install and run a packaged workload such as ClickHouse, Presto, or TiDB
---
# Running a kit

A kit is a self-contained package that installs, starts, stops, and optionally backs up a
workload on your cluster. Each kit defines its whole lifecycle in a `kit.yaml` file, so you do
not write any Kubernetes YAML. The tool ships kits for ClickHouse, Presto, Trino, TiDB, and
sysbench, among others.

Run these commands from the cluster's workspace directory, after `up` has finished.

## Step 1: See what is available

```bash
easy-db-lab kit list
```

Inspect a kit before you install it. This shows its arguments, its endpoints, and the commands it
adds:

```bash
easy-db-lab kit info clickhouse
```

## Step 2: Install the kit

Give the kit the arguments it needs. Each kit declares its own; use `kit info <name>` or `--help`
to see them.

```bash
easy-db-lab kit install clickhouse --clickhouse-version 25.4 --size 100Gi
```

Install writes the kit's files into a subdirectory of the workspace and registers the kit's
lifecycle commands automatically. No code change is required.

## Step 3: Start and stop the workload

The kit adds a command group named after itself. Each script in the kit becomes a subcommand:

```bash
easy-db-lab clickhouse start
easy-db-lab clickhouse stop
```

A dashboard for the kit is installed into Grafana automatically after a successful start.

## Benchmarking a database with a bench kit

A bench kit runs load against an already-running database kit. Point it at the target with
`--target`:

```bash
easy-db-lab kit install sysbench --target tidb
easy-db-lab sysbench-tidb prepare
easy-db-lab sysbench-tidb start
easy-db-lab sysbench-tidb stop
```

The bench kit installs into a directory named `<bench-kit>-<target>`, so you can run the same
bench against several databases at once and compare the results in Grafana.

## Step 4: Remove a kit

```bash
easy-db-lab kit uninstall clickhouse
```

## Related topics

- `provisioning` — create the cluster a kit runs on.
- `stress-testing` — drive load against the database or a kit.
