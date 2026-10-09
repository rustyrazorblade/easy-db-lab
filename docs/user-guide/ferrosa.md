# FerrosaDB

The `ferrosa` kit runs [FerrosaDB](https://github.com/ferrosadb/ferrosa), a Rust reimplementation of Cassandra, on the db nodes. It runs one FerrosaDB pod on each db node, and the pods form one ring. FerrosaDB serves CQL v3/v4, Bolt/Cypher, a graph HTTP API, SPARQL, Arrow Flight and a Postgres wire stub. Authentication is off, so clients connect with no credentials.

Only a ring of 3 or more db nodes is tested. The kit also starts on 1 or 2 db nodes.

## Quick Start

```bash
easy-db-lab init my-cluster --db 3 --up

easy-db-lab kit install ferrosa
easy-db-lab ferrosa start
```

`install` creates one local persistent volume on each db node's data disk (`/mnt/db1/ferrosa`). `start` creates, for each db node `i`, a Deployment `ferrosa-<i>` with 1 replica, a claim `ferrosa-data-<i>` bound to that node's volume, and a Service `ferrosa-<i>`. It returns when every pod answers ready on `/readyz`.

## Start Options

Every option belongs to `start`, so you can change it between runs without reinstalling. `easy-db-lab kit info ferrosa` lists them.

| Option | Description | Default |
|--------|-------------|---------|
| `--version` | Tag of `ghcr.io/ferrosadb/ferrosa` to run (`nightly`, `latest`, `vYYYY.MM.DD.HHMM`) | `nightly` |
| `--image` | A full image reference to run instead, such as a custom build in your ECR | none |
| `--storage` | `local` keeps the data on the node's data disk; `s3` keeps it in the cluster data bucket under `ferrosa/` | `local` |
| `--log-level` | FerrosaDB log level (`RUST_LOG`) | `info` |
| `--heap-profile` | Turn on jemalloc heap profiling; needs a FerrosaDB profiling build | off |
| `--heap-sample` | jemalloc `lg_prof_sample` with `--heap-profile` | `19` |
| `--env KEY=VALUE` | Pass any other setting to every FerrosaDB container; repeat it for more | none |

`start` checks every option before it creates anything. It fails when:

- `--image` and `--version` are both given. Use one of them.
- `--image` is not an image reference, or `--version` is not an image tag.
- `--storage` is not `local` or `s3`.
- `--heap-sample` is given without `--heap-profile`.
- An `--env` value is not `KEY=VALUE`.
- An `--env` key is one the kit sets for each pod: `FERROSA_HOST_ID`, `FERROSA_SEED`, `FERROSA_INTERNODE_BROADCAST`, `FERROSA_CQL_BROADCAST`, `FERROSA_FLIGHT_BROADCAST`, `FERROSA_CLUSTER_NAME` or `FERROSA_EXPECTED_CLUSTER_SIZE`.

An `--env` setting wins over a named option. For example, `--log-level=debug --env RUST_LOG=trace` runs with `RUST_LOG=trace`. If you give the same `--env` key more than once, the last value wins.

```bash
easy-db-lab ferrosa start --version=v2026.10.01.1200 --log-level=debug \
  --env FERROSA_<SETTING>=<value> --env FERROSA_<OTHER_SETTING>=<value>
```

### Storage

With `--storage=local` (the default), FerrosaDB keeps its data in `/var/lib/ferrosa`, which is the pod's volume on the node's data disk. Nothing goes to S3.

With `--storage=s3`, FerrosaDB keeps its data in the cluster data bucket under `ferrosa/`, and the local volume holds only its cache. It uses the node's instance profile; no access key is set. If FerrosaDB cannot reach the bucket, it exits with "S3 access failed and FERROSA_S3_REQUIRED is set" instead of falling back to local storage, and `start` fails and prints that message. An unreachable S3 endpoint can take about 3 minutes to fail.

Switching `--storage` on an existing volume is not supported, and the kit does not check for it. To switch, run `ferrosa uninstall` and install the kit again. Nothing in the kit deletes the objects under `ferrosa/`.

## Endpoints

Every client listener of the first pod (`ferrosa-0`) is published on a NodePort of every node. `ferrosa start`, `ferrosa status` and `kit info ferrosa` list each one at every db node's private IP.

| Name | NodePort | Pod port | Type |
|------|----------|----------|------|
| CQL | 30942 | 9042 | `cql` |
| Console, `/metrics` and `/readyz` | 30909 | 9090 | `http` |
| Bolt | 30787 | 7687 | `native` |
| Graph HTTP API | 30747 | 7474 | `http` |
| SPARQL | 30880 | 8080 | `http` |
| Postgres wire stub | 30532 | 5432 | `postgresql` |
| Arrow Flight | 30815 | 8815 | `native` |

Internode traffic (port 17000) has no NodePort. Inside the cluster, pod `i` is `ferrosa-<i>.default.svc.cluster.local`, with CQL on 9042.

## Connecting with CQL

Connect any CQL client to the CQL NodePort on a db node's private IP:

```bash
cqlsh <db node private IP> 30942
```

```sql
CREATE KEYSPACE demo WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 3};
CREATE TABLE demo.kv (k text PRIMARY KEY, v text);
INSERT INTO demo.kv (k, v) VALUES ('a', '1');
SELECT * FROM demo.kv;
```

A driver finds the other pods through `system.peers`.

## Running a custom build from ECR

Every node runs the kubelet ECR credential provider from the base AMI, so a node pulls images from your account's ECR with its instance role. There is no pull secret and no login step, and a pod that restarts days later still pulls.

```bash
REGISTRY=<account>.dkr.ecr.<region>.amazonaws.com
aws ecr create-repository --repository-name ferrosa
aws ecr get-login-password | docker login --username AWS --password-stdin "$REGISTRY"
docker tag my-ferrosa-build:latest "$REGISTRY/ferrosa:my-build"
docker push "$REGISTRY/ferrosa:my-build"

easy-db-lab ferrosa start --image="$REGISTRY/ferrosa:my-build"
```

Every container runs with `imagePullPolicy: Always`, so each `start` pulls the tag again. You can push a new build under the same tag and run `ferrosa stop` and `ferrosa start`.

A custom image has two requirements:

- It needs `sh`. An init container runs the image as root to create and chown the data directory.
- It must not bake `/etc/ferrosa/ferrosa.toml`. FerrosaDB prefers its TOML file over environment variables, so a baked file overrides the kit's settings and `--env`.

If an image or a tag does not exist, `start` fails at once and names the pod and the full image reference. If a container crashes, `start` fails and prints the end of its previous log.

## Heap profiling

`--heap-profile` sets `MALLOC_CONF` so jemalloc samples allocations and writes `.heap` files to `/var/lib/ferrosa/heap-profiles/` on the node's volume. `--heap-sample` sets the sample rate (`lg_prof_sample`; a sample about every 2^n bytes).

The published images do not have jemalloc profiling. The profiling build is published as a per-architecture OCI archive. Load it, tag it, push it to your ECR as above, and run it with `--image`. On an image without profiling, `start` fails with "--heap-profile needs a FerrosaDB profiling build" and names the image.

```bash
easy-db-lab ferrosa start --image="$REGISTRY/ferrosa:profiling" --heap-profile --heap-sample=17
```

The `.heap` files stay on the db nodes' data disks, which `down` destroys. Copy them off first, from the cluster workspace:

```bash
for host in db0 db1 db2; do
  ssh -F sshConfig "$host" 'sudo tar czf - -C /mnt/db1/ferrosa heap-profiles' > "$host-heap-profiles.tgz"
done
```

## Load with cassandra-easy-stress

Run cassandra-easy-stress against the first pod's Service name. The stress pod uses the cluster's DNS, so it resolves the name. Put `--` before the cassandra-easy-stress arguments, so that easy-db-lab passes `-d` and `--host` through to it:

```bash
easy-db-lab cassandra stress start -- KeyValue -d 10m --host ferrosa-0.default.svc.cluster.local
```

## Metrics, logs, profiles and the dashboard

- **Metrics:** the collector on each pod's own node scrapes FerrosaDB's `/metrics` on port 9090, so there is one series for each pod, with `job="ferrosa"`. `start` registers the scrape as the ConfigMap `easydblab-metrics-ferrosa-ferrosa`, and `stop` removes it.
- **Logs:** FerrosaDB's stdout reaches Loki through the container log collection. Select the FerrosaDB pods with `{k8s_pod_name=~"ferrosa-[0-9]+-.*"}`. Each pod is named `ferrosa-<i>-<hash>-<hash>`, and the ordinal keeps out other pods whose names start with `ferrosa-`, such as a stress job's.
- **Profiles:** the eBPF profiler sends CPU profiles of the FerrosaDB pods to Pyroscope. Function names resolve fully only on a profiling build.
- **Dashboard:** `start` installs the FerrosaDB dashboard into the Grafana folder `ferrosa`.

FerrosaDB exports no traces.

## Running alongside another database

The FerrosaDB pods run on the db nodes. If Cassandra or another kit also runs on those nodes, they contend for the same CPU, memory and disk, and both sets of results show it.

## Lifecycle

```bash
# Deletes the Deployments, ReplicaSets, pods, Services and ConfigMaps labelled easydblab/kit=ferrosa.
# The claims, the volumes and their data (including .heap files), and the objects under ferrosa/
# in the data bucket stay, so start works again straight away.
easy-db-lab ferrosa stop

# Also deletes the claims and the volumes, and the data directory on each db node.
easy-db-lab ferrosa uninstall
```

Running `ferrosa start` while FerrosaDB runs fails with a collision error; run `ferrosa stop` first.
