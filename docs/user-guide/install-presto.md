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
easy-db-lab install presto
easy-db-lab presto start
```

## Flags

| Flag | Default | Description |
|---|---|---|
| `--workers` | app node count | Number of Presto worker pods |

## What Gets Generated

```
presto/
├── README.md            # Usage instructions for this cluster
├── values.yaml          # Helm values for the Presto chart
└── bin/
    ├── start.sh         # Deploy sequence
    └── stop.sh          # Teardown sequence
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
kubectl wait --for=condition=Ready pods -l app=presto,component=coordinator --timeout=180s
```

No `platform create-pvs` step — Presto is stateless.

### stop

```bash
helm uninstall presto
```

## Node Placement

Presto workers are scheduled on `type=app` nodes using `nodeSelector: type: app`. The coordinator runs on the control plane or app nodes depending on cluster size.

## Connecting

After `easy-db-lab presto start` completes, the Presto coordinator is accessible within the cluster. Use `kubectl port-forward` or the SOCKS5 proxy to connect from your workstation:

```bash
kubectl port-forward svc/presto 8080:8080
```

Then connect with any Presto-compatible client at `localhost:8080`.
