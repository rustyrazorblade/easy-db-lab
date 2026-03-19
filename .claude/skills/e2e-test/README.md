# Easy-DB-Lab End-to-End Test Skill

Run comprehensive end-to-end tests with intelligent scope detection, non-interactive execution, and automatic failure debugging.

## Quick Start

```bash
# Auto-detect what to test based on code changes
/e2e-test

# Test specific database
/e2e-test --cassandra

# Full regression test
/e2e-test --all
```

## ⚠️ CRITICAL SAFETY RULE

**NEVER USE `rm` COMMANDS. EVER.**

This skill is prohibited from executing `rm` for any reason:
- ❌ No file cleanup
- ❌ No directory removal
- ❌ No deletion of "old" or "stale" files

The test script handles all cleanup via `--clean` flag. Files are never stale - clusters are fresh. Manual deletion breaks things.

**NO EXCEPTIONS.**

---

## Features

### 🎯 Intelligent Test Scope Detection

Automatically determines what to test by analyzing code changes in your branch:
- Detects Cassandra, ClickHouse, OpenSearch, or Spark changes
- Identifies core infrastructure modifications
- Suggests appropriate test flags

### 🤖 Non-Interactive Execution

Runs tests without manual intervention:
- Uses `--no-teardown` flag for non-interactive mode
- Returns exit code (0=pass, 1=fail)
- Leaves cluster running for inspection

### 🔍 Automatic Failure Debugging with Agent Teams

When tests fail:
1. **Immediately starts parallel investigation** using agent teams (if available)
2. **Team member investigates** while main agent summarizes results
3. **Read-only inspection:** Checks pods, services, logs, SSH to nodes
4. **Combined analysis:** Both agents contribute to root cause diagnosis
5. **Actionable recommendations:** Specific fixes based on findings

**Fallback:** If agent teams unavailable, uses sequential debugging via `/debug-environment`

### ⚡ Real-Time Progress Reporting

Monitors test execution and reports progress:
- Reports each step as it starts
- Reports step outcomes (pass/fail)
- Provides milestone updates
- Detects and reports failures immediately
- Total test transparency

## How It Works

### 1. Scope Detection

The skill analyzes your git changes:

```bash
# Get changed files
git diff --name-only main...HEAD

# Detect affected subsystems
# - Cassandra: src/**/cassandra/**, packer/cassandra/**
# - ClickHouse: src/**/clickhouse/**
# - OpenSearch: src/**/opensearch/**
# - Spark: spark/**, src/**/spark/**
```

### 2. Test Execution and Monitoring

Runs end-to-end test with appropriate flags and monitors progress in real-time:

```bash
bin/end-to-end-test --<detected-flags> --no-teardown
```

While running, the skill:
- **Reports each step** as it starts
- **Reports outcomes** (pass/fail)
- **Provides progress updates** every few steps
- **Immediately flags errors** as they occur
- **Estimates time remaining** based on progress

The `--no-teardown` flag:
- Skips interactive prompt
- Exits with status code
- Leaves cluster for debugging

### 3. Result Handling

**On Success:**
- Reports all tests passed
- Asks if you want to tear down cluster
- Suggests next steps (commit, PR, etc.)

**On Failure:**
- Reports which steps failed
- **Automatically debugs** using `/debug-environment`
- Provides root cause analysis
- Recommends specific fixes
- Asks whether to keep cluster or tear down

### 4. Automatic Debugging

Uses the `debug-environment` skill to:
1. Verify environment files (sshConfig, kubeconfig, state.json)
2. Test connectivity (SSH, K8s API)
3. Check service health (pods, systemd services)
4. Analyze logs and events
5. Identify root cause
6. Recommend fixes

## Usage Examples

### Example 1: Auto-Detect from Changes

**Scenario:** You modified Cassandra configuration code

```bash
# Changed files:
src/main/kotlin/com/rustyrazorblade/easydblab/commands/CassandraUpdateConfig.kt

# Invoke skill
/e2e-test

# Skill detects: Cassandra changes
# Runs: bin/end-to-end-test --cassandra --no-teardown
# Duration: ~15-20 minutes
```

### Example 2: Explicit Database Test

**Scenario:** You want to test ClickHouse specifically

```bash
/e2e-test --clickhouse

# Runs: bin/end-to-end-test --clickhouse --no-teardown
# Duration: ~10-15 minutes
```

### Example 3: Full Regression Test

**Scenario:** Major refactoring, want to test everything

```bash
/e2e-test --all

# Runs: bin/end-to-end-test --all --no-teardown
# Duration: ~45-60 minutes
# Tests: Cassandra, ClickHouse, OpenSearch, Spark
```

### Example 4: Spark Integration Test

**Scenario:** Modified Spark connector code

```bash
/e2e-test --spark

# Detects: Spark requires Cassandra
# Runs: bin/end-to-end-test --spark --cassandra --no-teardown
# Duration: ~25-35 minutes
```

## Test Scope Detection Logic

See [reference/test-scope-detection.md](reference/test-scope-detection.md) for detailed detection rules.

**Quick reference:**

| Changed Files | Flags | Duration |
|---------------|-------|----------|
| `**/cassandra/**` | `--cassandra` | 15-20 min |
| `**/clickhouse/**` | `--clickhouse` | 10-15 min |
| `**/opensearch/**` | `--opensearch` | 10-30 min |
| `spark/**` | `--spark --cassandra` | 25-35 min |
| Core infrastructure | `--cassandra` (default) | 15-20 min |
| Multiple subsystems | `--all` | 45-60 min |

## What Gets Tested

### Common Steps (All Tests)

1. ✅ Build project
2. ✅ Initialize cluster (3 nodes)
3. ✅ Setup K3s cluster
4. ✅ Verify VPC tags
5. ✅ Test MCP server /status endpoint
6. ✅ Verify S3 config backup
7. ✅ Test SSH access
8. ✅ Test exec command
9. ✅ Generate S3 traffic
10. ✅ Test observability stack (VictoriaMetrics, VictoriaLogs, Grafana)
11. ✅ Validate Grafana dashboards
12. ✅ Test logs query command
13. ✅ Test metrics/logs backup

### Cassandra Tests (`--cassandra`)

14. ✅ Setup Cassandra 5.0
15. ✅ Verify Cassandra config backup
16. ✅ Verify restore from VPC
17. ✅ Test SSH and nodetool
18. ✅ Check Sidecar API
19. ✅ Run stress test
20. ✅ Run stress K8s job
21. ✅ Cassandra start/stop cycle

### Spark Tests (`--spark`)

22. ✅ Submit Spark read-write job
23. ✅ Check Spark job status
24. ✅ Test bulk writer (sidecar)
25. ✅ Test connector writer
26. ✅ Verify Spark logs in VictoriaLogs

### ClickHouse Tests (`--clickhouse`)

27. ✅ Start ClickHouse cluster
28. ✅ Test ClickHouse connectivity
29. ✅ Test S3 tier storage policy
30. ✅ Stop ClickHouse

### OpenSearch Tests (`--opensearch`)

31. ✅ Start OpenSearch domain (10-30 min)
32. ✅ Test OpenSearch connectivity
33. ✅ Stop OpenSearch

## Failure Handling

### Automatic Debugging Flow

When tests fail:

```
1. Test fails (exit code 1)
   ↓
2. Skill detects failure
   ↓
3. If agent teams available:
   ├─ Main Agent: Summarizes test results, prepares report
   └─ Team Member: Investigates in parallel
       - kubectl get pods -A
       - kubectl logs <failed-pods>
       - SSH to nodes, check systemd services
       - Review K8s events
       - Check resources (disk, memory)
   ↓
4. Both agents analyze:
   - Main: Test execution patterns, step failures
   - Team: Live cluster state, logs, service health
   ↓
5. Combined findings:
   - What failed (test steps)
   - Why it failed (root cause from investigation)
   - Cluster state (from team member)
   - How to fix it (recommendations)
   ↓
6. User decides:
   - Apply fix and retest
   - Manual investigation
   - Tear down cluster
```

**Key benefit:** Investigation starts immediately while test results are being processed, reducing time to diagnosis.

### Team Member Investigation Scope

**What team members CAN do (read-only investigation):**
- ✅ Check Kubernetes pods: `kubectl get pods -A`
- ✅ Read pod logs: `kubectl logs <pod> -n <namespace>`
- ✅ Check services: `kubectl get svc -A`
- ✅ Review events: `kubectl get events -A`
- ✅ SSH to nodes: `ssh -F sshConfig <node>`
- ✅ Check systemd services: `systemctl status <service>`
- ✅ View service logs: `journalctl -u <service>`
- ✅ Check resources: `df -h`, `free -h`, `top`
- ✅ Inspect configs: `cat`, `grep`, read files

**What team members CANNOT do (no modifications):**
- ❌ Restart services or pods
- ❌ Modify configurations
- ❌ Delete or remove files (`rm` is prohibited)
- ❌ Apply K8s manifests
- ❌ Change cluster state
- ❌ Run test commands that modify state

This ensures safe, parallel investigation without risk of interfering with the cluster or test state.

### Common Failure Scenarios

**Pod CrashLoopBackOff:**
- Debug skill checks pod logs
- Identifies config errors, missing dependencies
- Recommends fix (update ConfigMap, fix code, etc.)

**SSH Connection Failure:**
- Debug skill verifies security groups
- Checks EC2 instance state
- Recommends security group updates

**Service Not Responding:**
- Debug skill checks systemd status
- Reviews service logs
- Identifies port conflicts, resource issues

**K8s Resource Issues:**
- Debug skill checks node resources
- Identifies insufficient CPU/memory
- Recommends instance type changes

## Cost Management

### AWS Costs

Tests create real AWS resources that cost money per hour:

| Resource | Cost (approx) |
|----------|---------------|
| 3x c5d.2xlarge nodes | ~$1.00/hour |
| EMR cluster (Spark) | ~$1.50/hour |
| OpenSearch domain | ~$0.50-2.00/hour |
| **Total** | **$1-4/hour** |

### Always Tear Down

**Critical:** Always tear down when done:

```bash
cd /Users/jhaddad/dev/easy-db-lab
easy-db-lab down --yes
```

The skill will remind you, but ultimately it's your responsibility.

### When to Keep Running

Keep cluster running only when:
- ✅ Actively debugging failures
- ✅ Manually investigating issues
- ✅ About to retest with fixes

Tear down immediately if:
- ❌ Taking a break
- ❌ Not actively working on it
- ❌ Switching to other tasks

## Integration with Other Skills

### Works With debug-environment

The e2e-test skill automatically uses debug-environment on failures:
- No need to manually invoke
- Automatic root cause analysis
- Integrated into test flow

### Complements Development Workflow

```
1. Make code changes
   ↓
2. Run unit tests: ./gradlew test
   ↓
3. Check code quality: ./gradlew detekt ktlintCheck
   ↓
4. Run e2e tests: /e2e-test (auto-detects scope)
   ↓
5. If failures: auto-debug, fix, retest
   ↓
6. If success: tear down, commit, PR
```

## Advanced Usage

### Resume from Failed Step

If you fix an issue and want to resume:

```bash
# List steps to find the failed step number
bin/end-to-end-test --list-steps

# Resume from that step
bin/end-to-end-test --start-step 15 --cassandra --no-teardown
```

### Use Breakpoints

Pause before specific steps for inspection:

```bash
bin/end-to-end-test --break 10,15 --cassandra --no-teardown
```

### Build AMI First

For packer changes, rebuild the AMI:

```bash
/e2e-test --build --cassandra
# Adds 30-45 minutes for AMI build
```

### Custom Instance Type

Override default instance type:

```bash
EASY_DB_LAB_INSTANCE_TYPE=c5d.4xlarge /e2e-test --cassandra
```

## Troubleshooting

### Skill Doesn't Auto-Detect Changes

**Issue:** On main branch or no git changes

**Solution:** Explicitly specify what to test:
```bash
/e2e-test --cassandra
```

### Tests Take Too Long

**Issue:** `--all` tests take 45-60 minutes

**Solution:** Test only what changed:
```bash
# Let skill auto-detect
/e2e-test

# Or specify minimal scope
/e2e-test --cassandra
```

### Auto-Debug Not Working

**Issue:** Debug-environment skill not invoked on failure

**Solution:**
- Ensure you're using the e2e-test skill (not running script manually)
- Check that --no-teardown flag is being used
- Manually invoke: `/debug-environment`

### Cluster Still Running After Tests

**Issue:** Cluster not torn down, AWS charges accumulating

**Solution:**
```bash
cd /Users/jhaddad/dev/easy-db-lab
easy-db-lab down --yes
```

## Files Created

When tests run, these files are created in the project root:

- `state.json` - Cluster metadata
- `kubeconfig` - K8s cluster config
- `sshConfig` - SSH configuration for nodes
- `env.sh` - Environment variables
- `cassandra.patch.yaml` - Cassandra config overrides (if --cassandra)

These are needed for debugging and manual investigation.

## See Also

- **Script Source:** `bin/end-to-end-test`
- **Debug Skill:** `.claude/skills/debug-environment/`
- **Test Scope Detection:** [reference/test-scope-detection.md](reference/test-scope-detection.md)
- **Project Documentation:** `docs/`

## Quick Reference

### Invoke Skill

```bash
/e2e-test                    # Auto-detect scope
/e2e-test --cassandra        # Test Cassandra
/e2e-test --clickhouse       # Test ClickHouse
/e2e-test --opensearch       # Test OpenSearch
/e2e-test --spark            # Test Spark + Cassandra
/e2e-test --all              # Test everything
```

### After Tests

```bash
# Tear down cluster
easy-db-lab down --yes

# Or investigate manually
ssh -F sshConfig control
kubectl get pods -A
easy-db-lab logs query
```

### Resume from Failure

```bash
# Fix code, then resume
bin/end-to-end-test --start-step <N> --<flags> --no-teardown
```

---

**Maintained by:** easy-db-lab project
**Last Updated:** 2026-03-19
**Skill Version:** 2.0.0 (with non-interactive mode and auto-debug)
