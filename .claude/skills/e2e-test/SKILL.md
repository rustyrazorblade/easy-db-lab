---
name: e2e-test
description: Run end-to-end tests for easy-db-lab. Automatically detects what to test based on code changes in the current branch, or allows manual specification of test scope. Use when validating changes, running CI tests, or verifying full system functionality. Runs in background, reports results, and automatically debugs failures.
allowed-tools: Bash, Read, Grep, Glob, Task
argument-hint: [--cassandra|--clickhouse|--opensearch|--spark|--all] [--clean] [--start-step <N>]
disable-model-invocation: false
user-invocable: true
---

# Easy-DB-Lab End-to-End Test Runner

Run comprehensive end-to-end tests for easy-db-lab with intelligent test scope detection.

## Arguments

User-provided arguments: $ARGUMENTS

If no arguments provided, detect what to test based on code changes in current branch.

If `--clean` is present in arguments, pass `--clean` to `bin/end-to-end-test`.

If `--start-step <N>` is present in arguments, pass it through to `bin/end-to-end-test`. This skips steps 1 through N-1 and starts execution at step N. Useful for resuming after a failure or skipping infrastructure setup when a cluster is already running. When `--start-step` is provided, skip the cluster existence check (Step 1) — a running cluster is assumed.

## Current Branch Information

Current branch: !`git branch --show-current`

Recent commits on this branch: !`git log --oneline main..HEAD 2>/dev/null | head -5 || echo "On main branch or no commits yet"`

## Step 1: Check for Existing Test Cluster

**CRITICAL:** Check if an e2e test cluster is already running before starting new tests.

**SKIP THIS STEP if `--clean` was provided.** The `--clean` flag tears down any existing environment, so there is no risk of duplicate infrastructure and no need to check status.

### Infrastructure Status Check

Only perform this check if `--clean` was NOT provided.

**IMPORTANT:** Use `easy-db-lab status` as the sole source of truth for whether infrastructure is running. Do NOT check for `state.json` — it is unreliable and may exist even after a cluster has been torn down.

```bash
# Always check actual infrastructure status — never gate on state.json
status_output=$(easy-db-lab status 2>&1)

if echo "$status_output" | grep -q "Infrastructure: UP"; then
    echo "Infrastructure is UP — active cluster detected"
    echo "CLUSTER_EXISTS=true"
else
    echo "Infrastructure is not UP — safe to proceed with new test"
    echo "CLUSTER_EXISTS=false"
fi
```

**Detection Logic:**
- `easy-db-lab status` shows `Infrastructure: UP` → Active cluster (do NOT proceed)
- `easy-db-lab status` shows `Infrastructure: DOWN` → Cluster is down (proceed with tests)
- `easy-db-lab status` errors or shows no cluster → No cluster (proceed with tests)

**If active cluster exists (CLUSTER_EXISTS=true):**

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

## Step 4: No Build or Cleanup Needed

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

## ⚠️ ABSOLUTE RULE: NEVER USE `rm`

**CRITICAL SAFETY RULE:**

🚫 **NEVER EXECUTE `rm` COMMANDS. EVER.**

This is an **ABSOLUTE RULE** with no exceptions:
- ❌ Do NOT use `rm` to clean up files
- ❌ Do NOT use `rm -rf` for anything
- ❌ Do NOT remove state.json, kubeconfig, sshConfig, or any files
- ❌ Do NOT clean up "old" or "stale" files
- ❌ Do NOT delete anything

**Why:**
- The test script handles cleanup via `--clean` flag
- Files are never "stale" - clusters are fresh
- Manual file deletion can break active clusters
- User may need files for debugging

**If you think files need cleanup:**
- They don't
- The script handles it
- Trust the --clean flag

**NO EXCEPTIONS. NEVER USE `rm`.**

## Step 5: Run Test Directly as Background Bash

**CRITICAL: Do NOT use the Agent tool to delegate test execution.**

Delegating to a subagent traps all test output inside the subagent until the test completes (20+ minutes), making real-time progress reporting impossible. Always run the test directly in the main agent using the Bash tool with `run_in_background: true`.

### How to Run

Use the Bash tool with `run_in_background: true`:

```bash
bin/end-to-end-test --<flags> --no-teardown 2>&1
```

The Bash tool result will include an `output_file` path. **Save this path** — you will use it to monitor progress in Step 6.

**IMMEDIATELY after launching the background Bash:**
1. Tell the user the test has started and which flags are being used
2. Record the `output_file` path from the Bash tool result
3. Record `last_reported_step = 0`
4. Begin monitoring (see Step 6)

### Test Command Format

Always use `--no-teardown` flag:

```bash
# Basic Cassandra test
bin/end-to-end-test --cassandra --no-teardown

# Full test suite
bin/end-to-end-test --all --no-teardown

# Spark + Cassandra
bin/end-to-end-test --spark --cassandra --no-teardown

# ClickHouse only
bin/end-to-end-test --clickhouse --no-teardown

# With custom instance type
EASY_DB_LAB_INSTANCE_TYPE=c5d.4xlarge bin/end-to-end-test --cassandra --no-teardown

# Build AMI first (slow)
bin/end-to-end-test --build --cassandra --no-teardown

# Clean old environment files before starting
bin/end-to-end-test --clean --cassandra --no-teardown

# Skip infra setup, start from step 21 (e.g. Spark steps on existing cluster)
bin/end-to-end-test --start-step 21 --spark --cassandra --no-teardown
```

**Using `--start-step`:**
- Skips all steps before N (1-based, matches `--list-steps` output)
- Requires a cluster already running — skips infra provisioning
- Use `bin/end-to-end-test --list-steps` to see step numbers
- When `--start-step` is provided, skip the cluster existence check

**Why `--no-teardown`?**
- Exits with status code (0=pass, 1=fail)
- Leaves cluster running for investigation
- Enables automatic failure debugging

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

## Step 6: Monitor Progress via Output File

The test runs as a background Bash. You push updates to the user by running a short-lived poller in background, re-launching it on every notification.

### The Poller

After launching the test, immediately launch this as a background Bash:
```bash
sleep 10 && grep "^Step\|^FAILED\|step(s) FAILED\|All tests passed" <output_file>
```

### The Absolute Rule

**In EVERY response you send — whether triggered by a poll notification OR a user message — your FIRST tool call MUST be to re-launch the poller** (unless the test has already finished):

```bash
sleep 10 && grep "^Step\|^FAILED\|step(s) FAILED\|All tests passed" <output_file>
```

Do this BEFORE reading results, BEFORE responding to the user, BEFORE anything else. This is what keeps the loop alive. If you skip this even once, updates stop.

### After Re-launching

Read the output and output a **full cumulative progress report** showing every step seen so far. This lets the user see the full picture without scrolling back:

```
Progress: Step N/TOTAL

✅ Step 1/TOTAL: <step-name>
✅ Step 2/TOTAL: <step-name>
⏭️ Step 3/TOTAL: <step-name> - Skipped
❌ Step 4/TOTAL: <step-name> - FAILED
✅ Step 5/TOTAL: <step-name>
🔄 Step 6/TOTAL: <step-name> - Running...
```

Use:
- ✅ for completed steps
- ❌ for failed steps
- ⏭️ for skipped steps
- 🔄 for the currently-running step

### Stop Condition

Stop re-launching when the output contains `All tests passed` or `step(s) FAILED`.

**Example Monitoring Flow:**

```
[System notifies: new output available]
→ Check output immediately
→ Find: Steps 6, 7, 8 completed since last check
→ Report to user (full cumulative list):
   "Progress: Step 8/40

    ✅ Step 1/40: Build project
    ✅ Step 2/40: Check version
    ✅ Step 3/40: Build packer image
    ✅ Step 4/40: Set IAM policies
    ✅ Step 5/40: Initialize cluster
    ✅ Step 6/40: Setup kubectl
    ✅ Step 7/40: Wait for K3s
    ✅ Step 8/40: Verify cluster
    🔄 Step 9/40: Verify VPC tags - Running..."

[System notifies: new output available]
→ Check output immediately
→ Find: Step 9 complete, Step 10 started
→ Report to user (full cumulative list):
   "Progress: Step 9/40

    ✅ Step 1/40: Build project
    ...
    ✅ Step 9/40: Verify VPC tags
    🔄 Step 10/40: List hosts - Running..."
```

**Concrete Execution Timeline:**

```
Message 1: [You start the test]
  "Starting e2e tests with --spark. Running in background.
   Will report each step as it completes."

→ [System notification arrives: new output]

Message 2: [Full progress report after steps 1-2]
  "Progress: Step 2/40

   ✅ Step 1/40: Build project
   ✅ Step 2/40: Check version
   🔄 Step 3/40: Build packer image - Running..."

→ [System notification arrives: new output]

Message 3: [Full progress report after steps 3-5]
  "Progress: Step 5/40

   ✅ Step 1/40: Build project
   ✅ Step 2/40: Check version
   ⏭️ Step 3/40: Build packer image - Skipped
   ✅ Step 4/40: Set IAM policies
   ✅ Step 5/40: Initialize cluster
   🔄 Step 6/40: Setup kubectl - Running..."

...and so on until test completes
```

**The pattern is simple:**
- System notifies → You respond immediately
- Every notification = one message to user with all new steps
- No gaps, no delays, no missed steps

### Reporting Format

**1. Initial Status (when test starts):**
```
Starting end-to-end tests with --spark flag
Test scope: Spark + Cassandra + Core infrastructure
Estimated duration: 25-35 minutes
Running in background, will report each step as it progresses
```

**2. After Every Step — Full Cumulative Report:**

After each notification, output the complete list of all steps seen so far:
```
Progress: Step 5/40

✅ Step 1/40: Build project
✅ Step 2/40: Check version
⏭️ Step 3/40: Build packer image - Skipped
✅ Step 4/40: Set IAM policies
✅ Step 5/40: Initialize cluster
🔄 Step 6/40: Setup SSH config - Running...
```

This lets the user see all progress at a glance without scrolling back.

**3. Failures (shown inline in the cumulative list):**
```
Progress: Step 23/40

✅ Step 1/40: Build project
...
✅ Step 22/40: Deploy VictoriaMetrics
❌ Step 23/40: Test VictoriaMetrics - FAILED: pod not responding on port 8428
🔄 Step 24/40: ... - Running...

Checking logs for details...
```

**4. Long-Running Steps:**
If a step takes >5 minutes, provide interim update with the same cumulative format, marking the running step with elapsed time:
```
🔄 Step 15/40: Wait for Cassandra - Running (5 minutes elapsed)
```

### Self-Check: Have You Missed Reporting Steps?

**Before sending ANY message to the user, ask yourself:**

1. "When did I last check the output file?"
2. "What was the last step number I reported?"
3. "Are there system notifications I haven't responded to?"

**If you realize you've missed steps:**

1. **IMMEDIATELY check the output file**
2. **Find ALL steps since your last report**
3. **Report them ALL in your next message**
4. **Do NOT make excuses or explain why you missed them**
5. **Just catch up and continue monitoring**

**Example Recovery:**
```
[You realize you last reported step 5, but output shows steps 6-10 exist]

Your next message (full cumulative list):
"Progress: Step 10/40

 ✅ Step 1/40: Build project
 ✅ Step 2/40: Check version
 ...
 ✅ Step 5/40: Initialize cluster
 ✅ Step 6/40: Setup kubectl
 ✅ Step 7/40: Wait for K3s
 ✅ Step 8/40: Verify cluster
 ✅ Step 9/40: Verify VPC tags
 ✅ Step 10/40: List hosts
 🔄 Step 11/40: ... - Running..."
```

**Prevention: After sending each message, immediately look for the NEXT system notification.**

### Output Parsing Patterns

Look for these patterns in the output:
- `Step N/TOTAL: <name>` → New step starting (report immediately)
- Line starting with `===` followed by success message → Step complete
- `FAILED: Step N` → Step failed (report immediately)
- `==========================================` → Step boundary
- `All tests passed successfully` → All done (success)
- `N step(s) FAILED` → Test run complete (failure)

**ABSOLUTE RULE: Report EVERY step. No exceptions. No batching. No delays.**

### Common Monitoring Failures and How to Avoid Them

**Failure Pattern 1: "I started the test and then did nothing"**
- ❌ Wrong: Start test, send one message, wait for user to ask
- ✅ Right: Start test, send status, watch for notifications, respond to EACH one

**Failure Pattern 2: "I only checked when the user asked"**
- ❌ Wrong: Passive waiting, only react to user questions
- ✅ Right: Active monitoring, system notifications trigger immediate action

**Failure Pattern 3: "I batched multiple steps together"**
- ❌ Wrong: Wait for several steps to complete, report them all at once
- ✅ Right: Report steps as soon as each notification arrives

**Failure Pattern 4: "I didn't realize the test was progressing"**
- ❌ Wrong: Assume silence means nothing is happening
- ✅ Right: System notifications tell you when output changes - respond immediately

**Failure Pattern 5: "I reported some steps but skipped others"**
- ❌ Wrong: Only report "important" steps or milestones
- ✅ Right: EVERY step gets reported, no matter how small

**How to Verify You're Monitoring Correctly:**

After each message you send, ask yourself:
1. "Did I just respond to a system notification?" → Should be YES
2. "Did I report all steps since my last message?" → Should be YES
3. "Am I actively waiting for the next notification?" → Should be YES

If any answer is NO, you are failing to monitor correctly.

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

**AUTO-DEBUG on failure:**

Invoke the `/debug-environment` skill to investigate the live cluster. Report:
- Which steps failed and their error output
- Root cause from debug findings
- Recommended fixes

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

All Steps:
  ✅ Step 1/N: <step-name>
  ✅ Step 2/N: <step-name>
  ❌ Step 3/N: <step-name> - <brief reason if known>
  ✅ Step 4/N: <step-name>
  ... (all steps, every one)

Steps Executed: <total>
Steps Passed: <count>
Steps Failed: <count>

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

# Clean old environment files before starting
/e2e-test --clean --cassandra
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
