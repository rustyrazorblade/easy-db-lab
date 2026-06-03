# Lab Plan: ClickHouse + Presto Smoke Test

## Objective

Verify that ClickHouse and Presto install and start correctly, and that basic SQL works end-to-end: create a table in ClickHouse, query it directly via ClickHouse SQL, and query it via Presto's ClickHouse connector.

## Cluster Name

ch-presto-test

## Datacenters

single

## Environment

- 1 db node (`m5.xlarge`, gp3 EBS 50Gi) — runs ClickHouse via K8s
- 1 app node (`m5.xlarge`) — runs Presto (stateless)

## Steps

### 1. Provision the cluster

Spin up a single-DC cluster with 1 db node and 1 app node.

```bash
$EDB init ch-presto-test --db 1 --app 1 \
  --instance m5.xlarge \
  --ebs.type gp3 --ebs.size 50 \
  --up
```

### 2. Scaffold ClickHouse kit

Install the ClickHouse kit files with a small storage size suitable for a quick test.

```bash
$EDB kit install clickhouse --size 20Gi
```

### 3. Scaffold Presto kit

Install the Presto kit files.

```bash
$EDB kit install presto
```

### 4. Start ClickHouse

Deploy ClickHouse to the K8s cluster and wait for it to be ready.

```bash
$EDB clickhouse start
```

### 5. Create database and table in ClickHouse

Create a database and table, then insert a row.

```bash
$EDB clickhouse sql "CREATE DATABASE IF NOT EXISTS test_db"
$EDB clickhouse sql "CREATE TABLE test_db.events (id UInt64, name String, ts DateTime) ENGINE = MergeTree() ORDER BY id"
$EDB clickhouse sql "INSERT INTO test_db.events VALUES (1, 'hello', now())"
```

### 6. Query ClickHouse directly

Verify the row is present via the ClickHouse SQL interface.

```bash
$EDB clickhouse sql "SELECT * FROM test_db.events"
```

### 7. Start Presto

Deploy Presto. The `post-workload-start` hook (`update-catalogs`) runs automatically and registers ClickHouse as a catalog.

```bash
$EDB presto start
```

### 8. Query ClickHouse via Presto

Verify Presto can query ClickHouse through its connector.

```bash
$EDB presto sql "SELECT * FROM clickhouse.test_db.events"
```

### 9. Tear down

```bash
$EDB down --auto-approve
```

## Notes

- ClickHouse PVC size is set to 20Gi — sufficient for this smoke test. The kit default is 10Ti.
- Presto's `update-catalogs` hook fires automatically after `presto start` and registers the ClickHouse endpoint as a catalog named `clickhouse`.
- If the ClickHouse catalog isn't visible immediately in Presto, run `$EDB presto update-catalogs` manually.
