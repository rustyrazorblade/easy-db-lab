# Apache Ignite 3

easy-db-lab supports deploying Apache Ignite 3 clusters on Kubernetes for distributed SQL and in-memory computing workloads.

## Overview

Apache Ignite 3 is a distributed database with ACID transactions, distributed SQL, and a pluggable storage engine. It runs as a StatefulSet on K3s with configurable storage profiles ranging from pure in-memory to fully disk-backed persistence.

Metrics are automatically pushed to the cluster's OTel Collector via OTLP and appear in Grafana.

## Quick Start

```bash
# Initialize and start a 3-node cluster
easy-db-lab init my-cluster --db 3 --up

# Install and start Ignite 3 with default settings
easy-db-lab kit install ignite3
easy-db-lab ignite3 start
```

## Configuration Options

| Option | Description | Default |
|--------|-------------|---------|
| `--replicas` | Number of Ignite server nodes | db node count |
| `--storage` | Storage profile (see below) | `aipersist` |
| `--version` | Apache Ignite 3 Docker image version | `3.0.0` |

## Storage Profiles

Ignite 3 supports three storage engines, selectable at start time:

| Profile | Description | Data survives restart? |
|---------|-------------|----------------------|
| `aimem` | Pure in-memory, volatile | No |
| `aipersist` | In-memory with disk persistence (default) | Yes |
| `rocksdb` | Disk-based LSM, suited for large datasets | Yes |

```bash
# Start with pure in-memory storage (fastest, no persistence)
easy-db-lab ignite3 start --storage aimem

# Start with disk-based storage for large datasets
easy-db-lab ignite3 start --storage rocksdb
```

## SQL Queries

Run SQL directly against the cluster using the thin client JDBC driver:

```bash
# Execute a SQL statement
easy-db-lab ignite3 sql "CREATE TABLE t1 (id INT PRIMARY KEY, val VARCHAR)"
easy-db-lab ignite3 sql "INSERT INTO t1 VALUES (1, 'hello')"
easy-db-lab ignite3 sql "SELECT * FROM t1"

# Execute SQL from a file
easy-db-lab ignite3 sql --file query.sql
```

## Lifecycle

```bash
# Start the cluster
easy-db-lab ignite3 start

# Stop (preserves PVCs and data for aipersist/rocksdb profiles)
easy-db-lab ignite3 stop

# Start again — existing data is available immediately
easy-db-lab ignite3 start

# Remove all resources including data
easy-db-lab kit uninstall ignite3
```

## Endpoints

| Name | Port | Protocol |
|------|------|----------|
| REST / Management | 30300 | HTTP |
| Thin Client / JDBC | 30800 | TCP |

## Metrics

Ignite 3 metrics are pushed to the cluster's OTel Collector via OTLP automatically at start time. No additional configuration is needed. Metrics appear in VictoriaMetrics and are accessible from Grafana.
