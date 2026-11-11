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

A private image (the registry answers 401 or 403 to the anonymous check) prints a warning and
continues, because the pods may pull with credentials the check does not have. Docker Hub and
ghcr.io are checked anonymously; other registries usually answer 401 and are not checked.

A custom image must be built like the Docker Official `cassandra` image: its entrypoint is
`docker-entrypoint.sh`, `cassandra.yaml` is at `/etc/cassandra/cassandra.yaml` and writable by the
container user, and data lives in `/var/lib/cassandra`. A different layout fails when the pod
starts, not in the registry check.

## CQL access

Every pod advertises its node's IP and listens on port 30942, and a NodePort Service maps 30942 to
the pod on that node. Connect to any db node IP:

```bash
cqlsh <db-node-ip> 30942
```

A driver connected through one node learns the other nodes' addresses from `system.peers_v2`, and
each of them is reachable the same way. Port 30942 is inside Kubernetes' default NodePort range
(30000-32767). The Service keeps traffic on the node (`externalTrafficPolicy: Local`), so a db
node that is not running a ready pod refuses connections instead of forwarding them.

## Persistence

`stop` deletes the StatefulSet, the Services and the ConfigMap, and keeps the data volumes. A
later `start` reattaches them, so the data is still there. `kit uninstall cassandra-pods` is destructive:
it deletes the volumes and the data on them.

The args are fixed at `kit install`; `stop` then `start` reuses them. Cassandra refuses to boot
on existing data under a different datacenter name, and does not support downgrading a major
version, so to change `--dc` or move to an older `--version`, run `kit uninstall` first and accept
that the data goes with it.

## Metrics and logs

Metrics and logs arrive with `job="cassandra"`, the cluster label, the node as `host_name` and the
image tag as `cassandra_build`, so the Cassandra dashboards show these pods without any change.

## Running next to the AMI Cassandra

Both would report under `job="cassandra"`, so `start` refuses to run while the AMI-based Cassandra
is running on a db node. Stop it first with `easy-db-lab cassandra stop`.

## Local clusters

The memory request equals the limit, so N db pods reserve N × `--memory`. On a 3-node K3s-in-docker
cluster, pass `--memory 3Gi` or less (the heap is half the limit, or set it with `--heap`), or run
fewer nodes. A 2Gi limit was OOM-killed in testing, so do not go below about 3Gi without testing it.
