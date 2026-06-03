---
name: run-test
description: Run an integration test against a real AWS cluster. Use when the user says anything like "test it in AWS", "fire up a cluster and test", "run the presto test", "test on a real cluster", or names a specific test. Handles provisioning, surfaces the tail command, monitors progress, and reports results. Does NOT tear down on failure — leaves the cluster running for debugging.
allowed-tools: Bash, Read, AskUserQuestion
argument-hint: [test-name] [--provision]
user-invocable: true
---

# Run Integration Test

Arguments: $ARGUMENTS

## Step 1: Determine Test Name

If a test name was provided in arguments, use it.

Otherwise, list available tests and ask:

```bash
ls bin/tests/
```

Use AskUserQuestion to ask which test to run.

## Step 2: Determine Whether to Provision

If the user said "fire up a cluster", "provision", "fresh cluster", "new cluster", or `-p` was in arguments — use `-p`.

If there might already be a cluster running, check:

```bash
bin/easy-db-lab status 2>&1 | head -5
```

- If infrastructure is UP: do NOT pass `-p`. Tell the user a cluster is already up and you're running the test against it.
- If infrastructure is DOWN or no cluster: use `-p` to provision.

Do NOT ask the user about provisioning if context makes it obvious. Only ask if genuinely ambiguous.

## Step 3: Run the Test

First, create a fresh cluster directory under `clusters/` in the project root and resolve the absolute path to `bin/test`:

```bash
PROJECT_DIR=$(pwd)
TEST_DIR="${PROJECT_DIR}/clusters/$(date +%Y%m%d-%H%M%S)-<test-name>"
mkdir -p "$TEST_DIR"
BIN_TEST="${PROJECT_DIR}/bin/test"
echo "Cluster dir: $TEST_DIR"
```

Run the test from that directory so no stale project-root artifacts (rendered workload dirs, old configs, etc.) bleed in:

```bash
cd "$TEST_DIR" && "$BIN_TEST" [-p] <test-name>
```

Run in the background. The log file will be written inside the cluster dir under `logs/`.

Include `-p` only if determined in Step 2.

**Never pass `-t` (teardown).** The cluster must stay up after a failure so the user can debug. The user will explicitly say "shut it down" when they're done.

Immediately after launching, read the first ~20 lines of output to find the `Follow:` line:

```bash
sleep 2 && head -20 <output_file>
```

**Surface the `Follow:` line to the user right away** so they can tail the log in another terminal. Example:

```
Test started. To follow along:
  tail -f -n 100 /path/to/logs/test-presto-20260519-143201.log
```

## Step 4: Monitor Progress

Poll the log file for the `step()` banners and final result. Each banner looks like:

```
══════════════════════════════════════════════
  <step name>
══════════════════════════════════════════════
```

Launch a background poller after surfacing the Follow line:

```bash
sleep 15 && grep -E "══|PASSED|FAILED|error" <log_file> | tail -20
```

Re-launch the poller on each notification. Report each new step to the user as it appears.

Stop polling when you see `PASSED` or an unrecovered error/exit.

## Step 5: Report Results

**On success:** Report which steps completed and that the test passed. Remind the user to shut down the cluster when done:

```
Test passed. Cluster is still running — cd <cluster-dir> && <project>/bin/easy-db-lab down --yes when you're done.
```

**On failure:** Report which step failed and what the output showed. Do NOT tear down. Tell the user:

```
Test failed at: <step name>
The cluster is still running for debugging.
Cluster dir: <cluster-dir>
When you're done: cd <cluster-dir> && <project>/bin/easy-db-lab down --yes
```

## Investigating Failures

When a test fails, the cluster stays up. All cluster artifacts (`kubeconfig`, `sshConfig`, workload dirs) are in the cluster dir created in Step 3. Surface that path to the user.

**kubectl** — the kubeconfig is written to `<cluster-dir>/kubeconfig`:
```bash
KUBECONFIG=<cluster-dir>/kubeconfig kubectl get pods -A
KUBECONFIG=<cluster-dir>/kubeconfig kubectl describe pod -n <ns> <pod>
KUBECONFIG=<cluster-dir>/kubeconfig kubectl logs -n <ns> <pod>
```

**SSH** — an SSH config is written to `<cluster-dir>/sshConfig`:
```bash
ssh -F <cluster-dir>/sshConfig control0
ssh -F <cluster-dir>/sshConfig db0
ssh -F <cluster-dir>/sshConfig app0
```

**Node-level logs via crictl** (when kubectl logs returns 502 or the node is NotReady):
```bash
ssh -F sshConfig db0 "sudo crictl logs \$(sudo crictl ps -a --name <container-name> -q | head -1) 2>&1 | tail -40"
```

**K3s agent status on a worker node:**
```bash
ssh -F sshConfig db0 "sudo systemctl status k3s-agent --no-pager -l | tail -20"
```

When a node is `NotReady`, check whether its Cilium agent can reach the API server — a common bootstrap issue on fresh clusters.

## Notes

- **Always use `bin/easy-db-lab`**, never bare `easy-db-lab` — there may be other versions in PATH.
- **Never tear down automatically.** The user debugs failures on the live cluster.
- **Shutting down:** When the user says "shut it down" / "shut the cluster down", `cd` into the cluster dir and run `<project>/bin/easy-db-lab down --yes`.
- **No build skip by default.** Pass `--no-build` only if the user explicitly asks to skip the build.
