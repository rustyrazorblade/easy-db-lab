---
skill: "/aws-policy-debug"
category: "Debugging & Setup"
purpose: "Debug AWS IAM policy issues during easy-db-lab setup"
---

# AWS IAM Policy Debugging Skill

Expert knowledge for debugging AWS IAM policy issues during easy-db-lab setup and helping other developers configure their AWS credentials.

## Available Tools

### 1. Show IAM Policies Command

**Command**: `bin/easy-db-lab sip [policy-name]`
**Alias**: `sip`

Displays easy-db-lab IAM policies with the user's AWS account ID automatically populated.

**Usage:**
```bash
# Show all policies with headers (human-readable)
bin/easy-db-lab sip

# Show specific policy without header (script-friendly)
bin/easy-db-lab sip ec2    # EC2 policy only
bin/easy-db-lab sip iam    # IAM/S3 policy only
bin/easy-db-lab sip emr    # EMR policy only
```

**Policy Names:**
- `ec2` → EasyDBLabEC2 (EC2 instance management, AMI creation, VPC operations)
- `iam` → EasyDBLabIAM (IAM role/instance profile creation, S3 bucket operations)
- `emr` → EasyDBLabEMR (EMR cluster creation and management)

**Requirements:**
- AWS credentials must be configured (fails if not)
- Account ID is retrieved automatically via AWS STS

**Output Format:**
- All policies: Includes headers (`=== PolicyName ===`) between policies
- Specific policy: Raw JSON only (no headers) - perfect for piping to files or AWS CLI

### 2. Set Policies Script

**Script**: `bin/set-policies`

Automatically applies easy-db-lab IAM policies to AWS users, groups, or roles. Essential for helping other developers debug setup issues.

**Command-Line Flags:**
```bash
--user-name <name>       # Apply policies to IAM user
--group-name <name>      # Apply policies to IAM group
--role-name <name>       # Apply policies to IAM role
--profile <profile>      # AWS profile to use
--policies <list>        # Which policies: ec2, iam, emr, or all (default: all)
--dry-run                # Show what would be done without applying
-h, --help               # Show help message
```

**Environment Variables:**
```bash
ECL_USER_NAME            # IAM user name
ECL_GROUP_NAME           # IAM group name
ECL_ROLE_NAME            # IAM role name
ECL_PROFILE              # AWS profile
ECL_POLICIES             # Which policies (ec2, iam, emr, all)
ECL_DRY_RUN              # Set to "true" for dry run
AWS_PROFILE              # AWS profile (fallback if ECL_PROFILE not set)
```

**Precedence**: CLI flags > ECL_* env vars > AWS_PROFILE > defaults

**Requirements:**
- Exactly one of --user-name, --group-name, or --role-name must be specified
- AWS credentials with permission to put inline policies on the target
- `bin/easy-db-lab sip` command must be available

## Common Workflows

### Workflow 1: View All Policies
**Use Case**: Developer wants to see what IAM permissions are required.

```bash
# View all three policies with account ID populated
bin/easy-db-lab sip
```

**Output**: All policies with headers showing policy names.

### Workflow 2: Extract Policy for Manual Application
**Use Case**: Developer wants to manually create inline policy via AWS Console or CLI.

```bash
# Get EC2 policy and save to file
bin/easy-db-lab sip ec2 > ec2-policy.json

# Apply via AWS CLI
aws iam put-user-policy \
  --user-name my-user \
  --policy-name EasyDBLabEC2 \
  --policy-document file://ec2-policy.json
```

### Workflow 3: Apply Policies to User (Dry Run)
**Use Case**: Developer wants to preview policy application before actually applying.

```bash
# Preview what would be applied
AWS_PROFILE=sandbox-admin \
  bin/set-policies --user-name easy-db-lab --dry-run
```

**Output**: Shows AWS CLI commands that would be executed without running them.

### Workflow 4: Apply All Policies to User
**Use Case**: Developer needs to quickly apply all policies to fix permission errors.

```bash
# Apply all three policies
AWS_PROFILE=sandbox-admin \
  bin/set-policies --user-name easy-db-lab

# Or with environment variables
export AWS_PROFILE=sandbox-admin
export ECL_USER_NAME=easy-db-lab
bin/set-policies
```

### Workflow 5: Apply Specific Policy to Group
**Use Case**: Team wants to grant only EC2 permissions to a developer group.

```bash
bin/set-policies \
  --group-name easy-db-lab-developers \
  --policies ec2 \
  --profile prod-account
```

### Workflow 6: Help Other Developer Debug Setup
**Use Case**: Another developer is getting permission errors during `easy-db-lab init`.

**Steps:**
1. Identify which permission is missing from error message
2. Apply the specific policy:
```bash
# If they're missing EC2 CreateImage permission
bin/set-policies \
  --user-name their-username \
  --policies ec2 \
  --profile their-profile
```

## Troubleshooting Guide

### Issue: Permission Denied Errors During Setup

**Symptoms:**
- `easy-db-lab init` fails with "UnauthorizedOperation" or "AccessDenied"
- Error messages mention specific AWS actions (e.g., "ec2:CreateKeyPair", "iam:CreateRole")

**Diagnosis:**
1. Identify which service is denied from error message:
   - `ec2:*` actions → Missing EC2 policy
   - `iam:*` or `s3:*` actions → Missing IAM policy
   - `emr:*` actions → Missing EMR policy

2. View the required policy:
```bash
bin/easy-db-lab sip ec2   # If EC2 permission error
bin/easy-db-lab sip iam   # If IAM/S3 permission error
bin/easy-db-lab sip emr   # If EMR permission error
```

**Resolution:**
```bash
# Apply the missing policy
bin/set-policies --user-name <username> --policies <ec2|iam|emr> --profile <profile>

# Or apply all policies at once
bin/set-policies --user-name <username> --profile <profile>
```

### Issue: Account ID Not Found

**Symptoms:**
- `bin/easy-db-lab sip` fails with "Failed to get AWS account ID"
- Error message: "Please run 'easy-db-lab init' to set up credentials"

**Cause:**
- AWS credentials are not configured or invalid

**Resolution:**
1. Verify credentials are configured:
```bash
aws sts get-caller-identity --profile <profile>
```

2. If credentials are missing, configure them:
```bash
aws configure --profile <profile>
```

3. If credentials are invalid, update them in `~/.aws/credentials`

### Issue: Policy Application Fails

**Symptoms:**
- `bin/set-policies` fails with permission error
- Error: "User is not authorized to perform: iam:PutUserPolicy"

**Cause:**
- The AWS credentials being used don't have permission to create inline policies

**Resolution:**
- Use credentials that have `iam:PutUserPolicy`, `iam:PutGroupPolicy`, or `iam:PutRolePolicy` permission
- Or manually apply policies via AWS Console with an administrator account

### Issue: Helping Remote Developer

**Scenario:** Another developer in a different AWS account needs help setting up easy-db-lab.

**Steps:**
1. Ask them to provide their AWS IAM user name
2. Ask them to provide their AWS profile name (or have them set AWS_PROFILE)
3. Have them run:
```bash
AWS_PROFILE=their-profile \
  bin/set-policies --user-name their-username --dry-run
```
4. Review the dry run output together
5. If it looks correct, remove `--dry-run` to apply:
```bash
AWS_PROFILE=their-profile \
  bin/set-policies --user-name their-username
```

## Key Concepts

### Account ID Substitution
The policy templates contain `__ACCOUNT_ID__` placeholders (and `__LEGACY_TAG_KEY__` / `__TAG_VALUE__` for the marker tag conditions) that `AWSPolicy.UserIAM.loadAll` replaces with the actual AWS account ID and the tag constants. This ensures:
- Policies are scoped to the specific AWS account
- No cross-account access is granted accidentally
- PassRole permissions are restricted to the correct account

### Policy Types
- **EC2 Policy**: Manages EC2 instances, key pairs, AMIs, VPCs, security groups
- **IAM Policy**: Creates IAM roles and instance profiles, manages S3 buckets for cluster data
- **EMR Policy**: Creates and manages EMR clusters for Spark workloads (optional feature)

### Inline vs. Managed Policies
The `bin/set-policies` script creates **inline policies** attached directly to users/groups/roles:
- Pros: Easy to manage, version-controlled via script
- Cons: Not reusable across multiple users (but that's fine for developer accounts)

## Best Practices

1. **Always use dry-run first** when applying policies to production accounts
2. **Apply all policies at once** during initial setup to avoid multiple permission errors
3. **Use specific policies** when debugging to minimize permission grants
4. **Set AWS_PROFILE** environment variable for consistency across commands
5. **Keep policies in sync** by always using `bin/easy-db-lab sip` as the source of truth

## Error Messages Reference

### "Failed to get AWS account ID"
→ AWS credentials not configured. Run `aws configure` or check `~/.aws/credentials`

### "Must specify exactly one of --user-name, --group-name, or --role-name"
→ No target specified for policy application. Add one of the required flags.

### "Cannot specify multiple targets"
→ More than one target specified (user, group, and role are mutually exclusive)

### "Invalid --policies value"
→ Policy name must be one of: ec2, iam, emr, all

### "No policies found matching: <name>"
→ Policy filter didn't match any policies. Use ec2, iam, or emr.

## Quick Reference

**View all policies:**
```bash
bin/easy-db-lab sip
```

**Get specific policy for scripting:**
```bash
bin/easy-db-lab sip ec2 > policy.json
```

**Preview policy application:**
```bash
bin/set-policies --user-name myuser --dry-run
```

**Apply all policies:**
```bash
AWS_PROFILE=myprofile bin/set-policies --user-name myuser
```

**Apply specific policy:**
```bash
bin/set-policies --user-name myuser --policies ec2
```
