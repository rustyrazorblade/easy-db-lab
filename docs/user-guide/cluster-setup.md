# Cluster Setup

This page provides a quick reference for cluster initialization and provisioning. For a complete walkthrough, see the [Tutorial](tutorial.md).

## Quick Start

```bash
# Initialize a 3-node cluster with i4i.xlarge instances and 1 stress node
easy-db-lab init my-cluster --db 3 --instance i4i.xlarge --app 1

# Provision AWS infrastructure
easy-db-lab up

# Set up your shell environment
source env.sh
```

Or combine init and up:

```bash
easy-db-lab init my-cluster --db 3 --instance i4i.xlarge --app 1 --up
```

## Initialize

The `init` command creates local configuration files but does **not** provision AWS resources.

```bash
easy-db-lab init <cluster-name> [options]
```

### Common Options

| Option | Description | Default |
|--------|-------------|---------|
| `--db`, `-c` | Number of Cassandra instances | 3 |
| `--stress`, `-s` | Number of stress instances | 0 |
| `--instance`, `-i` | Instance type | r3.2xlarge |
| `--ebs.type` | EBS volume type (NONE, gp2, gp3) | NONE |
| `--ebs.size` | EBS volume size in GB | 256 |
| `--arch`, `-a` | CPU architecture (AMD64, ARM64) | AMD64 |
| `--up` | Auto-provision after init | false |

For the complete options list, see the [Tutorial](tutorial.md#init-options) or run `easy-db-lab init --help`.

### Storage Requirements

Database instances need a data disk separate from the root volume. This can come from either:

- **Instance store (local NVMe)** — Instance types with a `d` suffix (e.g., `i3.xlarge`, `m5d.xlarge`, `c5d.2xlarge`) include local NVMe storage.
- **EBS volumes** — Attach an EBS volume using `--ebs.type` (e.g., `--ebs.type gp3`).

If the selected instance type has no instance store and `--ebs.type` is not specified, `up` will fail with an error. For example, `c5.2xlarge` has no local storage, so you must specify EBS:

```bash
easy-db-lab init my-cluster --instance c5.2xlarge --ebs.type gp3 --ebs.size 200
```

### bcache: NVMe-Accelerated EBS Storage

The `--bcache` flag configures Linux bcache to use the local NVMe instance store as a transparent cache in front of the EBS volume. This gives you EBS-level persistence with NVMe-level I/O performance for hot data.

**Prerequisites:**

- The database instance type must have local NVMe instance store (e.g., `i3.xlarge`, `i4i.xlarge`, `m5d.2xlarge`).
- `--ebs.type` must be set to a non-NONE value — EBS is the backing device.

```bash
easy-db-lab init my-cluster --instance i4i.xlarge --ebs.type gp3 --ebs.size 500 --bcache
```

**Cache modes:**

| Mode | Description | When to use |
|------|-------------|-------------|
| `writethrough` (default) | Writes go to both NVMe and EBS before acknowledgement. No data loss risk. | Most workloads |
| `writeback` | Writes acknowledged after NVMe cache only, flushed to EBS asynchronously. Higher write throughput. | Maximum write performance, ephemeral test data |

```bash
# High-performance writeback mode
easy-db-lab init my-cluster --instance i4i.xlarge --ebs.type gp3 --ebs.size 500 --bcache --bcache.mode=writeback
```

**Caveats:**

- bcache configuration is **not persistent across reboots**. If a cluster node is rebooted, bcache devices will not be re-registered automatically. Test clusters are typically not rebooted, so this is rarely an issue.
- In `writeback` mode, a small window of unacknowledged writes may be lost if an instance is terminated unexpectedly before dirty cache lines are flushed to EBS.

## Launch

The `up` command provisions all AWS infrastructure:

```bash
easy-db-lab up
```

### What Gets Created

- S3 bucket for cluster state
- VPC with subnets and security groups
- EC2 instances (Cassandra, Stress, Control nodes)
  - Control node: `m5d.xlarge` (NVMe-backed instance; K3s data is stored on NVMe to avoid filling the root volume)
- K3s cluster across all nodes (Cassandra, Stress, Control)

### Options

| Option | Description |
|--------|-------------|
| `--no-setup`, `-n` | Skip K3s and AxonOps setup |

## Shut Down

Destroy all cluster infrastructure:

```bash
easy-db-lab down
```

## Next Steps

After your cluster is running:

1. [Configure Cassandra](tutorial.md#part-3-configure-cassandra-50) - Select version and apply configuration
2. [Shell Aliases](shell-aliases.md) - Set up convenient shortcuts
