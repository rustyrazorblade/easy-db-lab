# Setup

This guide walks you through the initial setup of easy-db-lab, including AWS credentials configuration, IAM policies, and AMI creation.

## Overview

The `profile setup` command handles all initial configuration interactively. It will:

1. Collect your email and AWS credentials
2. Validate your AWS access
3. Create necessary AWS resources (key pair, IAM roles, Packer VPC)
4. Build or validate the required AMI

## Prerequisites

Before running setup:

- **AWS Account**: An AWS account with appropriate permissions (see [IAM Policies](#step-2-getting-iam-policies) below)
- **Java 21+**: Required to run easy-db-lab
- **Docker**: Required only if building custom AMIs

## Step 1: Run Setup Profile

Run the interactive setup:

```bash
easy-db-lab profile setup
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
| SSH transport | How SSH reaches cluster nodes: `direct` (public IP, port 22) or `ssm` (tunneled through AWS SSM Session Manager, for networks that block outbound port 22 — see [SSH over SSM Session Manager](../user-guide/network-connectivity.md#ssh-over-ssm-session-manager)) | direct |

```admonish note
The AWS profile name is asked **first**. If you provide one, the access key and secret prompts are skipped — easy-db-lab resolves credentials through that profile (including [AWS SSO](#using-aws-sso-iam-identity-center) profiles). Static access keys are only collected when you leave the profile name blank.
```

`profile setup` validates your credentials against AWS immediately (and then provisions resources), so your credentials must be usable **before** you run it. For static keys this is automatic; for an SSO profile, run `aws sso login` first (see below).

### What Gets Created

During setup, the following AWS resources are created:

- **EC2 Key Pair**: For SSH access to instances
- **IAM Role**: For instance permissions (`EasyDBLabEC2Role`), including a minimal inline Session Manager policy so every node can be reached over SSM Session Manager
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

**3. Run `profile setup`** and enter `edl` when prompted for the AWS profile name (leave the access key and secret blank):

```bash
easy-db-lab profile setup
```

```admonish important
Run `aws sso login` **before** `profile setup`. It validates and provisions against AWS immediately, so it needs an active SSO session. This first-time `aws sso login` → `profile setup` sequence is only needed once.
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

This displays five policies:

| Policy | Purpose |
|--------|---------|
| EC2 | Create/manage EC2 instances, VPCs, security groups; open SSM Session Manager sessions to cluster instances |
| IAM | Create instance roles and profiles |
| EMR | Create Spark clusters (optional) |
| OpenSearch | Create OpenSearch domains (optional) |
| Compactor | Run the [account compactor](../user-guide/compactor.md) on ECS Fargate: ECS, its log group, and passing its two roles |

### Filter by Policy Name

To show a specific policy:

```bash
easy-db-lab show-iam-policies ec2    # Show EC2 policy only
easy-db-lab show-iam-policies iam    # Show IAM policy only
easy-db-lab show-iam-policies emr    # Show EMR policy only
easy-db-lab show-iam-policies opensearch    # Show the OpenSearch policy only
easy-db-lab show-iam-policies compactor    # Show the compactor policy only
```

### Applying the Policies

Create each policy as a customer-managed policy, and attach all five to one IAM user, group, or role. The policy names are `EasyDBLabEC2`, `EasyDBLabIAM`, `EasyDBLabEMR`, `EasyDBLabOpenSearch` and `EasyDBLabCompactor`. Run these steps with an AWS profile that can manage IAM.

```admonish warning
Inline policies have a 5,120 byte limit which may not fit all the policies. Use managed policies instead.
```

```admonish note
`show-iam-policies` gets the account ID from the credentials of your easy-db-lab profile, not from the AWS profile you give the AWS CLI. Make sure that both point to the same AWS account. If you have no easy-db-lab profile yet, the command runs `profile setup` first.
```

#### With `bin/set-policies`

If you have a source checkout, use `bin/set-policies`. Run `./gradlew installDist` first, because the script calls `bin/easy-db-lab`. The script creates the five managed policies and attaches them to the target you name:

```bash
bin/set-policies --user-name easy-db-lab --profile <admin-profile>
```

| Option | Environment variable | Description |
|--------|----------------------|-------------|
| `--user-name <name>` | `ECL_USER_NAME` | Attach the policies to an IAM user |
| `--group-name <name>` | `ECL_GROUP_NAME` | Attach the policies to an IAM group |
| `--role-name <name>` | `ECL_ROLE_NAME` | Attach the policies to an IAM role |
| `--profile <profile>` | `ECL_PROFILE` or `AWS_PROFILE` | AWS CLI profile to run the IAM calls with |
| `--dry-run` | `ECL_DRY_RUN=true` | Show the AWS CLI calls without making them |

Give exactly one of `--user-name`, `--group-name` and `--role-name`. Before it changes anything, the script fetches all five policies and checks that each one is valid JSON and names only the account of `--profile`. If a check fails, the script stops and changes nothing. The script stops with an error if an attach fails. At the end, it checks that all five policies are attached to the target.

You can run the script again at any time. If a policy already exists, the script creates a new default version of it from the current output of `show-iam-policies`. This updates the policies after you upgrade easy-db-lab.

#### Without a Source Checkout

If you installed with Homebrew, you do not have `bin/set-policies`. Do the same steps with the AWS CLI. For each policy, write its JSON to a file, create the managed policy, and attach it. This example applies the EC2 policy to the user `easy-db-lab`:

```bash
easy-db-lab show-iam-policies ec2 > EasyDBLabEC2.json
aws iam create-policy --policy-name EasyDBLabEC2 \
  --policy-document file://EasyDBLabEC2.json --profile <admin-profile>
aws iam attach-user-policy --user-name easy-db-lab \
  --policy-arn arn:aws:iam::<account-id>:policy/EasyDBLabEC2 --profile <admin-profile>
```

Repeat these commands for `iam` (`EasyDBLabIAM`), `emr` (`EasyDBLabEMR`), `opensearch` (`EasyDBLabOpenSearch`) and `compactor` (`EasyDBLabCompactor`). To attach the policies to a group, use `aws iam attach-group-policy --group-name <group>` instead of `attach-user-policy`.

If a policy already exists, `create-policy` fails. To update it, create a new default version:

```bash
aws iam create-policy-version --set-as-default \
  --policy-arn arn:aws:iam::<account-id>:policy/EasyDBLabEC2 \
  --policy-document file://EasyDBLabEC2.json --profile <admin-profile>
```

```admonish note
A managed policy can have at most five versions. If a policy has five versions, delete the oldest non-default version with `aws iam delete-policy-version` before you create a new one. `bin/set-policies` does this for you.
```

### Creating a Dedicated IAM User

You can run easy-db-lab as a dedicated IAM user with access keys. Run these steps with an AWS profile that can manage IAM. The examples use the user name `easy-db-lab`.

1. Create the user:

    ```bash
    aws iam create-user --user-name easy-db-lab --profile <admin-profile>
    ```

2. Create and attach the five policies. Use `bin/set-policies --user-name easy-db-lab --profile <admin-profile>`, or do the [steps without a source checkout](#without-a-source-checkout).

3. Make sure that all five policies are attached. The output must list `EasyDBLabEC2`, `EasyDBLabIAM`, `EasyDBLabEMR`, `EasyDBLabOpenSearch` and `EasyDBLabCompactor`.

    ```bash
    aws iam list-attached-user-policies --user-name easy-db-lab --profile <admin-profile>
    ```

4. Create an access key for the user:

    ```bash
    aws iam create-access-key --user-name easy-db-lab --profile <admin-profile>
    ```

    AWS shows the secret access key only once, in this output. Copy it now. If you lose it, create a new access key.

5. Store the access key and secret in a named AWS profile:

    ```bash
    aws configure --profile <easy-db-lab-profile>
    ```

6. Run `easy-db-lab profile setup`. When it asks for the AWS profile name, enter `<easy-db-lab-profile>`. The access key and secret prompts are then skipped. Alternatively, leave the profile name blank and enter the access key and secret at the prompts.

### Recommended IAM Setup

For teams with multiple users, we recommend attaching the managed policies to an IAM group:

1. **Create an IAM group** (e.g., "EasyDBLabUsers"):

    ```bash
    aws iam create-group --group-name EasyDBLabUsers --profile <admin-profile>
    ```

2. **Create and attach the policies** to the group:

    ```bash
    bin/set-policies --group-name EasyDBLabUsers --profile <admin-profile>
    ```

    If you have no source checkout, do the [steps without a source checkout](#without-a-source-checkout) with `aws iam attach-group-policy`.

3. **Add users** to the group:

    ```bash
    aws iam add-user-to-group --group-name EasyDBLabUsers --user-name <user> --profile <admin-profile>
    ```

To check the attached policies, run `aws iam list-attached-group-policies --group-name EasyDBLabUsers --profile <admin-profile>`.

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

If your profile's SSH transport is `ssm`, AMI builds reach the build instance over SSM Session
Manager too. The first such build also builds a local Packer image with the Session Manager plugin
added (see [AMI builds](../user-guide/network-connectivity.md#ami-builds)).

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `EASY_DB_LAB_USER_DIR` | Override configuration directory | `~/.easy-db-lab` |
| `EASY_DB_LAB_PROFILE` | Use a named profile | `default` |
| `EASY_DB_LAB_INSTANCE_TYPE` | Default database instance type for `init` | `i4i.xlarge` |
| `EASY_DB_LAB_STRESS_INSTANCE_TYPE` | Default application (stress) instance type | `c6id.2xlarge` |
| `EASY_DB_LAB_AMI` | Override AMI ID | (auto-detected) |

## Verify Installation

After setup completes, verify by running:

```bash
easy-db-lab
```

You should see the help output with available commands.

## Next Steps

Once setup is complete, follow the [Tutorial](../user-guide/tutorial.md) to create your first cluster.
