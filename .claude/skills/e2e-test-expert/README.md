# End-to-End Test Expert Skill

A specialized knowledge agent for easy-db-lab's comprehensive end-to-end test suite. Explains test steps, interprets results, and helps understand the testing infrastructure.

## Purpose

This skill provides expert guidance on the e2e test suite defined in `bin/end-to-end-test`. It explains what tests do, helps interpret results, and recommends test scope based on code changes. This is a Q&A agent, not a test executor.

## When to Use

Use this skill when you need to:
- ✅ Understand what specific test steps do
- ✅ Choose appropriate test scope (--cassandra, --all, etc.)
- ✅ Interpret test failures and results
- ✅ Estimate test duration and costs
- ✅ Learn how to resume from failed steps
- ✅ Understand test infrastructure

## When NOT to Use

Don't use this skill when you need to:
- ❌ Actually run tests (use `/e2e-test`)
- ❌ Debug test failures (use `/debug-environment`)
- ❌ Execute commands or modify code
- ❌ Analyze active test clusters (use `/debug-environment`)

This skill **explains** the tests. To **run** them, use `/e2e-test`.

## Expertise Areas

### Test Steps
- All 35+ test steps and their purposes
- Step dependencies and ordering
- What each step validates
- Expected outcomes

### Test Scope
- Flag combinations (--cassandra, --spark, etc.)
- What each flag enables/disables
- Scope recommendations based on code changes
- Duration estimates

### Test Results
- Interpreting success/failure output
- Understanding failure logs
- Common failure patterns
- Diagnostic information

### Test Infrastructure
- AWS resources created
- Cost estimates per configuration
- Cleanup behavior
- Resource lifecycle

### Test Execution
- Using --start-step for resume
- Breakpoints for inspection
- --build flag timing
- --no-teardown behavior

## Usage Examples

### Example 1: Understanding a Step

```
/e2e-test-expert

What does step_verify_k3s do?
```

**Response:**
Explains that step_verify_k3s validates K3s cluster health by:
1. Listing all nodes and verifying they're Ready
2. Listing all system pods
3. Waiting for all nodes to reach Ready state (120s timeout)

Shows implementation location, explains why it's important, describes expected output.

### Example 2: Scope Selection

```
/e2e-test-expert

I changed ClickHouse manifest builder code. What should I test?
```

**Response:**
Recommends `--clickhouse` flag, explains it will:
- Deploy ClickHouse StatefulSet
- Test connectivity and operations
- Validate S3 tier storage policy
- Run for ~10-15 minutes
- Cost ~$1/hour

Provides command: `bin/end-to-end-test --clickhouse --no-teardown`

### Example 3: Interpreting Failure

```
/e2e-test-expert

Step 15 failed with "CrashLoopBackOff" in diagnostics. What does this mean?
```

**Response:**
Explains:
- CrashLoopBackOff means a pod is repeatedly crashing
- Step 15 context (what was being tested)
- Common causes for that specific step
- How to find pod logs in the failure output
- Recommends using `/debug-environment` for active investigation

### Example 4: Resume Question

```
/e2e-test-expert

How do I resume from step 20 after fixing an issue?
```

**Response:**
Shows:
```bash
bin/end-to-end-test --start-step 20 --cassandra --no-teardown
```

Explains:
- Step numbers are 1-based
- Must provide same flags as original run
- Cluster must still be running
- Previous steps assumed successful

### Example 5: Cost Estimation

```
/e2e-test-expert

How much does --all cost to run?
```

**Response:**
Breaks down costs:
- Base cluster: ~$1/hour
- EMR (Spark): +$1.50/hour
- OpenSearch: +$0.50-2/hour
- Total: ~$3-4/hour
- Duration: 45-60 minutes
- Estimated total: $3-4

Emphasizes always tearing down when done.

## Knowledge Sources

### Primary Source
- `bin/end-to-end-test` - The complete test script

### Key Sections
- **Lines 120-1257:** All test step functions
- **Lines 1264-1308:** Test step registry
- **Lines 1339-1433:** Test execution engine
- **Lines 1442-1486:** Cleanup and failure handling

### Related Documentation
- Project CLAUDE.md files
- `docs/` user documentation
- AWS cost documentation

## Test Structure

The test suite has 35+ steps organized into categories:

### 1. Project Build (3 steps)
Build JAR, check version, optionally build AMI

### 2. Cluster Setup (8 steps)
Initialize cluster, setup K3s, verify infrastructure

### 3. Core Features (3 steps)
MCP server, S3 backup, restore verification

### 4. Cassandra (7 steps)
Install, configure, start/stop, stress tests

### 5. Spark (4 steps)
Submit jobs, verify status, test writers

### 6. ClickHouse (4 steps)
Deploy, test, S3 tier, cleanup

### 7. OpenSearch (3 steps)
Create domain, test, delete

### 8. Observability & Utils (9 steps)
Test observability stack, backups, queries

### 9. Cleanup (1 step)
Teardown (handled by cleanup trap)

## Test Flags Explained

### Core Flags

| Flag | Purpose | Duration | Cost |
|------|---------|----------|------|
| `--cassandra` | Cassandra tests | 15-20 min | ~$1/hr |
| `--clickhouse` | ClickHouse tests | 10-15 min | ~$1/hr |
| `--opensearch` | OpenSearch tests | +10-30 min | +$0.50-2/hr |
| `--spark` | Spark tests (needs Cassandra) | 25-35 min | ~$2.50/hr |
| `--all` | All database tests | 45-60 min | ~$3-4/hr |

### Utility Flags

| Flag | Purpose |
|------|---------|
| `--build` | Build AMI first (+30-45 min) |
| `--ebs` | Use EBS instead of instance store |
| `--list-steps` | List all steps and exit |
| `--start-step N` | Resume from step N |
| `--break N,M` | Pause before steps N and M |
| `--no-teardown` | Skip teardown prompt (for automation) |

## Common Test Scenarios

### Scenario Matrix

| What Changed | Recommended Flags | Duration | Why |
|--------------|------------------|----------|-----|
| Cassandra code | `--cassandra` | 15-20m | Direct impact |
| ClickHouse code | `--clickhouse` | 10-15m | Direct impact |
| Spark code | `--spark` | 25-35m | Needs Cassandra too |
| Core infrastructure | `--cassandra` | 15-20m | Fastest validation |
| Observability stack | `--cassandra` | 15-20m | Obs tested in all runs |
| Packer base scripts | `--build --cassandra` | 45-60m | Must rebuild AMI |
| Multiple subsystems | `--all` | 45-60m | Comprehensive test |

## Understanding Failure Patterns

### Pattern Recognition

**K3s Failures:**
- `step_wait_k3s_ready` timeout → K3s didn't start
- `step_verify_k3s` node issues → Agent join problems
- Check: K3s installation, security groups, tokens

**Pod Failures:**
- `CrashLoopBackOff` in diagnostics → Container crashes
- `Pending` pods → Resource or volume issues
- `ImagePullBackOff` → Image doesn't exist
- Check: ConfigMaps, resource limits, logs

**Service Failures:**
- Cassandra won't start → Check systemd, disk, Java
- ClickHouse pods failing → Check K8s logs, config
- Spark job FAILED → Check EMR stderr in diagnostics
- Check: Service-specific logs and configuration

**Observability Failures:**
- VictoriaMetrics/Logs not responding → Pod or port issues
- Grafana datasources failing → Connection or config issues
- No metrics/logs appearing → Collector problems
- Check: Pod status, port mappings, collector configs

### Failure Log Anatomy

Each failure includes:

```
FAILURE N: Step X - <step_name>
Exit code: <code>
Time: <timestamp>

--- Step Output (last 100 lines) ---
[Command output from the failing step]

--- Diagnostics ---
>> Pods NOT Running/Completed:
[Unhealthy pods]

>> CrashLoopBackOff pod logs (last 20 lines each):
[Logs from crashed pods]

>> kubectl get events --sort-by=.lastTimestamp (last 20):
[Recent K8s events]

>> df -h:
[Disk usage]
```

Use this to understand what went wrong.

## Test Duration Reference

### By Configuration

| Configuration | Steps | Duration | Notes |
|--------------|-------|----------|-------|
| Minimal (--cassandra) | ~27 | 15-20m | Fastest, basic validation |
| ClickHouse only | ~27 | 10-15m | No Cassandra overhead |
| Spark + Cassandra | ~31 | 25-35m | EMR adds time |
| Full (--all) | 35+ | 45-60m | All databases |
| With --build | +1 | +30-45m | AMI build time |
| OpenSearch adds | +3 | +10-30m | Domain creation varies |

### By Step Type

| Step Type | Typical Duration |
|-----------|-----------------|
| Build/install | 2-5 minutes |
| Cluster init | 5-10 minutes |
| K3s setup | 2-3 minutes |
| Database start | 2-5 minutes |
| Spark job | 3-8 minutes |
| Tests/validation | 1-2 minutes each |
| OpenSearch domain | 10-30 minutes (varies) |

## Cost Management

### Hourly Costs

**Base Infrastructure:**
- 3x c5d.2xlarge: $1.008/hour
- S3 storage: negligible
- Data transfer: minimal

**Add-ons:**
- EMR (m5.xlarge): +$1.50/hour
- OpenSearch: +$0.50-2.00/hour (size-dependent)

### Total Costs by Configuration

| Configuration | Hourly | 1 Hour Total |
|--------------|--------|--------------|
| --cassandra or --clickhouse | ~$1/hr | ~$1 |
| --spark | ~$2.50/hr | ~$2.50 |
| --opensearch | ~$1.50-3/hr | ~$1.50-3 |
| --all | ~$3-4/hr | ~$3-4 |

**Critical:** Always tear down when done. Costs accumulate as long as resources run.

### Tear Down Reminders

The test script reminds you but doesn't auto-teardown:
- With `--no-teardown`: Exits immediately, cluster stays
- Without: Prompts for "yes" to tear down

Always manually tear down:
```bash
easy-db-lab down --yes
```

## Resume and Recovery

### List All Steps

```bash
bin/end-to-end-test --list-steps
```

Shows all steps with numbers (1-based).

### Resume from Specific Step

```bash
# After fixing issue that caused step 15 to fail:
bin/end-to-end-test --start-step 15 --cassandra --no-teardown
```

**Requirements:**
- Cluster must still be running
- Use same flags as original run
- Previous steps assumed successful

### Use Breakpoints

```bash
# Pause before steps 10 and 15 for inspection
bin/end-to-end-test --break 10,15 --cassandra --no-teardown
```

**Use cases:**
- Inspect state before critical steps
- Set up monitoring
- Manual verification

## Best Practices

### 1. Test What You Changed

Don't default to `--all`:
- Changed one database? Test that database
- Changed core infra? Use `--cassandra` (fastest)
- Changed multiple? Use specific flags
- Only use `--all` for comprehensive validation

### 2. Use --no-teardown for Automation

When running via skills or CI:
```bash
bin/end-to-end-test --cassandra --no-teardown
```

Exit code indicates pass/fail without prompts.

### 3. Monitor Costs

- Set AWS billing alerts
- Don't leave clusters overnight
- Tear down immediately if not debugging
- Time tests during development hours

### 4. Keep Cluster on Failure

If tests fail:
- Leave cluster running
- Use `/debug-environment` to diagnose
- Fix and resume with `--start-step`
- Tear down only when done

### 5. Avoid Unnecessary Builds

Skip `--build` unless:
- Packer scripts changed
- Testing provisioning
- CI requires fresh AMI

Adds 30-45 minutes.

## Related Skills

- **`/e2e-test`** - Actually run tests (executor)
- **`/debug-environment`** - Debug test failures
- **`/easy-db-lab-expert`** - General easy-db-lab Q&A
- **`/k8-expert`** - Kubernetes Q&A

## Invocation

```bash
/e2e-test-expert
```

Then ask your question about the tests.

## Tips

1. **Ask about specific steps:** "What does step 15 do?"
2. **Request scope advice:** "What should I test if I changed X?"
3. **Interpret failures:** "Step 20 failed, what does this mean?"
4. **Estimate costs:** "How much does --spark cost?"
5. **Learn resumption:** "How do I resume from step N?"
6. **Understand infrastructure:** "What AWS resources are created?"

## Limitations

This is a **knowledge agent**, not an executor:
- Cannot run tests (use `/e2e-test`)
- Cannot debug clusters (use `/debug-environment`)
- Cannot execute commands
- Cannot modify test script

For test execution, use `/e2e-test`.
For debugging failures, use `/debug-environment`.

---

**Maintained by:** easy-db-lab project
**Version:** 1.0.0
**Type:** Knowledge Agent (Test Q&A)
**Specialty:** End-to-end test suite expertise
