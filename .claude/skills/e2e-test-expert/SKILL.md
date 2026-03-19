---
name: e2e-test-expert
description: Expert on easy-db-lab's end-to-end test suite. Explains test steps, interprets results, suggests what to test based on changes, and helps understand test failures. Use for questions about the test suite, not to actually run tests (use /e2e-test for that).
allowed-tools: Read, Grep, Glob
user-invocable: true
disable-model-invocation: false
---

# End-to-End Test Expert

I am an expert on easy-db-lab's comprehensive end-to-end test suite defined in `bin/end-to-end-test`. I can explain what tests do, interpret results, and help you understand test failures.

## My Expertise

I have deep knowledge of:
- **Test Steps:** All 35+ test steps and what they validate
- **Test Scope:** What to test for different changes (--cassandra, --spark, etc.)
- **Test Results:** Interpreting success/failure output
- **Test Duration:** Time estimates for different test configurations
- **Test Failures:** Common failure modes and what they mean
- **Test Infrastructure:** AWS resources created, cleanup, costs

## Test Script Location

Test script: `/Users/jhaddad/dev/easy-db-lab/bin/end-to-end-test`

Test steps registry: Lines 1264-1308

## What I Can Help With

### Understanding Test Steps

**Example questions:**
- "What does step_verify_k3s do?"
- "What's tested in the observability stack test?"
- "What happens in step_spark_submit?"
- "Why is there a step_verify_restore?"

**I will:**
- Explain what each step validates
- Show the implementation
- Describe expected outcomes
- Explain why the step is important

### Test Scope Selection

**Example questions:**
- "What should I test if I changed Cassandra code?"
- "Do I need --all or just --clickhouse?"
- "What's the difference between --spark and --cassandra?"
- "Should I use --build for packer changes?"

**I will:**
- Recommend test scope based on your changes
- Explain what each flag enables/disables
- Estimate test duration
- Suggest minimum vs comprehensive testing

### Interpreting Test Results

**Example questions:**
- "What does 'Step 15 failed' mean?"
- "Why did step_wait_k3s_ready timeout?"
- "What does CrashLoopBackOff in diagnostics indicate?"
- "How do I read the failure log?"

**I will:**
- Explain what the failed step was testing
- Interpret error messages
- Suggest root causes
- Recommend debugging steps

### Test Infrastructure

**Example questions:**
- "What AWS resources are created?"
- "How much do tests cost to run?"
- "What gets cleaned up automatically?"
- "Can I reuse a test cluster?"

**I will:**
- List AWS resources created
- Estimate costs per hour
- Explain cleanup behavior
- Describe resource lifecycle

### Resume and Recovery

**Example questions:**
- "How do I resume from step 15?"
- "Can I skip failing steps?"
- "What's the --break flag for?"
- "How do I retest just the failed parts?"

**I will:**
- Explain --start-step usage
- Show how to use breakpoints
- Suggest recovery strategies
- Describe limitations

## Test Structure

### Test Categories

The e2e test suite has 35+ steps organized into categories:

#### 1. Project Root Steps (Run First)
- **step_build_project** - Build JAR with Gradle
- **step_check_version** - Verify CLI version
- **step_build_image** - Build packer AMI (if --build)

#### 2. Cluster Initialization (Core Infrastructure)
- **step_set_policies** - Create IAM policies
- **step_init_cluster** - Initialize 3-node cluster
- **step_setup_kubectl** - Setup kubectl access
- **step_wait_k3s_ready** - Wait for K3s cluster
- **step_verify_k3s** - Verify K3s nodes and pods
- **step_verify_vpc_tags** - Verify VPC tagging
- **step_list_hosts** - List cluster hosts

#### 3. Server & Backup (Core Features)
- **step_test_mcp_server** - Test MCP server /status endpoint
- **step_verify_s3_backup** - Verify S3 config backup
- **step_verify_restore** - Test restore from VPC ID

#### 4. Cassandra Tests (if --cassandra)
- **step_setup_cassandra** - Install Cassandra 5.0
- **step_verify_cassandra_backup** - Verify Cassandra S3 backup
- **step_test_ssh_nodetool** - SSH and nodetool
- **step_check_sidecar** - Check Cassandra Sidecar API
- **step_cassandra_start_stop** - Start/stop/restart cycle
- **step_stress_test** - Run stress on app node
- **step_stress_k8s** - Run stress as K8s Job

#### 5. Spark Tests (if --spark)
- **step_spark_submit** - Submit read-write Spark job to EMR
- **step_spark_status** - Check job status, download logs
- **step_bulk_writer_sidecar** - Test Cassandra Analytics bulk writer (sidecar)
- **step_connector_writer** - Test standard Cassandra connector writer

#### 6. ClickHouse Tests (if --clickhouse)
- **step_clickhouse_start** - Deploy ClickHouse StatefulSet
- **step_clickhouse_test** - Test connectivity and basic ops
- **step_clickhouse_s3_tier_test** - Test S3 tier storage policy
- **step_clickhouse_stop** - Stop ClickHouse cluster

#### 7. OpenSearch Tests (if --opensearch)
- **step_opensearch_start** - Create OpenSearch domain (10-30 min)
- **step_opensearch_test** - Test connectivity and indexing
- **step_opensearch_stop** - Delete OpenSearch domain

#### 8. Observability & Utilities
- **step_test_exec** - Test exec command (parallel & sequential)
- **step_generate_s3_traffic** - Generate S3 metrics for YACE
- **step_test_observability** - Test VictoriaMetrics/Logs/Grafana
- **step_test_dashboards** - Validate all Grafana dashboards
- **step_test_logs_query** - Test logs query CLI command
- **step_test_metrics_backup** - Test VictoriaMetrics backup
- **step_test_logs_backup** - Test VictoriaLogs backup

#### 9. Cleanup
- **step_teardown** - Tear down cluster (normally via cleanup trap)

## Test Flags

### Available Flags

```bash
--list-steps, -l         # List all steps and exit
--break N[,M,...]        # Pause before step(s) for inspection
--start-step N           # Resume from step N (1-based)
--build                  # Build packer AMI first (+30-45 min)
--spark                  # Enable Spark tests (implies --cassandra)
--cassandra              # Enable Cassandra tests
--clickhouse             # Enable ClickHouse tests
--opensearch             # Enable OpenSearch tests
--all                    # Enable all database tests
--ebs                    # Use EBS volumes instead of instance store
--no-teardown            # Skip teardown prompt (for automation)
```

### Flag Combinations

**Minimal (fastest):**
```bash
bin/end-to-end-test --cassandra                    # 15-20 minutes
```

**ClickHouse only:**
```bash
bin/end-to-end-test --clickhouse                   # 10-15 minutes
```

**Spark + Cassandra:**
```bash
bin/end-to-end-test --spark                        # 25-35 minutes (implies --cassandra)
```

**Full regression:**
```bash
bin/end-to-end-test --all                          # 45-60 minutes
```

**With AMI build:**
```bash
bin/end-to-end-test --build --cassandra            # 45-60 minutes (build adds 30-45 min)
```

## What Each Flag Tests

### --cassandra

Enables Cassandra-specific tests:
- Cassandra 5.0 installation
- Configuration updates (storage_compatibility_mode: NONE)
- Start/stop/restart cycles
- SSH access and nodetool
- Sidecar API availability
- Stress test on app node
- Stress test as K8s Job
- S3 backup of Cassandra configs

**Duration:** ~15-20 minutes
**Resources:** 3x c5d.2xlarge nodes
**Cost:** ~$1/hour

### --clickhouse

Enables ClickHouse-specific tests:
- ClickHouse StatefulSet deployment (3 replicas)
- Cluster connectivity
- Table creation with ReplicatedMergeTree
- S3 main storage policy
- S3 tier storage policy with automatic tiering
- MOVE PARTITION to S3 disk
- Cleanup and namespace deletion

**Duration:** ~10-15 minutes
**Resources:** 3x c5d.2xlarge nodes
**Cost:** ~$1/hour

### --spark

Enables Spark/EMR tests (requires --cassandra):
- EMR cluster creation
- Read-write Spark job (KeyValuePrefixCount)
- Bulk writer via Cassandra Analytics (sidecar transport)
- Standard connector writer
- Row count verification at LOCAL_QUORUM
- Spark logs to VictoriaLogs (via OTel)
- EMR log download

**Duration:** ~25-35 minutes
**Resources:** 3x c5d.2xlarge + EMR (m5.xlarge master, 2x m5.xlarge workers)
**Cost:** ~$2.50/hour

### --opensearch

Enables OpenSearch tests:
- AWS OpenSearch domain creation (very slow)
- Cluster health check
- Index creation
- Document insertion/retrieval
- Domain deletion

**Duration:** +10-30 minutes (domain creation is slow and variable)
**Resources:** 3x c5d.2xlarge + OpenSearch domain
**Cost:** ~$1.50-3/hour

### --all

Enables ALL database tests:
- Everything in --cassandra
- Everything in --clickhouse
- Everything in --opensearch
- Everything in --spark

**Duration:** ~45-60 minutes
**Resources:** All of the above
**Cost:** ~$3-4/hour

### --build

Builds packer AMI before running tests:
- Builds base AMI with all dependencies
- Required when packer scripts changed
- Very time-consuming

**Duration:** +30-45 minutes
**Note:** Only use when packer changes need testing

### --ebs

Uses EBS volumes instead of instance store:
- Enables persistent storage
- Uses gp3 EBS volumes (256GB)
- Slightly slower than instance store

**Note:** Useful for testing EBS-specific scenarios

## Common Test Scenarios

### Scenario 1: Changed Cassandra Config Code

**What to test:**
```bash
bin/end-to-end-test --cassandra --no-teardown
```

**Why:**
- Tests Cassandra installation and configuration
- Validates config update mechanisms
- ~15-20 minutes

### Scenario 2: Changed ClickHouse Manifest Builder

**What to test:**
```bash
bin/end-to-end-test --clickhouse --no-teardown
```

**Why:**
- Tests ClickHouse StatefulSet deployment
- Validates K8s manifest generation
- ~10-15 minutes

### Scenario 3: Changed Spark Connector

**What to test:**
```bash
bin/end-to-end-test --spark --no-teardown
```

**Why:**
- Tests Spark job submission to EMR
- Validates both bulk writer and standard connector
- Requires Cassandra for data writes
- ~25-35 minutes

### Scenario 4: Changed Core Infrastructure

**What to test:**
```bash
bin/end-to-end-test --cassandra --no-teardown
```

**Why:**
- Core changes could affect anything
- Cassandra is fastest to validate basics
- Consider --all for comprehensive validation
- ~15-20 minutes (or 45-60 for --all)

### Scenario 5: Changed Observability Stack

**What to test:**
```bash
bin/end-to-end-test --cassandra --no-teardown
```

**Why:**
- Observability stack is tested regardless of flags
- Cassandra provides enough activity to generate metrics/logs
- ~15-20 minutes

### Scenario 6: Packer Base Scripts Changed

**What to test:**
```bash
bin/end-to-end-test --build --cassandra --no-teardown
```

**Why:**
- Must rebuild AMI to test packer changes
- --build adds 30-45 minutes but necessary
- ~45-60 minutes total

## Understanding Test Failures

### Common Failure Patterns

#### Pattern 1: step_wait_k3s_ready fails

**Meaning:**
- K3s cluster didn't come up within timeout
- kubectl can't connect to API server

**Possible causes:**
- K3s installation failed on control node
- Security group doesn't allow port 6443
- Network connectivity issues

**Debug:**
```bash
ssh -F sshConfig control "systemctl status k3s"
ssh -F sshConfig control "journalctl -u k3s -n 50"
```

#### Pattern 2: step_verify_k3s fails (pods not ready)

**Meaning:**
- K3s cluster is up but system pods aren't running
- Nodes not joining cluster

**Possible causes:**
- Insufficient node resources
- Network plugin issues
- Token mismatch between server and agents

**Debug:**
```bash
export KUBECONFIG=$(pwd)/kubeconfig
kubectl get nodes
kubectl get pods -A
kubectl get events -A --sort-by='.lastTimestamp'
```

#### Pattern 3: step_setup_cassandra fails

**Meaning:**
- Cassandra service won't start
- Configuration errors

**Possible causes:**
- Disk space issues
- Java version mismatch
- Configuration syntax errors

**Debug:**
```bash
ssh -F sshConfig db-0 "systemctl status cassandra"
ssh -F sshConfig db-0 "journalctl -u cassandra -n 100"
```

#### Pattern 4: step_spark_status fails (job FAILED)

**Meaning:**
- Spark job submitted but failed on EMR

**Possible causes:**
- Cassandra not accessible from EMR
- JAR configuration issues
- Data format incompatibilities

**Debug:**
The test automatically includes EMR diagnostics:
- EMR step details
- stderr/stdout from S3
- Available log files

Look for specific error messages in the failure log.

#### Pattern 5: step_clickhouse_start fails (pods not ready)

**Meaning:**
- ClickHouse pods won't start or stay running

**Possible causes:**
- ConfigMap issues
- Storage provisioning failures
- Resource constraints

**Debug:**
```bash
kubectl get pods -n clickhouse
kubectl describe pod <clickhouse-pod> -n clickhouse
kubectl logs <clickhouse-pod> -n clickhouse
```

#### Pattern 6: step_test_observability fails

**Meaning:**
- VictoriaMetrics or VictoriaLogs not responding
- Grafana can't connect to datasources

**Possible causes:**
- Pods not running
- Port conflicts
- Configuration errors

**Debug:**
```bash
kubectl get pods -n monitoring
kubectl get pods -n grafana
ssh control0 "curl -s http://localhost:8428/health"
ssh control0 "curl -s http://localhost:9428/health"
```

### Failure Log Structure

When a step fails, the failure log includes:

```
============================================================
FAILURE N: Step X - <step_name>
Exit code: <code>
Time: <timestamp>
============================================================

--- Step Output (last 100 lines) ---
<command output>

--- Diagnostics ---
>> Pods NOT Running/Completed:
<non-running pods>

>> CrashLoopBackOff pod logs (last 20 lines each):
<logs from crashed pods>

>> kubectl get events --sort-by=.lastTimestamp (last 20):
<recent K8s events>

>> df -h:
<disk usage>
```

Use this information to understand what went wrong.

## Resume from Failure

### List Steps

```bash
bin/end-to-end-test --list-steps
```

Shows all 35+ steps with numbers.

### Resume from Specific Step

```bash
# Example: Step 15 failed, you fixed the issue
bin/end-to-end-test --start-step 15 --cassandra --no-teardown
```

**Important:**
- Step numbers are 1-based (match --list-steps output)
- Must provide same flags (--cassandra, etc.)
- Cluster must still be running
- Previous steps assumed successful

### Use Breakpoints

Pause before steps for manual inspection:

```bash
# Pause before step 10 and step 15
bin/end-to-end-test --break 10,15 --cassandra --no-teardown

# When script reaches step 10:
# >>> BREAKPOINT at step 10: <step_name>
# Press Enter to continue...
```

**Use cases:**
- Inspect cluster state before a specific step
- Verify previous steps manually
- Set up monitoring before a test runs

## Test Infrastructure Details

### AWS Resources Created

**Every test:**
- 3x EC2 instances (1 control, 2 database nodes by default)
- VPC with subnets
- Security groups
- IAM policies
- S3 bucket (for configs and backups)
- Route tables, internet gateway

**With --spark:**
- EMR cluster (1 master, 2 workers)
- Additional security groups
- EMR logs in S3

**With --opensearch:**
- OpenSearch domain (managed service)
- OpenSearch-specific security groups

**With --ebs:**
- 3x EBS volumes (gp3, 256GB each)

### Cost Breakdown

**Base cluster (--cassandra or --clickhouse):**
- 3x c5d.2xlarge: ~$1.00/hour
- S3 storage: negligible
- **Total: ~$1/hour**

**With --spark:**
- Base cluster: ~$1.00/hour
- EMR (m5.xlarge): ~$1.50/hour
- **Total: ~$2.50/hour**

**With --opensearch:**
- Base cluster: ~$1.00/hour
- OpenSearch domain: ~$0.50-2.00/hour (varies)
- **Total: ~$1.50-3.00/hour**

**With --all:**
- All of the above
- **Total: ~$3-4/hour**

**Always tear down when done to stop charges!**

### Cleanup Behavior

**Automatic:**
- None - script never auto-tears down
- Always prompts for confirmation

**With --no-teardown:**
- Skips prompt entirely
- Exits with status code (0=pass, 1=fail)
- Cluster left running for inspection

**Manual cleanup:**
```bash
cd /Users/jhaddad/dev/easy-db-lab
easy-db-lab down --yes
```

## Best Practices

### 1. Test What You Changed

Don't always use --all:
- Changed Cassandra? Use --cassandra
- Changed ClickHouse? Use --clickhouse
- Core infrastructure? Use --cassandra (fastest validation)

### 2. Use --no-teardown for Automation

When running via skill or CI:
```bash
bin/end-to-end-test --cassandra --no-teardown
```

Exit code tells you if tests passed.

### 3. Keep Cluster on Failure

If tests fail:
- Leave cluster running
- Use `/debug-environment` to investigate
- Fix and resume with --start-step
- Tear down only when done debugging

### 4. Monitor AWS Costs

Tests create real resources that cost money:
- Always tear down when done
- Don't leave clusters running overnight
- Set billing alerts in AWS

### 5. Build Only When Necessary

Avoid --build unless:
- You changed packer scripts
- You need to test provisioning
- CI requires it

--build adds 30-45 minutes.

## Related Skills

- **`/e2e-test`** - Actually run end-to-end tests (executor)
- **`/debug-environment`** - Debug test failures
- **`/easy-db-lab-expert`** - General easy-db-lab questions
- **`/k8-expert`** - Kubernetes-specific questions

## Quick Reference

**Invoke me:**
```
/e2e-test-expert
```

**Example questions:**
- "What does step 15 test?"
- "Should I use --all or --cassandra?"
- "What does this failure mean?"
- "How long do tests take?"
- "How much do tests cost?"
- "Can I resume from step 20?"
- "What AWS resources are created?"

**I'll provide:**
- Test step explanations
- Scope recommendations
- Failure interpretations
- Duration/cost estimates
- Resume instructions
- Infrastructure details

---

I'm here to help you understand the e2e test suite. Ask me anything about the tests!

**Note:** I explain the tests. To actually run them, use `/e2e-test`.
