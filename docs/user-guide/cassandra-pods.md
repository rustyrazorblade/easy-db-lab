# Cassandra Pods

The `cassandra-pods` kit runs Cassandra as a StatefulSet, one pod per db node. It runs the same
way on any environment, so a plan that uses it is not tied to the AMI-based `cassandra` commands,
which start Cassandra as a systemd service over SSH.

By default the pods run Cassandra from a tarball, the same Apache release or cassandra-builds build
the AMI path installs, configured the same way. With `--image` they run any Cassandra image instead.

## Quick Start

```bash
easy-db-lab init my-cluster --db 3 --up

easy-db-lab kit install cassandra-pods --version 5.0
easy-db-lab cassandra-pods start
```

`start` returns when `nodetool status` lists every requested node as UN. If that does not happen
within 15 minutes, it prints the pods' recent events and exits non-zero.

## Configuration Options

| Option | Description | Default |
|--------|-------------|---------|
| `--version` | Cassandra release or release line (`5.0`, `5.0.6`), from its Apache tarball | `5.0` |
| `--build` | A cassandra-builds build (`5.0-HEAD`, `trunk`); overrides `--version` | empty |
| `--java` | JDK to run on: 8, 11, 17 or 21 | the release line's default |
| `--image` | Any Cassandra image instead of a tarball; cannot be used with `--build` | empty |
| `--nodes` | Number of nodes, at most one per db node | the db node count |
| `--memory` | Memory request and limit per node, such as `4Gi` or `2048Mi` | 75% of the smallest db node on nodes easy-db-lab built, else `3Gi` |
| `--cpus` | CPUs per node, a whole number | the smallest db node's CPUs minus 1 |
| `--heap` | JVM heap per node, such as `4G`; must be smaller than `--memory` | half of `--memory` |
| `--dc` | Datacenter name; the rack is always `rack1` | `dc1` |
| `--storage-size` | Size of each node's data volume | `10Ti` on local volumes, `100Gi` on a kit StorageClass ([Kit Storage](platform-substrate.md#kit-storage)) |

The version or build is resolved before anything is created: an unknown version, a build
cassandra-builds does not have, or a JDK the release does not run on fails at once and names it.

On nodes easy-db-lab built, each pod's CPU and memory requests equal its limits, so the pod has the
Guaranteed QoS class and the node to itself. Elsewhere the pod requests `--cpus` with no CPU limit.

## Tarballs, the cache and configuration

Each pod runs the tarball on the `cassandra-runtime` image for its JDK. The first start of a version
downloads the tarball onto the pod's data volume, checks its sha256, and keeps it there; a restart,
or a later start of the same version, uses the kept copy and needs no network. Older versions are
kept too, so switching back is fast.

`cassandra.yaml` is built as on the AMI path: the tarball's own file, then your workspace's
`cassandra.patch.yaml` (edit it to change any setting), then the pod's placement (seeds, directories,
snitch and port) and addresses. The pods' configuration therefore matches the AMI path's except for
those last keys. A change to `cassandra.patch.yaml` reaches the pods at the next `start` or `upgrade`,
which restarts them one at a time; a start with nothing changed restarts nothing.

A catalog build's download link is valid for 7 days. A pod scheduled later onto a node with no kept
copy fails its download with a message naming the expiry; run `upgrade` or `start` again for a new link.

Cassandra 3.x tarballs ship a `cqlsh` that needs Python 2, which the runtime image does not have; use a
`cqlsh` on your machine (see [CQL access](#cql-access)).

## Upgrading a running kit

```bash
easy-db-lab cassandra-pods upgrade --version 5.0.6
easy-db-lab cassandra-pods upgrade --build 5.0-HEAD --java 17
```

`upgrade` takes `--version` or `--build` (exactly one) and optionally `--java`. It restarts the pods one
at a time; each drains before it stops and takes traffic again only once it serves CQL, so a keyspace
with RF=3 stays available at QUORUM. It returns once every node is UN on the new version, and a later
`stop` and `start` keep that version.

Cassandra does not support a major-version downgrade, so `upgrade` refuses one (for example 5.0 to
4.1) before changing anything, naming both versions. `upgrade` does not apply to a kit started with
`--image`.

## Branch builds with `--image`

Any image works, including one built from a branch:

```bash
easy-db-lab kit install cassandra-pods --image ghcr.io/example/cassandra:sha-abc123
```

Before it creates anything, `start` checks that the image's manifest exists in its registry. An
unknown tag fails at once and names the image. The image must support the platform of your db
nodes; images built for amd64 only do not run on arm64 nodes.

A private image (the registry answers 401 or 403 to the anonymous check) prints a warning and
continues, because the pods may pull with credentials the check does not have. Docker Hub and
ghcr.io are checked anonymously; other registries usually answer 401 and are not checked. The
check reads the registry token with `jq`, which must be installed on your machine.

A custom image must be built like the Docker Official `cassandra` image: its entrypoint is
`docker-entrypoint.sh`, `cassandra.yaml` is at `/etc/cassandra/cassandra.yaml` and writable by the
container user with an uncommented `native_transport_port:` line, and data lives in
`/var/lib/cassandra`. A different layout fails when the pod starts, not in the registry check; a
`cassandra.yaml` with no `native_transport_port:` line makes the container exit at once with an
error naming the file. An image's configuration comes from the image, not from `cassandra.patch.yaml`.

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

`stop` deletes the StatefulSet, the Services and the ConfigMaps, and keeps the data volumes. A
later `start` reattaches them, so the data is still there. `kit uninstall cassandra-pods` is destructive:
it deletes the volumes and the data on them.

The args are fixed at `kit install`; `stop` then `start` reuses them, and `upgrade` changes the
version. Cassandra refuses to boot on existing data under a different datacenter name, so to change
`--dc`, or to move to an older major version, run `kit uninstall` first and accept that the data goes
with it.

## Metrics and logs

Metrics and logs arrive with `job="cassandra"`, the cluster label, the node as `host_name` and the
build (`5.0.6`, `5.0-HEAD`, or the image tag) as `cassandra_build`, so the Cassandra dashboards show
these pods without any change.

## Running next to the AMI Cassandra

Both would report under `job="cassandra"`, so `start` refuses to run while the AMI-based Cassandra
is running on a db node. Stop it first with `easy-db-lab cassandra stop`. The check needs SSH to the
nodes; on an environment without it (a local or bring-your-own Kubernetes cluster, which has no AMI
Cassandra) `start` skips the check and prints one line saying so.

## Local clusters

The memory request equals the limit, so N db pods reserve N × `--memory` (3Gi by default there). On
a 3-node K3s-in-docker cluster, run fewer nodes if that does not fit. A 2Gi limit was OOM-killed in
testing, so do not go below about 3Gi without testing it.
