# Command Reference

Complete reference for all easy-db-lab commands.

## Global Options

| Option | Description |
|--------|-------------|
| `--help`, `-h` | Shows help information |
| `--vpc-id` | Reconstruct state from existing VPC (requires ClusterId tag) |
| `--force` | Force state reconstruction even if state.json exists |

---

## Setup Commands

### setup-profile

Set up user profile interactively.

```bash
easy-db-lab setup-profile
```

**Aliases:** `setup`

Guides you through:

- Email and AWS credentials collection
- AWS credential validation
- Key pair generation
- IAM role creation
- Packer VPC infrastructure setup
- AMI validation/building

### show-iam-policies

Display IAM policies with your account ID populated.

```bash
easy-db-lab show-iam-policies [policy-name]
```

**Aliases:** `sip`

| Argument | Description |
|----------|-------------|
| `policy-name` | Optional filter: `ec2`, `iam`, or `emr` |

### build-image

Build both base and Cassandra AMI images.

```bash
easy-db-lab build-image [options]
```

| Option | Description | Default |
|--------|-------------|---------|
| `--arch` | CPU architecture (AMD64, ARM64) | AMD64 |
| `--region` | AWS region | (from profile) |

---

## Cluster Lifecycle Commands

### init

Initialize a directory for easy-db-lab.

```bash
easy-db-lab init [cluster-name] [options]
```

| Option | Description | Default |
|--------|-------------|---------|
| `--db`, `--cassandra`, `-c` | Number of Cassandra instances | 3 |
| `--app`, `--stress`, `-s` | Number of stress instances | 0 |
| `--instance`, `-i` | Cassandra instance type | r3.2xlarge |
| `--stress-instance`, `-si` | Stress instance type | c7i.2xlarge |
| `--azs`, `-z` | Availability zones (e.g., `a,b,c`) | all |
| `--arch`, `-a` | CPU architecture (AMD64, ARM64) | AMD64 |
| `--ebs.type` | EBS volume type (NONE, gp2, gp3, io1, io2) | NONE |
| `--ebs.size` | EBS volume size in GB | 256 |
| `--ebs.iops` | EBS IOPS (gp3 only) | 0 |
| `--ebs.throughput` | EBS throughput (gp3 only) | 0 |
| `--ebs.optimized` | Enable EBS optimization | false |
| `--until` | When instances can be deleted | tomorrow |
| `--ami` | Override AMI ID | (auto-detected) |
| `--open` | Unrestricted SSH access | false |
| `--tag` | Custom tags (key=value, repeatable) | - |
| `--vpc` | Use existing VPC ID | - |
| `--up` | Auto-provision after init | false |
| `--clean` | Remove existing config first | false |

### up

Provision AWS infrastructure.

```bash
easy-db-lab up [options]
```

| Option | Description |
|--------|-------------|
| `--no-setup`, `-n` | Skip K3s setup and AxonOps configuration |

Creates: VPC, EC2 instances, K3s cluster. Configures the account S3 bucket for this cluster.

### down

Shut down AWS infrastructure.

```bash
easy-db-lab down [vpc-id] [options]
```

| Argument | Description |
|----------|-------------|
| `vpc-id` | Optional: specific VPC to tear down |

| Option | Description |
|--------|-------------|
| `--all` | Tear down all VPCs tagged with easy_cass_lab |
| `--packer` | Tear down the packer infrastructure VPC |
| `--retention-days N` | Days to retain S3 data after teardown (default: 1) |

### clean

Clean up generated files from the current directory.

```bash
easy-db-lab clean
```

### hosts

List all hosts in the cluster.

```bash
easy-db-lab hosts
```

### status

Display full environment status.

```bash
easy-db-lab status
```

---

## Cassandra Commands

All Cassandra commands are available under the `cassandra` subcommand group.

### cassandra use

Select a Cassandra version.

```bash
easy-db-lab cassandra use <version> [options]
```

| Option | Description |
|--------|-------------|
| `--java` | Java version to use |
| `--hosts` | Filter to specific hosts |

Versions: 3.0, 3.11, 4.0, 4.1, 5.0, 5.0-HEAD, trunk

### cassandra write-config

Generate a new configuration patch file.

```bash
easy-db-lab cassandra write-config [filename] [options]
```

**Aliases:** `wc`

| Option | Description | Default |
|--------|-------------|---------|
| `-t`, `--tokens` | Number of tokens | 4 |

### cassandra update-config

Apply configuration patch to all nodes.

```bash
easy-db-lab cassandra update-config [options]
```

**Aliases:** `uc`

| Option | Description |
|--------|-------------|
| `--restart`, `-r` | Restart Cassandra after applying |
| `--hosts` | Filter to specific hosts |

### cassandra download-config

Download configuration files from nodes.

```bash
easy-db-lab cassandra download-config [options]
```

**Aliases:** `dc`

| Option | Description |
|--------|-------------|
| `--version` | Version to download config for |

### cassandra start

Start Cassandra on all nodes.

```bash
easy-db-lab cassandra start [options]
```

| Option | Description | Default |
|--------|-------------|---------|
| `--sleep` | Time between starts in seconds | 120 |
| `--hosts` | Filter to specific hosts | - |

### cassandra stop

Stop Cassandra on all nodes.

```bash
easy-db-lab cassandra stop [options]
```

| Option | Description |
|--------|-------------|
| `--hosts` | Filter to specific hosts |

### cassandra restart

Restart Cassandra on all nodes.

```bash
easy-db-lab cassandra restart [options]
```

| Option | Description |
|--------|-------------|
| `--hosts` | Filter to specific hosts |

### cassandra list

List available Cassandra versions.

```bash
easy-db-lab cassandra list
```

**Aliases:** `ls`

---

## Cassandra Stress Commands

Stress testing commands under `cassandra stress`.

### cassandra stress start

Start a stress job on Kubernetes.

```bash
easy-db-lab cassandra stress start [options]
```

**Aliases:** `run`

### cassandra stress stop

Stop and delete stress jobs.

```bash
easy-db-lab cassandra stress stop [options]
```

### cassandra stress status

Check status of stress jobs.

```bash
easy-db-lab cassandra stress status
```

### cassandra stress logs

View logs from stress jobs.

```bash
easy-db-lab cassandra stress logs [options]
```

### cassandra stress list

List available workloads.

```bash
easy-db-lab cassandra stress list
```

### cassandra stress fields

List available field generators.

```bash
easy-db-lab cassandra stress fields
```

### cassandra stress info

Show information about a workload.

```bash
easy-db-lab cassandra stress info <workload>
```

---

## Utility Commands

### exec

Execute commands on remote hosts via `systemd-run`. All output is captured to `/var/log/easydblab/tools/` and shipped to VictoriaLogs automatically.

#### exec run

Run a command on remote hosts (foreground by default).

```bash
# Foreground (blocks until complete, shows output)
easy-db-lab exec run -t cassandra -- ls /mnt/db1

# Background (returns immediately, tool keeps running)
easy-db-lab exec run --bg -t cassandra -- inotifywait -m /mnt/db1/data

# Background with custom name
easy-db-lab exec run --bg --name watch-imports -t cassandra -- inotifywait -m /mnt/db1/data
```

| Option | Description |
|--------|-------------|
| `-t, --type` | Server type: cassandra, stress, control (default: cassandra) |
| `--bg` | Run in background (returns immediately) |
| `--name` | Name for the systemd unit (auto-derived if not provided) |
| `--hosts` | Filter to specific hosts |
| `-p` | Execute in parallel across hosts |

#### exec list

List running background tools on remote hosts.

```bash
easy-db-lab exec list
easy-db-lab exec list -t cassandra
```

#### exec stop

Stop a named background tool.

```bash
easy-db-lab exec stop watch-imports
easy-db-lab exec stop watch-imports -t cassandra
```

### ip

Get IP address for a host by alias.

```bash
easy-db-lab ip <alias>
```

### version

Display the easy-db-lab version.

```bash
easy-db-lab version
```

### repl

Start interactive REPL.

```bash
easy-db-lab repl
```

### server

Start the MCP server for Claude Code integration.

```bash
easy-db-lab server
```

See [MCP Server Integration](../integrations/mcp-server.md) for details.

---

## Kubernetes Commands

### k8 apply

Apply observability stack to K8s cluster.

```bash
easy-db-lab k8 apply
```

---

## Dashboard Commands

### dashboards generate

Extract all Grafana dashboard manifests (core and ClickHouse) from JAR resources to the local `k8s/` directory. Useful for rapid dashboard iteration without re-running init.

```bash
easy-db-lab dashboards generate
```

### dashboards upload

Apply all Grafana dashboard manifests and the datasource ConfigMap to the K8s cluster. Extracts dashboards, creates the `grafana-datasources` ConfigMap with runtime configuration, and applies everything.

```bash
easy-db-lab dashboards upload
```

---

## ClickHouse Commands

### clickhouse start

Deploy ClickHouse cluster to K8s.

```bash
easy-db-lab clickhouse start [options]
```

### clickhouse stop

Stop and remove ClickHouse cluster.

```bash
easy-db-lab clickhouse stop
```

### clickhouse status

Check ClickHouse cluster status.

```bash
easy-db-lab clickhouse status
```

---

## Spark Commands

### spark submit

Submit Spark job to EMR cluster.

```bash
easy-db-lab spark submit [options]
```

### spark status

Check status of a Spark job.

```bash
easy-db-lab spark status [options]
```

### spark jobs

List recent Spark jobs on the cluster.

```bash
easy-db-lab spark jobs
```

### spark logs

Download EMR logs from S3.

```bash
easy-db-lab spark logs [options]
```

---

## OpenSearch Commands

### opensearch start

Create an AWS OpenSearch domain.

```bash
easy-db-lab opensearch start [options]
```

### opensearch stop

Delete the OpenSearch domain.

```bash
easy-db-lab opensearch stop
```

### opensearch status

Check OpenSearch domain status.

```bash
easy-db-lab opensearch status
```

---

## AWS Commands

### aws vpcs

List all easy-db-lab VPCs.

```bash
easy-db-lab aws vpcs
```
