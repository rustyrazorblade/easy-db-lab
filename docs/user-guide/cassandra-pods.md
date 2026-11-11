# Cassandra Pods

The `cassandra-pods` kit runs Cassandra as a StatefulSet, one pod per db node. It runs the same
way on any environment, so a plan that uses it is not tied to the AMI-based `cassandra` commands,
which start Cassandra as a systemd service over SSH.

## Quick Start

```bash
easy-db-lab init my-cluster --db 3 --up

easy-db-lab kit install cassandra-pods
easy-db-lab cassandra-pods start
```

`start` returns when `nodetool status` lists every requested node as UN. If that does not happen
within 15 minutes, it prints the pods' recent events and exits non-zero.

## Configuration Options

| Option | Description | Default |
|--------|-------------|---------|
| `--version` | Cassandra version; the image is `cassandra:<version>` | `5.0` |
| `--image` | Any image reference; overrides `--version` | empty |
| `--nodes` | Number of nodes, at most one per db node | the db node count |
| `--memory` | Memory request and limit per node, such as `4Gi` or `2048Mi` | `4Gi` |
| `--heap` | JVM heap per node, such as `4G`; must be smaller than `--memory` | half of `--memory` |
| `--dc` | Datacenter name; the rack is always `rack1` | `dc1` |
| `--storage-size` | Size of each node's data volume | `10Ti` |

## Branch builds with `--image`

Any image works, including one built from a branch:

```bash
easy-db-lab kit install cassandra-pods --image ghcr.io/example/cassandra:sha-abc123
```

Before it creates anything, `start` checks that the image's manifest exists in its registry. An
unknown version or tag fails at once and names the image. The image must support the platform of
your db nodes; images built for amd64 only do not run on arm64 nodes.

## CQL access

Every pod advertises its node's IP and listens on port 30942, and a NodePort Service maps 30942 to
the pod on that node. Connect to any db node IP:

```bash
cqlsh <db-node-ip> 30942
```

A driver connected through one node learns the other nodes' addresses from `system.peers_v2`, and
each of them is reachable the same way.

## Persistence

`stop` deletes the StatefulSet, the Services and the ConfigMap, and keeps the data volumes. A
later `start` reattaches them, so the data is still there. `kit uninstall cassandra-pods` deletes
the volumes.

## Metrics and logs

Metrics and logs arrive with `job="cassandra"`, the cluster label, the node as `host_name` and the
image tag as `cassandra_build`, so the Cassandra dashboards show these pods without any change.

## Running next to the AMI Cassandra

Both would report under `job="cassandra"`, so `start` refuses to run while the AMI-based Cassandra
is running on a db node. Stop it first with `easy-db-lab cassandra stop`.
