# Configuring Cassandra

This page covers Cassandra version management and configuration. For a step-by-step walkthrough, see the [Tutorial](tutorial.md#part-3-configure-cassandra-50).

## Supported Versions

easy-db-lab supports the following Cassandra versions:

| Version | Java | Notes |
|---------|------|-------|
| 3.0 | 8 | Legacy support |
| 3.11 | 8 | Stable release |
| 4.0 | 11 | First 4.x release |
| 4.1 | 11 | Current LTS |
| 5.0 | 11 | **Latest stable (recommended)** |
| 5.0-HEAD | 11 | Nightly build from 5.0 branch |
| trunk | 17 | Development branch |

## Quick Start

```bash
# Select Cassandra 5.0
easy-db-lab cassandra use 5.0

# Generate configuration patch
easy-db-lab cassandra write-config

# Apply configuration and start
easy-db-lab cassandra update-config
easy-db-lab cassandra start

# Verify cluster
ssh db0 nodetool status
```

## Version Management

### Select a Version

```bash
easy-db-lab cassandra use <version>
```

Examples:
```bash
easy-db-lab cassandra use 5.0       # Latest stable
easy-db-lab cassandra use 4.1       # LTS version
easy-db-lab cassandra use trunk     # Development branch
```

This command:

1. Sets the active Cassandra version on all nodes
2. Downloads current configuration files locally
3. Applies any existing `cassandra.patch.yaml`

### Specify Java Version

```bash
easy-db-lab cassandra use 5.0 --java 11
```

### List Available Versions

```bash
easy-db-lab ls
```

## Configuration

### The Patch File

Cassandra configuration uses a **patch file** approach. The `cassandra.patch.yaml` file contains only the settings you want to customize, which are merged with the default `cassandra.yaml`.

Generate a new patch file:

```bash
easy-db-lab cassandra write-config
```

Options:
- `-t`, `--tokens`: Number of tokens (default: 4)

Example patch file:
```yaml
cluster_name: "my-cluster"
num_tokens: 4
seed_provider:
  class_name: "org.apache.cassandra.locator.SimpleSeedProvider"
  parameters:
    seeds: "10.0.1.28"
hints_directory: "/mnt/db1/cassandra/hints"
data_file_directories:
  - "/mnt/db1/cassandra/data"
commitlog_directory: "/mnt/db1/cassandra/commitlog"
concurrent_reads: 64
concurrent_writes: 64
trickle_fsync: true
endpoint_snitch: "Ec2Snitch"
```

```admonish info title="Auto-Injected Fields"
`listen_address` and `rpc_address` are automatically injected with each node's private IP. Do not include them in your patch file.
```

### Apply Configuration

```bash
easy-db-lab cassandra update-config
```

Options:
- `--restart`, `-r`: Restart Cassandra after applying
- `--hosts`: Filter to specific hosts

Apply and restart in one command:
```bash
easy-db-lab cassandra update-config --restart
```

### Download Configuration

Download current configuration files from nodes:

```bash
easy-db-lab cassandra download-config
```

Files are saved to a local directory named after the version (e.g., `5.0/`).

## Starting and Stopping

```bash
# Start on all nodes
easy-db-lab cassandra start

# Stop on all nodes
easy-db-lab cassandra stop

# Restart on all nodes
easy-db-lab cassandra restart

# Target specific hosts
easy-db-lab cassandra start --hosts db0,db1
```

## Cassandra Sidecar

The [Apache Cassandra Sidecar](https://github.com/apache/cassandra-sidecar) is automatically installed and started alongside Cassandra. The sidecar provides:

- REST API for Cassandra operations
- S3 import/restore capabilities
- Streaming data operations
- Metrics collection (Prometheus-compatible)

### Sidecar Access

The sidecar runs on port `9043` on each Cassandra node:

```bash
# Check sidecar health
curl http://<cassandra-node-ip>:9043/api/v1/__health
```

### Sidecar Management

The sidecar is managed via systemd:

```bash
# Check status
ssh db0 sudo systemctl status cassandra-sidecar

# Restart
ssh db0 sudo systemctl restart cassandra-sidecar
```

### Sidecar Configuration

Configuration is located at `/etc/cassandra-sidecar/cassandra-sidecar.yaml` on each node. Key settings:

- Cassandra connection details
- Data directory paths
- Traffic shaping and throttling
- S3 integration settings

## Custom Builds

To use a custom Cassandra build from source:

### Build from Repository

```bash
easy-db-lab cassandra build -n my-build /path/to/cassandra-repo
```

### Use Custom Build

```bash
easy-db-lab cassandra use my-build
```

## Next Steps

- [Tutorial](tutorial.md) - Complete walkthrough
- [Shell Aliases](shell-aliases.md) - Convenient shortcuts for Cassandra management
