# Neo4j

The `neo4j` kit runs Neo4j Community Edition as a single-replica StatefulSet on a db node. Its
data directory is on a local persistent volume that `kit install` provisions. Authentication is
off (`NEO4J_AUTH=none`), so clients connect without credentials.

## Quick Start

```bash
easy-db-lab init my-cluster --db 3 --up

easy-db-lab kit install neo4j
easy-db-lab neo4j start
```

## Configuration Options

| Option | Description | Default |
|--------|-------------|---------|
| `--version` | Neo4j Community version: 5.6.0 or later 5.x, or a calendar-versioned release (2025.x, 2026.x, and later). The image is `neo4j:<version>-community` | `2026.09.0` |
| `--storage-size` | Size of the data volume | `10Ti` |

```bash
easy-db-lab kit install neo4j --version 5.26.0
```

`start` refuses unsupported versions before it applies anything:

- 4.x and earlier use different configuration keys from 5.x and the calendar-versioned releases.
- 5.0 through 5.5 are refused too; 5.6.0 is the first supported 5.x release. The kit loads the
  Java agent through `NEO4J_server_jvm_additional`. Images before 5.6.0 replace the stock
  `server.jvm.additional` lines in `neo4j.conf` with that value instead of appending to them,
  so Neo4j would start without its stock JVM flags.

## Endpoints

Neo4j is published on two NodePorts, reachable on any node's private IP:

| Name | Port | Protocol |
|------|------|----------|
| Bolt | 30687 | Bolt (`bolt://`, `neo4j://`) |
| HTTP | 30474 | HTTP (Neo4j Browser and HTTP API) |

`neo4j start`, `neo4j status` and `kit info neo4j` print both endpoints resolved to each db
node's private IP: `<db node private IP>:30687` for Bolt and `http://<db node private IP>:30474`
for HTTP.

The server advertises `<first db node private IP>:30687` as its Bolt address, so a `neo4j://`
client that fetches the routing table gets an address it can dial, not a pod IP.

## Connecting

Over Bolt, with `cypher-shell` or any Neo4j driver:

```bash
cypher-shell -a bolt://<db node private IP>:30687 "RETURN 1"
```

Over HTTP, with the transactional Cypher endpoint:

```bash
curl -s -H 'Content-Type: application/json' \
  -d '{"statements":[{"statement":"RETURN 1"}]}' \
  http://<db node private IP>:30474/db/neo4j/tx/commit
```

Neo4j Browser is at `http://<db node private IP>:30474/`.

## Metrics

The OpenTelemetry Java agent from the base AMI is mounted into the pod and loaded by the Neo4j
JVM. It pushes metrics over OTLP to the collector on the pod's own node every 5 seconds. The
series land in VictoriaMetrics under `job="neo4j"`.

## Lifecycle

```bash
# Stop: deletes the StatefulSet, Services, and pods labelled easydblab/kit=neo4j.
# The data volume is kept.
easy-db-lab neo4j stop

# Start again with the existing data
easy-db-lab neo4j start

# Uninstall: also deletes the PVC and the kit's persistent volumes
easy-db-lab neo4j uninstall
```

Installing Neo4j a second time fails with a collision error and exits non-zero, leaving the
running kit untouched. Pass `--force` to overwrite the scaffold. Running `neo4j start` while Neo4j
is already running also fails with a collision error; run `neo4j stop` first. `stop` returns once
the Neo4j pod is gone, so `start` can follow it straight away.
