---
name: provisioning
description: Create a cluster, launch its AWS infrastructure, and tear it down
---
# Provisioning a cluster

This topic explains how to bring up a database lab cluster in AWS and how to take it down again.
A cluster is ephemeral: you create it, use it, and destroy it.

## Before you start

Run the profile setup once, so the tool has your AWS credentials, an SSH key, and an AMI:

```bash
easy-db-lab profile setup
```

## Step 1: Create a workspace directory

Every cluster has its own directory. Run all commands for that cluster from inside it. The tool
writes `state.json`, `env.sh`, `sshConfig`, and `kubeconfig` into the current directory, so the
wrong directory targets the wrong cluster.

```bash
mkdir my-cluster && cd my-cluster
```

## Step 2: Initialize the cluster

`init` writes local configuration only. It does not launch any AWS resources yet.

```bash
easy-db-lab init my-cluster
```

This describes a three-node database cluster by default. Size the node groups with the
namespaced options:

```bash
easy-db-lab init my-cluster --db.count 5 --app.count 2
```

Database nodes need a data disk. Use an instance type with local NVMe (a `d` suffix, such as
`i4i.xlarge`), or attach an EBS volume with `--ebs.type`. If neither is present, the next step
fails.

## Step 3: Launch the infrastructure

```bash
easy-db-lab up
```

This creates the VPC, the EC2 instances, and a K3s Kubernetes cluster, then configures the
observability stack. It fails fast: if any step fails, the command stops with a non-zero exit
code rather than leave a half-built cluster. Instances that already launched keep running; fix
the cause and run `up` again.

To do both steps at once, add `--up` to `init`:

```bash
easy-db-lab init my-cluster --up
```

## Step 4: Confirm the cluster is healthy

```bash
easy-db-lab status
easy-db-lab hosts
```

`status` reports the EC2 instances, the network, observability URLs, and the database version.
`hosts` lists the node aliases you use with `--hosts` and `ip`.

## Step 5: Tear the cluster down

When you are done, destroy everything so it stops costing money:

```bash
easy-db-lab down --auto-approve
```

Run `down` from the same workspace directory you provisioned from.

## Related topics

- `configs` — edit and apply the database configuration.
- `kits` — install a workload such as ClickHouse, Presto, or TiDB.
- `stress-testing` — drive load against the cluster.
