# Lab Plan: Trino Federation — Cassandra to TiDB

## Objective

Verify that Trino can query a Cassandra table and INSERT the data into TiDB using federation across both automatically-configured catalogs. Success is a TiDB table populated with rows sourced from Cassandra via a single Trino INSERT INTO ... SELECT statement.

## Cluster Name

trino-test

## Datacenters

single

## Environment

- 1 db node: `i4i.xlarge` (Cassandra + TiDB, local NVMe)
- 1 app node: `i4i.xlarge` (Trino coordinator + worker, local NVMe)
- No EBS volumes

## Steps

### 1. Provision the cluster

```bash
$EDB init trino-test -c 1 -s 1 -i i4i.xlarge -si i4i.xlarge --up
```

### 2. Start Cassandra

```bash
$EDB cassandra start
```

### 3. Install and start TiDB

```bash
$EDB kit install tidb
$EDB tidb start
```

### 4. Install and start Trino

```bash
$EDB kit install trino
$EDB trino start
```

### 5. Create a table in Cassandra and load data

Create the keyspace and table, then insert a few rows:

```bash
$EDB cassandra cql "CREATE KEYSPACE IF NOT EXISTS demo WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};"
$EDB cassandra cql "CREATE TABLE IF NOT EXISTS demo.users (id uuid PRIMARY KEY, name text, email text, created_at timestamp);"
$EDB cassandra cql "INSERT INTO demo.users (id, name, email, created_at) VALUES (uuid(), 'Alice', 'alice@example.com', toTimestamp(now()));"
$EDB cassandra cql "INSERT INTO demo.users (id, name, email, created_at) VALUES (uuid(), 'Bob', 'bob@example.com', toTimestamp(now()));"
$EDB cassandra cql "INSERT INTO demo.users (id, name, email, created_at) VALUES (uuid(), 'Carol', 'carol@example.com', toTimestamp(now()));"
$EDB cassandra cql "SELECT * FROM demo.users;"
```

### 6. Create the destination table in TiDB

```bash
$EDB tidb sql "CREATE DATABASE IF NOT EXISTS demo;"
$EDB tidb sql "CREATE TABLE IF NOT EXISTS demo.users (id VARCHAR(36) PRIMARY KEY, name VARCHAR(255), email VARCHAR(255), created_at DATETIME);"
```

### 7. Verify Trino can see both catalogs

```bash
$EDB trino sql "SHOW CATALOGS;"
$EDB trino sql "SELECT * FROM cassandra.demo.users LIMIT 5;"
```

### 8. Copy data from Cassandra to TiDB via Trino

```bash
$EDB trino sql "INSERT INTO tidb.demo.users SELECT CAST(id AS VARCHAR), name, email, created_at FROM cassandra.demo.users;"
```

### 9. Verify data landed in TiDB

```bash
$EDB tidb sql "SELECT * FROM demo.users;"
```

### 10. Tear down

```bash
$EDB down --auto-approve
```

## Notes

- TiDB and Cassandra share the single db node — if memory is tight, TiDB pods may be slow to start. Give them up to 10 minutes.
- Trino's Cassandra connector maps `uuid` to `VARCHAR` — the destination table uses `VARCHAR(36)` accordingly.
- The `tidb` catalog is added automatically by Trino's `update-catalogs.sh` hook because `tidb` is in `RUNNING_KITS` when Trino starts. No manual catalog configuration needed.
- If `SHOW CATALOGS` doesn't show `tidb`, run `$EDB trino update-catalogs` manually.
