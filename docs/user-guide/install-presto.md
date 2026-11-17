# Install Presto

The `install presto` command scaffolds a Presto deployment using the Presto Helm chart. It generates scripts and a `values.yaml` in a local `presto/` directory. Once installed, use `easy-db-lab presto start` and `easy-db-lab presto stop` to manage it.

Presto is stateless — it does not use persistent volumes. Queries run in-memory on the app role's nodes.

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
helm upgrade --install presto prestodb/presto --version 0.4.0 -f values.yaml
# update-catalogs.sh runs automatically after helm install
kubectl wait --for=condition=Ready pods -l app=presto,component=coordinator --timeout=180s
```

No `platform create-pvs` step — Presto is stateless.

Every catalog update (`update-catalogs.sh`, run by `start` and by the `post-workload-start` hook) runs the helm upgrade without waiting, then replaces the coordinator pod and waits for its rollout. The coordinator uses hostPorts 8080 and 9090, which a rolling update could not satisfy on a single app node (the new pod cannot bind the ports while the old pod holds them); chart 0.4.0 deploys it with the `Recreate` strategy, and replacing the pod keeps the flow the same as Trino's whatever the strategy. Presto has no OpenSearch catalog: prestodb ships no `opensearch` connector.

The chart values run the Presto pods with `securityContext.appArmorProfile.type: Unconfined`. Presto's JMX agent attaches the JVM to itself, which sends it a SIGQUIT, and the node's default AppArmor profile refuses every signal a container sends; without the setting the coordinator and workers crash at startup with `IOException: Permission denied` in `JmxAgent9`.

### stop

```bash
helm uninstall presto
```

## Automatic Catalog Management

Presto has built-in support for two catalogs that wire up automatically:

- **Cassandra** — uses the Cassandra connector, pointing at the cluster's Cassandra nodes
- **ClickHouse** — uses the ClickHouse JDBC connector, pointing at the cluster's ClickHouse nodes

When either of those kits starts or stops, Presto detects the change via a `post-workload-start`
/ `post-workload-stop` hook and re-renders its catalog configuration automatically. No restart
of Presto is needed.

```bash
# Start Cassandra — Presto wires up the cassandra catalog automatically
easy-db-lab cassandra start

# Start ClickHouse — Presto wires up the clickhouse catalog automatically
easy-db-lab kit install clickhouse
easy-db-lab clickhouse start
```

You do not need to restart Presto or manually update any config when adding or removing either kit.

## Node Placement

The coordinator and workers run on the app role's nodes: `type=app` on the nodes easy-db-lab builds, or the nodes `init --role-nodes app=...` maps (any node with `app=`). See [Role node selectors](../reference/commands.md#role-node-selectors).

## Connecting

After `easy-db-lab presto start` completes, the Presto coordinator is published on the NodePort Service `presto-nodeport`, port 30882 on any node's private IP. Reach it through the SOCKS5 proxy at `<node-ip>:30882`, or port-forward the Service:

```bash
kubectl port-forward svc/presto-nodeport 8080:8080
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
