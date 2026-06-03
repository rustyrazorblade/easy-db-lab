# Install Presto

The `install presto` command scaffolds a Presto deployment using the Presto Helm chart. It generates scripts and a `values.yaml` in a local `presto/` directory. Once installed, use `easy-db-lab presto start` and `easy-db-lab presto stop` to manage it.

Presto is stateless — it does not use persistent volumes. Queries run in-memory on app (`type=app`) nodes.

## Prerequisites

- Cluster is up (`easy-db-lab up`)
- App nodes are provisioned (at least one `ServerType.Stress` node)
- `kubectl` and `helm` are available in your PATH (or run via the easy-db-lab container)
- Environment is sourced: `source env.sh`

## Quick Start

```bash
easy-db-lab kit install presto
easy-db-lab presto start
```

## Flags

| Flag | Default | Description |
|---|---|---|
| `--workers` | app node count | Number of Presto worker pods |

## What Gets Generated

```
presto/
├── README.md                    # Usage instructions for this cluster
├── values.yaml                  # Helm values for the Presto chart
├── catalogs/
│   ├── cassandra.properties     # Cassandra connector config
│   └── clickhouse.properties    # ClickHouse JDBC connector config
└── bin/
    ├── start.sh                 # Deploy sequence
    ├── stop.sh                  # Teardown sequence
    └── update-catalogs.sh       # Re-renders Presto catalog config from running kits
```

## Managing Presto

After installation, use the CLI to start and stop:

```bash
# Deploy Presto
easy-db-lab presto start

# Tear down Presto
easy-db-lab presto stop
```

### start

```bash
helm upgrade --install presto prestodb/presto -f values.yaml
# update-catalogs.sh runs automatically after helm install
kubectl wait --for=condition=Ready pods -l app=presto,component=coordinator --timeout=180s
```

No `platform create-pvs` step — Presto is stateless.

### stop

```bash
helm uninstall presto
```

## Automatic Catalog Management

Presto maintains its own catalog configuration. When any other kit starts or stops, Presto
automatically re-renders its catalog config to reflect the current set of running kits.

For example, starting Cassandra:

```bash
easy-db-lab cassandra start
# Presto's post-workload-start hook fires automatically
# update-catalogs.sh re-renders catalogs based on RUNNING_KITS
# helm upgrade applies the updated catalog config
```

You do not need to restart Presto or manually update any config when adding or removing kits.

## Node Placement

Presto workers are scheduled on `type=app` nodes using `nodeSelector: type: app`. The coordinator runs on the control plane or app nodes depending on cluster size.

## Connecting

After `easy-db-lab presto start` completes, the Presto coordinator is accessible within the cluster. Use `kubectl port-forward` or the SOCKS5 proxy to connect from your workstation:

```bash
kubectl port-forward svc/presto 8080:8080
```

Then connect with any Presto-compatible client at `localhost:8080`.

## Adding Presto Catalogs for Custom Kits

If you install a custom kit via `--from` and want Presto to connect to it, drop a
`presto-catalog.properties` file in the kit's installed directory:

```
my-kit/
├── bin/
│   ├── start.sh
│   └── stop.sh
└── presto-catalog.properties    # Presto picks this up automatically
```

The file follows the standard [Presto connector properties](https://prestodb.io/docs/current/connector.html) format:

```properties
connector.name=jdbc
connection-url=jdbc:postgresql://localhost:5432/mydb
connection-user=presto
connection-password=
```

`update-catalogs.sh` scans all sibling kit directories for this file. When found, the catalog
is added to Presto's configuration under a name matching the kit directory name. No restart of
Presto is required — the script runs `helm upgrade` with the merged catalog values.
