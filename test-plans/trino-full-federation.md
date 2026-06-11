# Lab Plan: Trino Full Federation — Cassandra + TiDB + OpenSearch

## Objective

Verify Trino federates across all three supported data sources simultaneously:
Cassandra (via Cassandra connector), TiDB (via MySQL connector), and AWS OpenSearch
(via OpenSearch connector). Success is Trino returning data from all three catalogs
in a single session with no manual catalog configuration.

## Cluster Name

trino-full

## Datacenters

single

## Environment

- 3 db nodes: `i4i.xlarge` (Cassandra + TiDB, local NVMe)
- 1 app node: `i4i.xlarge` (Trino coordinator + worker, local NVMe)
- AWS OpenSearch: 1 × `t3.small.search`, version 2.11

## Steps

### 1. Provision the cluster with OpenSearch

```bash
$EDB init trino-full -c 3 -s 1 -i i4i.xlarge -si i4i.xlarge \
  --opensearch.enable \
  --opensearch.instance.type t3.small.search \
  --opensearch.version 2.11 \
  --up
```

### 2. Start Cassandra

```bash
$EDB cassandra use 5.0
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

### 5. Verify all three catalogs are present

```bash
$EDB trino sql "SHOW CATALOGS;"
```

Expect: cassandra, tidb, opensearch all present.

### 6. Load data into Cassandra via stress

```bash
$EDB cassandra stress start -- KeyValue -d 1m --rate 5k
```

Wait for the job to complete:

```bash
$EDB cassandra stress status
```

Verify row count:

```bash
$EDB cassandra cql "CONSISTENCY LOCAL_ONE; SELECT COUNT(*) FROM cassandra_easy_stress.keyvalue;"
```

### 7. Create a table and load data into TiDB

```bash
$EDB tidb sql "CREATE DATABASE IF NOT EXISTS demo;"
$EDB tidb sql "CREATE TABLE IF NOT EXISTS demo.products (\`id\` INT PRIMARY KEY, \`name\` VARCHAR(100), \`price\` DECIMAL(10,2));"
$EDB tidb sql "INSERT INTO demo.products VALUES (1, 'Widget', 9.99), (2, 'Gadget', 24.99), (3, 'Doohickey', 4.99);"
```

### 8. Index data into OpenSearch

Get the OpenSearch endpoint from cluster status, then index documents from the control node:

```bash
$EDB status
```

```bash
OS_ENDPOINT="<endpoint from status>"
ssh -F sshConfig control0 "curl -s -X POST 'https://${OS_ENDPOINT}/products/_doc' -H 'Content-Type: application/json' -d '{\"id\": 1, \"name\": \"Widget\", \"category\": \"hardware\", \"in_stock\": true}'"
ssh -F sshConfig control0 "curl -s -X POST 'https://${OS_ENDPOINT}/products/_doc' -H 'Content-Type: application/json' -d '{\"id\": 2, \"name\": \"Gadget\", \"category\": \"electronics\", \"in_stock\": true}'"
ssh -F sshConfig control0 "curl -s -X POST 'https://${OS_ENDPOINT}/products/_doc' -H 'Content-Type: application/json' -d '{\"id\": 3, \"name\": \"Doohickey\", \"category\": \"hardware\", \"in_stock\": false}'"
```

### 9. Query all three catalogs via Trino

```bash
$EDB trino sql "SELECT key, value FROM cassandra.cassandra_easy_stress.keyvalue LIMIT 5;"
$EDB trino sql "SELECT id, name, price FROM tidb.demo.products;"
$EDB trino sql "SELECT name, category, in_stock FROM opensearch.default.products;"
```

### 10. Tear down

```bash
$EDB down --auto-approve
```

## Notes

- Cassandra RF=3 on 3 nodes — LOCAL_ONE for COUNT(*) to avoid quorum issues.
- TiDB and Cassandra share db nodes. Give TiDB up to 10 minutes to start on first boot.
- The opensearch catalog is wired automatically via __OPENSEARCH_ENDPOINT__ at kit install time.
- The tidb catalog is wired automatically because tidb is in RUNNING_KITS when trino starts.
