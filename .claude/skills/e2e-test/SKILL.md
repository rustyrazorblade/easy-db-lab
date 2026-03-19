---
name: e2e-test
description: Run end-to-end tests for easy-db-lab. Automatically detects what to test based on code changes in the current branch, or allows manual specification of test scope. Use when validating changes, running CI tests, or verifying full system functionality. Runs in background, reports results, and automatically debugs failures.
allowed-tools: Bash, Read, Grep, Glob, Task
argument-hint: [--cassandra|--clickhouse|--opensearch|--spark|--all]
disable-model-invocation: false
user-invocable: true
---

# Easy-DB-Lab End-to-End Test Runner

Run comprehensive end-to-end tests for easy-db-lab with intelligent test scope detection.

## Arguments

User-provided arguments: $ARGUMENTS

If no arguments provided, detect what to test based on code changes in current branch.

## Current Branch Information

Current branch: !`git branch --show-current`

Recent commits on this branch: !`git log --oneline main..HEAD 2>/dev/null | head -5 || echo "On main branch or no commits yet"`

## Step 1: Check for Existing Test Cluster

**CRITICAL:** Check if an e2e test cluster is already running before starting new tests.

```bash
# Check if state.json exists in current directory
if [ -f state.json ]; then
    echo "Found existing cluster (state.json exists)"

    # Check if it's from e2e tests (name contains "test")
    cluster_name=$(jq -r '.name' state.json)
    if [[ "$cluster_name" == *"test"* ]]; then
        echo "E2E test cluster already exists: $cluster_name"
        echo "CLUSTER_EXISTS=true"
    fi
fi
```

**If cluster exists:**

Do NOT re-run the full test. Instead:

1. **Report cluster status:**
   ```bash
   easy-db-lab status
   ```

2. **Check if tests already completed:**
   - Look for test completion markers
   - Check if last invocation was successful

3. **Options for user:**
   - **Investigate existing cluster:** Use `/debug-environment`
   - **Resume from step:** If tests failed mid-run
   - **Tear down and retest:** `easy-db-lab down --yes` then re-invoke skill
   - **Keep for debugging:** Leave cluster as-is

4. **Do NOT start new cluster** - This would create duplicate infrastructure and waste resources

**Message to user:**
```
An e2e test cluster is already running in this directory.

Cluster: <name>
Status: <from status command>

Options:
1. Debug this cluster: /debug-environment
2. Resume from specific step: bin/end-to-end-test --start-step <N> --<flags> --no-teardown
3. Tear down and retest: easy-db-lab down --yes, then re-invoke /e2e-test
4. Keep for manual investigation

What would you like to do?
```

**Exit this skill** if cluster exists - do not proceed to Step 2.

## Step 2: Determine Test Scope

**Only if NO cluster exists** - determine what to test.

If the user provided explicit flags (--cassandra, --clickhouse, etc.), use those.

If no flags provided, analyze code changes to determine what to test:

### Changed Files Analysis

Files changed in current branch (excluding main): !`git diff --name-only main...HEAD 2>/dev/null | head -30 || echo "No changes from main"`

### Detection Rules

Based on changed files, determine which test flags to enable:

**Cassandra Testing (`--cassandra`)** - Enable if changes affect:
- `src/main/kotlin/**/cassandra/**`
- `src/main/kotlin/**/commands/Cassandra*`
- `packer/cassandra/**`
- `src/main/resources/**/cassandra/**`
- Any file with "cassandra" in the path (case-insensitive)
- Configuration files related to Cassandra

**ClickHouse Testing (`--clickhouse`)** - Enable if changes affect:
- `src/main/kotlin/**/clickhouse/**`
- `src/main/kotlin/**/commands/Clickhouse*`
- `src/main/resources/**/clickhouse/**`
- Any file with "clickhouse" in the path (case-insensitive)
- ClickHouse configuration or manifest builders

**OpenSearch Testing (`--opensearch`)** - Enable if changes affect:
- `src/main/kotlin/**/opensearch/**`
- `src/main/kotlin/**/services/aws/OpenSearch*`
- Any file with "opensearch" in the path (case-insensitive)

**Spark Testing (`--spark`)** - Enable if changes affect:
- `spark/**` directory
- `src/main/kotlin/**/spark/**`
- `src/main/kotlin/**/commands/Spark*`
- EMR-related code
- Any file with "spark" or "emr" in the path (case-insensitive)

**Core Infrastructure Testing** - Enable Cassandra by default if changes affect:
- `src/main/kotlin/**/configuration/**` (K8s manifests)
- `src/main/kotlin/**/kubernetes/**`
- `src/main/kotlin/**/providers/**` (AWS providers)
- `src/main/kotlin/**/commands/Init*` or cluster initialization
- `packer/base/**` (base provisioning)
- Observability stack (VictoriaMetrics, Grafana, etc.)
- MCP server code

**If on main branch or no detectable changes:**
- Run basic test: `--cassandra` (fastest, covers core functionality)

**If many subsystems changed:**
- Recommend `--all` to test everything

## Step 3: Use Non-Interactive Mode

**IMPORTANT:** This skill runs tests in non-interactive mode using `--no-teardown` flag.

Benefits:
- Tests run to completion without prompts
- Exit code indicates pass (0) or fail (1)
- Cluster remains running for debugging on failure
- Can run in background/subagent

The script will:
1. Run all test steps
2. Report results
3. Exit with appropriate status code
4. **NOT** tear down the cluster automatically

This allows the skill to analyze failures and invoke debugging automatically.

## Step 4: No Build Preparation Needed

The end-to-end test script handles everything automatically:
- **Builds the project** (line 1502: `./gradlew shadowJar installDist`)
- **Cleans up old files** via `init --clean` flag
- **Sets up Docker services** (Redis, OTel collector)

You don't need to:
- ❌ Check if builds are needed
- ❌ Clean build directories
- ❌ Remove old environment files
- ❌ Manually build anything

The script does all of this. Just run it.

## Step 5: Run End-to-End Tests

Execute the test with determined flags **using `--no-teardown` for non-interactive mode**:

```bash
# IMPORTANT: Always add --no-teardown when running via this skill
# This allows the skill to handle failures and invoke debugging

# Example invocations:

# Basic Cassandra test (non-interactive)
bin/end-to-end-test --cassandra --no-teardown

# Full test suite (non-interactive)
bin/end-to-end-test --all --no-teardown

# Spark + Cassandra (non-interactive)
bin/end-to-end-test --spark --cassandra --no-teardown

# ClickHouse only (non-interactive)
bin/end-to-end-test --clickhouse --no-teardown

# With custom instance type
EASY_DB_LAB_INSTANCE_TYPE=c5d.4xlarge bin/end-to-end-test --cassandra --no-teardown

# Build AMI image first (slow - only when needed)
bin/end-to-end-test --build --cassandra --no-teardown
```

**Note:** The `--no-teardown` flag:
- Skips the interactive teardown prompt
- Exits with status 0 (pass) or 1 (fail)
- Leaves cluster running for debugging
- Enables this skill to automatically debug failures

### Important Environment Variables

- `EASY_DB_LAB_INSTANCE_TYPE` - Override default instance type (default: c5d.2xlarge)
- `AWS_PROFILE` - AWS profile to use (default: sandbox-admin in script)
- `EASY_DB_LAB_E2E_AUTO_TEARDOWN` - If available, enables non-interactive teardown

### Test Execution Notes

1. **Test Duration:**
   - `--cassandra` only: ~15-20 minutes
   - `--clickhouse` only: ~10-15 minutes
   - `--spark --cassandra`: ~25-35 minutes
   - `--opensearch`: +10-30 minutes (OpenSearch domain creation is slow)
   - `--all`: ~45-60 minutes
   - `--build`: +30-45 minutes (packer AMI build)

2. **Test Workspace:**
   - Tests run in the current directory (project root)
   - Creates `state.json`, `kubeconfig`, `sshConfig` in project root
   - Logs are captured in temporary files

3. **Cleanup:**
   - Script prompts for confirmation before tearing down
   - Type "yes" to tear down immediately
   - Press Ctrl-C to exit and keep cluster for debugging

4. **Cost Considerations:**
   - Tests spin up real AWS resources (EC2, EMR, OpenSearch)
   - Costs accumulate per hour for running resources
   - Always tear down when done to avoid unnecessary charges

## Step 6: Monitor Test Progress

The test script outputs progress for each step. Key indicators:

**Success Indicators:**
- Each step completes without "FAILED" messages
- K8s pods reach "Running" state
- Services respond to health checks
- Final summary shows "All tests passed successfully"

**Failure Indicators:**
- "FAILED: Step N - <name>" messages
- Pods stuck in "Pending", "CrashLoopBackOff", or "Error"
- Services not responding
- Final summary shows "N step(s) FAILED"

**Common Issues:**
- AWS rate limits (slow down, retry)
- Instance capacity issues (try different region/instance type)
- Timeout waiting for resources (may need longer waits)
- Build failures (check code quality first with `./gradlew detekt ktlintCheck`)

## Step 7: Handle Test Results and Auto-Debug

### If All Tests Pass

Output will show:
```
==========================================
=== All tests passed successfully ===
==========================================
=== --no-teardown specified: skipping teardown ===
Cluster remains running for inspection
```

**Action:** Report success to user and ask if they want to tear down the cluster.

### If Tests Fail

Output will show:
```
==========================================
=== N step(s) FAILED ===
==========================================
=== --no-teardown specified: skipping teardown ===
Cluster remains running for inspection
```

Exit code will be 1.

The failure log includes:
- Step output (last 100 lines)
- Pods not in Running/Completed state
- CrashLoopBackOff pod logs
- Kubernetes events
- Disk usage

**AUTOMATIC DEBUGGING:**

When tests fail, **automatically invoke the debug-environment skill** to diagnose:

```
I'll now automatically debug the failed tests using the debug-environment skill.
```

Then use the Task tool to invoke debug-environment:

```
Use Task tool with:
  subagent_type: "general-purpose"
  description: "Debug failed e2e tests"
  prompt: "Use the debug-environment skill to investigate the test failures. The e2e tests failed with N step(s) failing. The cluster is still running in the current directory. Please diagnose what went wrong."
```

This will:
1. Launch the debug-environment skill in a subagent
2. Analyze the environment (state.json, kubeconfig, sshConfig)
3. Check SSH and K8s connectivity
4. Review failed pods and services
5. Examine logs and events
6. Identify root cause
7. Recommend fixes

After debugging completes, present findings to the user with:
- Summary of failures
- Root cause analysis
- Recommended fixes
- Whether to tear down or keep cluster for manual investigation

### Resume from Failed Step

If a step fails and you want to continue from that point:

```bash
# List all steps to find the number
bin/end-to-end-test --list-steps

# Resume from step 15 (after fixing the issue)
bin/end-to-end-test --start-step 15 --cassandra
```

### Use Breakpoints for Investigation

Pause before specific steps to inspect state:

```bash
# Pause before step 10 and step 15
bin/end-to-end-test --break 10,15 --cassandra

# The script will pause and wait for Enter before continuing
```

## Step 8: Provide Results Summary and Next Steps

After test completion (and debugging if failed), provide a comprehensive summary:

**Summary Format:**

```
End-to-End Test Results
=======================

Branch: <branch-name>
Test Scope: <flags used>
Duration: <total time>
Result: <PASS/FAIL>

Steps Executed: <total>
Steps Passed: <count>
Steps Failed: <count>

<If failures:>
Failed Steps:
  - Step N: <step-name> - <brief reason if known>
  - Step M: <step-name> - <brief reason if known>

Root Cause (from auto-debug):
<Summary of findings from debug-environment skill>

Recommended Fixes:
1. <Fix from debug analysis>
2. <Fix from debug analysis>

Environment:
- Cluster: <name>
- Region: <region>
- Instance Type: <type>
- Test Directory: <path>
- Cluster Status: RUNNING (not torn down)

Next Steps:
<Recommendations based on results>
```

### Next Steps - Success

If all tests passed:

1. **Tear down cluster** to avoid AWS charges:
   ```bash
   cd /Users/jhaddad/dev/easy-db-lab
   source env.sh
   easy-db-lab down --yes
   ```

2. **Commit changes** if on a feature branch

3. **Create pull request** if ready

### Next Steps - Failure

If tests failed:

1. **Review debug findings** - The debug-environment skill has identified issues

2. **Choose action:**
   - **Fix and retest:** Apply recommended fixes, rebuild, and rerun e2e tests
   - **Manual investigation:** Keep cluster running and investigate manually
   - **Tear down and retry:** If transient issue, tear down and rerun

3. **If fixing code:**
   ```bash
   # Make fixes based on debug recommendations
   # Rebuild
   ./gradlew clean shadowJar

   # Resume from failed step (if possible)
   bin/end-to-end-test --start-step <N> --<database> --no-teardown

   # Or run full test again
   bin/end-to-end-test --<database> --no-teardown
   ```

4. **If manual investigation needed:**
   ```bash
   # SSH to nodes
   ssh -F sshConfig control
   ssh -F sshConfig db-0

   # Check K8s resources
   export KUBECONFIG=$(pwd)/kubeconfig
   kubectl get pods -A
   kubectl logs <pod> -n <namespace>

   # Use easy-db-lab commands
   easy-db-lab status
   easy-db-lab logs query --since 1h
   ```

5. **Always tear down when done:**
   ```bash
   easy-db-lab down --yes
   ```

### Cost Warning

**IMPORTANT:** The cluster continues running and accumulating AWS charges until torn down.

If not actively investigating:
- Tear down immediately to stop charges
- Tests can always be rerun later

Current approximate costs (per hour):
- 3x c5d.2xlarge instances: ~$1.00/hour
- EMR cluster (if Spark enabled): ~$1.50/hour
- OpenSearch domain (if enabled): ~$0.50-2.00/hour
- Total: ~$1-4/hour depending on configuration

## Advanced Usage

### Testing Specific Changes

```bash
# Test only what changed in your branch
# (detected automatically)
/e2e-test

# Override detection - test specific systems
/e2e-test --cassandra --clickhouse

# Full regression test
/e2e-test --all

# Build new AMI first (for packer changes)
/e2e-test --build --cassandra
```

### Development Workflow

1. **Make code changes** on feature branch
2. **Run local tests** - `./gradlew test`
3. **Check code quality** - `./gradlew detekt ktlintCheck`
4. **Run e2e test** - `/e2e-test` (auto-detects scope)
5. **Fix failures** - Use `/debug-environment` if needed
6. **Commit changes** - After tests pass

### CI/CD Integration

For CI/CD pipelines, the test script should support:
```bash
# Non-interactive mode (when available)
EASY_DB_LAB_E2E_AUTO_TEARDOWN=1 bin/end-to-end-test --cassandra

# Or use expect/timeout for current version
echo "yes" | bin/end-to-end-test --cassandra
```

## Reference Materials

- **Test script source:** `bin/end-to-end-test`
- **Step list:** Run `bin/end-to-end-test --list-steps`
- **Debugging:** Use `/debug-environment` skill for investigation
- **Project docs:** `docs/` directory
- **Architecture:** Project `CLAUDE.md` files

## Important Notes

1. **Always tear down** - AWS resources cost money per hour
2. **Tests are comprehensive** - Full cluster deployment and validation
3. **Fresh clusters** - Each test creates new infrastructure
4. **Branch detection** - Tests what you changed automatically
5. **Resume capability** - Can resume from any step after failures
6. **Breakpoints** - Can pause for manual inspection

## Your Task

Based on the user's request:

1. **Detect test scope** - From arguments or git changes
2. **Verify prerequisites** - Build if needed
3. **Run tests** - With appropriate flags
4. **Monitor progress** - Report key milestones
5. **Handle results** - Success or failure guidance
6. **Provide summary** - What was tested, results, next steps

Be clear about:
- What will be tested and why
- Expected duration
- AWS costs implications
- How to handle the teardown prompt
- What to do if tests fail
