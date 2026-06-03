# Install ClickHouse (Kubernetes)

The `install clickhouse` command scaffolds a Helm-based ClickHouse deployment using the [Altinity Kubernetes Operator](https://github.com/Altinity/clickhouse-operator). It generates scripts and a `ClickHouseInstallation` CR in a local `clickhouse/` directory. Once installed, use `easy-db-lab clickhouse start` and `easy-db-lab clickhouse stop` to manage it.

> **Note**: This is separate from the `clickhouse` command group (`clickhouse start`, `clickhouse stop`, etc.), which manages ClickHouse directly on EC2 nodes. `install clickhouse` deploys ClickHouse as a Kubernetes workload via the Altinity operator.

## Prerequisites

- Cluster is up (`easy-db-lab up`)
- `kubectl` and `helm` are available in your PATH (or run via the easy-db-lab container)
- Environment is sourced: `source env.sh`

## Quick Start

```bash
easy-db-lab kit install clickhouse --replicas 3 --size 100Gi
easy-db-lab clickhouse start
```

## Flags

| Flag | Default | Description |
|---|---|---|
| `--replicas` | db node count | Number of ClickHouse replicas |
| `--size` | required | Storage per node (e.g., `100Gi`) |
| `--s3-cache` | `0Gi` | S3 disk cache size |
| `--s3-cache-on-write` | `false` | Enable write-through S3 caching |
| `--s3-tier-move-factor` | `0.1` | Move factor for S3 tiering |
| `--force` | `false` | Overwrite existing `ClickHouseInstallation` CR |

## What Gets Generated

```
clickhouse/
├── README.md                     # Usage instructions for this cluster
├── clickhouseinstallation.yaml   # ClickHouseInstallation custom resource
└── bin/
    ├── start.sh                  # Deploy sequence
    └── stop.sh                   # Teardown sequence
```

## Managing ClickHouse

After installation, use the CLI to start and stop:

```bash
# Deploy ClickHouse
easy-db-lab clickhouse start

# Tear down ClickHouse
easy-db-lab clickhouse stop
```

### start

The `start` sequence:

1. **Create PVs**: `easy-db-lab platform create-pvs --workload clickhouse --size <size>`
2. **Install operator**: `helm upgrade --install clickhouse-operator altinity/altinity-clickhouse-operator`
3. **Apply CR**: `kubectl apply -f clickhouseinstallation.yaml`
4. **Wait**: `kubectl wait --for=condition=Ready pods -l clickhouse.altinity.com/chi=clickhouse --timeout=300s`

Grafana dashboards in `clickhouse/dashboards/` are installed automatically after a successful start.

### stop

```bash
kubectl delete -f clickhouseinstallation.yaml --ignore-not-found
helm uninstall clickhouse-operator --ignore-not-found
```

`stop` does **not** delete disk data. To wipe data, use `easy-db-lab cleanup clickhouse` after stopping.

## Node Placement

ClickHouse pods are scheduled on `type=db` nodes using `nodeSelector: type: db`. Each replica gets its own PV on a distinct db node, bound via `easydblab.com/node-ordinal`.

## Collision Detection

If a `ClickHouseInstallation` CR already exists in the cluster, `install clickhouse` will warn and exit. Use `--force` to overwrite:

```bash
easy-db-lab kit install clickhouse --replicas 3 --size 100Gi --force
```

## Cleaning Up

To remove ClickHouse and free disk space:

```bash
easy-db-lab clickhouse stop
easy-db-lab cleanup clickhouse
```

`cleanup clickhouse` runs a Kubernetes Job on each db node to delete `/mnt/db1/clickhouse`.
