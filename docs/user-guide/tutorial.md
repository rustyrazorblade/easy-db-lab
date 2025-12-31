# Tutorial: From Setup to Running Cassandra

This tutorial walks you through creating a Cassandra cluster from scratch, covering initialization, infrastructure provisioning, and Cassandra configuration.

!!! note "Prerequisites"
    Before starting, ensure you've completed the [Setup](../getting-started/setup.md) process by running `easy-db-lab setup-profile`.

## Part 1: Initialize Your Cluster

The `init` command creates local configuration files for your cluster. It does **not** provision AWS resources yet.

```bash
easy-db-lab init my-cluster
```

This creates a 3-node Cassandra cluster by default.

### Init Options

| Option | Description | Default |
|--------|-------------|---------|
| `--db`, `--cassandra`, `-c` | Number of Cassandra instances | 3 |
| `--app`, `--stress`, `-s` | Number of stress/application instances | 0 |
| `--instance`, `-i` | Cassandra instance type | r3.2xlarge |
| `--stress-instance`, `-si` | Stress instance type | c7i.2xlarge |
| `--azs`, `-z` | Availability zones (e.g., `a,b,c`) | all available |
| `--arch`, `-a` | CPU architecture (AMD64, ARM64) | AMD64 |
| `--ebs.type` | EBS volume type (NONE, gp2, gp3, io1, io2) | NONE |
| `--ebs.size` | EBS volume size in GB | 256 |
| `--ebs.iops` | EBS IOPS (gp3 only) | 0 |
| `--ebs.throughput` | EBS throughput (gp3 only) | 0 |
| `--until` | When instances can be deleted | tomorrow |
| `--tag` | Custom tags (key=value, repeatable) | - |
| `--vpc` | Use existing VPC ID | - |
| `--up` | Auto-provision after init | false |
| `--clean` | Remove existing config first | false |

### Examples

**Basic 3-node cluster:**
```bash
easy-db-lab init my-cluster
```

**5-node cluster with 2 stress nodes:**
```bash
easy-db-lab init my-cluster --db 5 --stress 2
```

**Production-like cluster with EBS storage:**
```bash
easy-db-lab init prod-test --db 5 --ebs.type gp3 --ebs.size 500 --ebs.iops 3000
```

**ARM64 cluster for Graviton instances:**
```bash
easy-db-lab init my-cluster --arch ARM64 --instance r7g.2xlarge
```

**Initialize and provision in one step:**
```bash
easy-db-lab init my-cluster --up
```

## Part 2: Launch Infrastructure

Once initialized, provision the AWS infrastructure:

```bash
easy-db-lab up
```

This command creates:

- **S3 Bucket**: Stores cluster state and backups
- **VPC**: With subnets and security groups
- **EC2 Instances**: Cassandra nodes, stress nodes, and a control node
- **K3s Cluster**: Lightweight Kubernetes across all nodes

### What Happens During `up`

1. Creates S3 bucket for cluster state
2. Creates VPC with public subnets in your availability zones
3. Provisions EC2 instances in parallel
4. Waits for SSH availability
5. Configures K3s cluster on all nodes
6. Writes SSH config and environment files

### Up Options

| Option | Description |
|--------|-------------|
| `--no-setup`, `-n` | Skip K3s setup and AxonOps configuration |

### Environment Setup

After `up` completes, source the environment file:

```bash
source env.sh
```

This configures your shell with:

- SSH shortcuts: `ssh db0`, `ssh db1`, `ssh stress0`, etc.
- Cluster aliases: `c0`, `c-all`, `c-status`
- SOCKS proxy configuration

See [Shell Aliases](shell-aliases.md) for all available shortcuts.

## Part 3: Configure Cassandra 5.0

With infrastructure running, configure and start Cassandra.

### Step 1: Select Cassandra Version

```bash
easy-db-lab cassandra use 5.0
```

This command:

- Sets the active Cassandra version on all nodes
- Downloads configuration files to your local directory
- Applies any existing patch configuration

Available versions: 3.0, 3.11, 4.0, 4.1, 5.0, 5.0-HEAD, trunk

### Step 2: Customize Configuration (Optional)

Edit `cassandra.patch.yaml` to customize settings:

```bash
# Example: Change token count
vim cassandra.patch.yaml
```

Common customizations:

| Setting | Description | Default |
|---------|-------------|---------|
| `num_tokens` | Virtual nodes per instance | 4 |
| `concurrent_reads` | Max concurrent read operations | 64 |
| `concurrent_writes` | Max concurrent write operations | 64 |
| `endpoint_snitch` | Network topology snitch | Ec2Snitch |

### Step 3: Apply Configuration

```bash
easy-db-lab cassandra update-config
```

This uploads and applies the patch to all Cassandra nodes.

To apply and restart Cassandra in one command:

```bash
easy-db-lab cassandra update-config --restart
```

### Step 4: Start Cassandra

```bash
easy-db-lab cassandra start
```

### Step 5: Verify Cluster

Check cluster status:

```bash
ssh db0 nodetool status
```

Or use the shell alias (after sourcing `env.sh`):

```bash
c-status
```

You should see all nodes in UN (Up/Normal) state.

## Part 4: Working with Your Cluster

### SSH Access

After sourcing `env.sh`:

```bash
ssh db0          # First Cassandra node
ssh db1          # Second Cassandra node
ssh stress0      # First stress node (if provisioned)
ssh control0     # Control node
```

### Cassandra Management

```bash
# Stop Cassandra on all nodes
easy-db-lab cassandra stop

# Start Cassandra on all nodes
easy-db-lab cassandra start

# Restart Cassandra on all nodes
easy-db-lab cassandra restart
```

### Filter to Specific Hosts

Most commands support the `--hosts` filter:

```bash
# Apply config only to db0 and db1
easy-db-lab cassandra update-config --hosts db0,db1

# Restart only db2
easy-db-lab cassandra restart --hosts db2
```

### Download Configuration Files

To download the current configuration from nodes:

```bash
easy-db-lab cassandra download-config
```

This saves configuration files to a local directory named after the version (e.g., `5.0/`).

## Part 5: Shut Down

When finished, destroy the cluster infrastructure:

```bash
easy-db-lab down
```

!!! warning
    This permanently destroys all EC2 instances, the VPC, and associated resources. S3 buckets are retained for state recovery.

## Quick Reference

| Task | Command |
|------|---------|
| Initialize cluster | `easy-db-lab init <name> [options]` |
| Provision infrastructure | `easy-db-lab up` |
| Initialize and provision | `easy-db-lab init <name> --up` |
| Select Cassandra version | `easy-db-lab cassandra use <version>` |
| Apply configuration | `easy-db-lab cassandra update-config` |
| Start Cassandra | `easy-db-lab cassandra start` |
| Stop Cassandra | `easy-db-lab cassandra stop` |
| Restart Cassandra | `easy-db-lab cassandra restart` |
| Check cluster status | `ssh db0 nodetool status` |
| Download config | `easy-db-lab cassandra download-config` |
| Destroy cluster | `easy-db-lab down` |
| Display hosts | `easy-db-lab hosts` |
| Clean local files | `easy-db-lab clean` |

## Next Steps

- [Kubernetes Access](kubernetes.md) - Access K3s cluster with kubectl and k9s
- [Shell Aliases](shell-aliases.md) - All available CLI shortcuts
- [ClickHouse](clickhouse.md) - Deploy ClickHouse for analytics
- [Spark](spark.md) - Set up Apache Spark via EMR
