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
| `--version` | Neo4j Community version. 5.x and 2025.x only; the image is `neo4j:<version>-community` | `2025.05.0` |
| `--storage-size` | Size of the data volume | `10Ti` |

```bash
easy-db-lab kit install neo4j --version 5.26.0
```

`start` refuses a version outside 5.x and 2025.x before it applies anything. Older and newer
lines use different configuration keys.

## Endpoints

Neo4j is published on two NodePorts, reachable on any node's private IP:

| Name | Port | Protocol |
|------|------|----------|
| Bolt | 30687 | Bolt (`bolt://`, `neo4j://`) |
| HTTP | 30474 | HTTP (Neo4j Browser and HTTP API) |

`neo4j status` prints both endpoints resolved to each db node's private IP.

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

Installing Neo4j a second time into the same cluster fails with a collision error and leaves the
running kit untouched.
