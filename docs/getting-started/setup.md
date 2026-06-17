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
| AWS Profile name | Named AWS profile to authenticate with — press Enter to enter credentials manually instead | (manual) |
| AWS Access Key | Your AWS access key ID (only asked if no profile name was given) | (required) |
| AWS Secret Key | Your AWS secret access key (only asked if no profile name was given) | (required) |
| AxonOps Org | Optional: AxonOps organization name | (skip) |
| AxonOps Key | Optional: AxonOps API key | (skip) |

```admonish note
The AWS profile name is asked **first**. If you provide one, the access key and secret prompts are skipped — easy-db-lab resolves credentials through that profile (including [AWS SSO](#using-aws-sso-iam-identity-center) profiles). Static access keys are only collected when you leave the profile name blank.
```

setup-profile validates your credentials against AWS immediately (and then provisions resources), so your credentials must be usable **before** you run it. For static keys this is automatic; for an SSO profile, run `aws sso login` first (see below).

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
Use a different profile by setting `EASY_DB_LAB_PROFILE` environment variable before running setup.
```

### Using AWS SSO (IAM Identity Center)

If your AWS access is provisioned through AWS SSO (IAM Identity Center) rather than long-lived access keys, easy-db-lab authenticates through a named AWS profile backed by an SSO session. You do **not** copy temporary credentials out of the AWS access portal — the tool resolves them automatically from your SSO login.

```admonish warning
The AWS access portal's "Command line or programmatic access" panel gives you a temporary access key, secret, **and session token**. Do not use these with easy-db-lab. The static-credential path has no field for a session token, so those credentials will not work. Use the SSO profile flow below instead.
```

**1. Define an SSO profile** in `~/.aws/config`:

```ini
[sso-session my-sso]
sso_start_url = https://my-company.awsapps.com/start
sso_region = us-east-1
sso_registration_scopes = sso:account:access

[profile edl]
sso_session = my-sso
sso_account_id = 123456789012
sso_role_name = YourRoleName
region = us-west-2
```

**2. Log in** to start an SSO session (this opens a browser):

```bash
aws sso login --profile edl
```

**3. Run setup-profile** and enter `edl` when prompted for the AWS profile name (leave the access key and secret blank):

```bash
easy-db-lab setup-profile
```

```admonish important
Run `aws sso login` **before** `setup-profile`. setup-profile validates and provisions against AWS immediately, so it needs an active SSO session. This first-time `aws sso login` → `setup-profile` sequence is only needed once.
```

#### Day-to-day usage

After the one-time setup, you do **not** sign in for every command. An `aws sso login` session lasts for hours (your organization sets the exact window), and easy-db-lab resolves and refreshes credentials from it automatically:

```bash
# Once per work session (e.g. each morning), or whenever the session expires:
aws sso login --profile edl

# Then run commands freely — no per-command authentication:
easy-db-lab up
easy-db-lab cassandra start
easy-db-lab down --auto-approve
```

When the session expires, the next command fails with an authentication error directing you to log in again. Re-run `aws sso login --profile edl` and continue.

```admonish note
For operations that run longer than your SSO session window (e.g. an unattended overnight benchmark), the session can expire mid-run and a later AWS call may fail. For interactive, command-by-command use this does not come up.
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
| `EASY_DB_LAB_PROFILE` | Use a named profile | `default` |
| `EASY_DB_LAB_INSTANCE_TYPE` | Default instance type for `init` | `r3.2xlarge` |
| `EASY_DB_LAB_STRESS_INSTANCE_TYPE` | Default stress instance type | `c7i.2xlarge` |
| `EASY_DB_LAB_AMI` | Override AMI ID | (auto-detected) |

## Verify Installation

After setup completes, verify by running:

```bash
easy-db-lab
```

You should see the help output with available commands.

## Next Steps

Once setup is complete, follow the [Tutorial](../user-guide/tutorial.md) to create your first cluster.
