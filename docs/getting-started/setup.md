# Setup

This guide walks you through the initial setup of easy-db-lab, including AWS credentials configuration, IAM policies, and AMI creation.

## Overview

The `setup-profile` command handles all initial configuration interactively. It will:

1. Collect your email and AWS credentials
2. Validate your AWS access
3. Create necessary AWS resources (key pair, IAM roles, Packer VPC)
4. Build or validate the required AMI

## Prerequisites

Before running setup:

- **AWS Account**: An AWS account with appropriate permissions (see [IAM Policies](#getting-iam-policies) below)
- **Java 21+**: Required to run easy-db-lab
- **Docker**: Required only if building custom AMIs

## Step 1: Run Setup Profile

Run the interactive setup:

```bash
easy-db-lab setup-profile
```

Or use the shorter alias:

```bash
easy-db-lab setup
```

The setup wizard will prompt you for:

| Prompt | Description | Default |
|--------|-------------|---------|
| Email | Used to tag AWS resources for ownership | (required) |
| AWS Region | Region for your clusters | us-west-2 |
| AWS Access Key | Your AWS access key ID | (required) |
| AWS Secret Key | Your AWS secret access key | (required) |
| AxonOps Org | Optional: AxonOps organization name | (skip) |
| AxonOps Key | Optional: AxonOps API key | (skip) |
| AWS Profile | Optional: Named AWS profile | (skip) |

### What Gets Created

During setup, the following AWS resources are created:

- **EC2 Key Pair**: For SSH access to instances
- **IAM Role**: For instance permissions (`easy-db-lab-instance-role`)
- **Packer VPC**: Infrastructure for building AMIs
- **AMI** (if needed): Takes 10-15 minutes to build

### Configuration Location

Your profile is saved to:

```
~/.easy-db-lab/profiles/default/settings.yaml
```

```admonish tip
Use a different profile by setting `EASY_CASS_LAB_PROFILE` environment variable before running setup.
```

## Step 2: Getting IAM Policies

If you need to request permissions from your AWS administrator, use the `show-iam-policies` command to display the required policies with your account ID populated:

```bash
easy-db-lab show-iam-policies
```

This displays three policies:

| Policy | Purpose |
|--------|---------|
| EC2 | Create/manage EC2 instances, VPCs, security groups |
| IAM | Create instance roles and profiles |
| EMR | Create Spark clusters (optional) |

### Filter by Policy Name

To show a specific policy:

```bash
easy-db-lab show-iam-policies ec2    # Show EC2 policy only
easy-db-lab show-iam-policies iam    # Show IAM policy only
easy-db-lab show-iam-policies emr    # Show EMR policy only
```

### Recommended IAM Setup

For teams with multiple users, we recommend creating managed policies attached to an IAM group:

1. **Create an IAM group** (e.g., "EasyDBLabUsers")
2. **Create three managed policies** from the JSON output
3. **Attach all policies** to the group
4. **Add users** to the group

```admonish warning
Inline policies have a 5,120 byte limit which may not fit all three policies. Use managed policies instead.
```

## Step 3: Build Custom AMI (Optional)

If setup couldn't find a valid AMI for your architecture, or if you want to customize the base image, build one manually:

```bash
easy-db-lab build-image
```

### Build Options

| Option | Description | Default |
|--------|-------------|---------|
| `--arch` | CPU architecture (AMD64 or ARM64) | AMD64 |
| `--region` | AWS region for the AMI | (from profile) |

### Examples

```bash
# Build AMD64 AMI (default)
easy-db-lab build-image

# Build ARM64 AMI for Graviton instances
easy-db-lab build-image --arch ARM64

# Build in specific region
easy-db-lab build-image --region eu-west-1
```

```admonish note
Building an AMI takes approximately 10-15 minutes. Docker must be installed and running.
```

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `EASY_DB_LAB_USER_DIR` | Override configuration directory | `~/.easy-db-lab` |
| `EASY_CASS_LAB_PROFILE` | Use a named profile | `default` |
| `EASY_CASS_LAB_INSTANCE_TYPE` | Default instance type for `init` | `r3.2xlarge` |
| `EASY_CASS_LAB_STRESS_INSTANCE_TYPE` | Default stress instance type | `c7i.2xlarge` |
| `EASY_CASS_LAB_AMI` | Override AMI ID | (auto-detected) |

## Verify Installation

After setup completes, verify by running:

```bash
easy-db-lab
```

You should see the help output with available commands.

## Next Steps

Once setup is complete, follow the [Tutorial](../user-guide/tutorial.md) to create your first cluster.
