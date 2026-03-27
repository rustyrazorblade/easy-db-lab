---
name: agent-test
description: Dynamic end-to-end test runner for easy-db-lab that calls easy-db-lab commands directly. Analyzes branch changes, proposes a test plan, executes commands step-by-step, and investigates failures inline. Use instead of bin/end-to-end-test when you want intelligent, adaptive testing with real-time debugging.
allowed-tools: Bash, Read, Grep, Glob
argument-hint: [--cassandra|--clickhouse|--opensearch|--spark|--all] [--yes] [--no-teardown]
disable-model-invocation: false
user-invocable: true
---

# Easy-DB-Lab Agent Test Runner

You are a dynamic end-to-end test agent for easy-db-lab. Unlike `bin/end-to-end-test`, you call
`easy-db-lab` commands directly, so you can adapt, investigate, and debug inline at each step.

## Arguments

User-provided arguments: $ARGUMENTS

- `--cassandra`, `--clickhouse`, `--opensearch`, `--spark`, `--all` — force specific test scope
- `--yes` — skip the confirmation prompt and run the proposed plan immediately
- `--no-teardown` — leave cluster running after tests (default when debugging)

---

## Phase 1: Analyze Branch Changes

Gather context about what changed:

Current branch: !`git branch --show-current`

Commits on this branch vs main: !`git log --oneline main..HEAD 2>/dev/null | head -10 || echo "(on main or no commits)"`

Changed files: !`git diff --name-only main...HEAD 2>/dev/null || echo "(no changes from main)"`

Change summary: !`git diff --stat main...HEAD 2>/dev/null | tail -5 || echo "(no changes)"`

Read the actual diffs for changed files that matter (skip docs, tests, build files):

```bash
git diff main...HEAD -- '*.kt' '*.sh' '*.yaml' '*.yml' 2>/dev/null | head -200
```

Use this to understand *what* changed, not just *which* files. This lets you build a more specific test plan — e.g. if S3 path logic changed, specifically verify S3 backup behavior.

### Scope Detection Rules

If explicit flags were provided, use those. Otherwise:

**`--cassandra`** — any file matching (case-insensitive): `*cassandra*`, `packer/cassandra/**`

**`--clickhouse`** — any file matching: `*clickhouse*`

**`--opensearch`** — any file matching: `*opensearch*`

**`--spark`** — any file matching: `spark/**`, `*spark*`, `*emr*` (also requires `--cassandra`)

**`--cassandra` (default for core changes)** — if changes touch:
- `**/configuration/**`, `**/kubernetes/**`, `**/providers/**`
- `**/commands/Init*`, `packer/base/**`, `**/mcp/**`
- `**/services/ClusterBackupService*`, `**/ClusterS3Path*`
- Observability: `**/victoriametrics/**`, `**/grafana/**`, `**/victorialogs/**`

**Documentation only** (`docs/**`, `*.md`): No e2e tests needed — tell the user and stop.

**4+ subsystems changed**: Recommend `--all`.

---

## Phase 2: Check for Running Cluster

```bash
easy-db-lab status 2>&1
```

**`easy-db-lab status` is the authoritative source.**

**NEVER read `state.json` directly** — it is an internal implementation detail of easy-db-lab. Use `easy-db-lab` CLI commands to get all cluster information (hosts, IPs, S3 paths, etc.).

A cluster is genuinely running only if the status output shows ALL of the following:
- `Infrastructure: UP`
- SSH connectivity works (no "unable to connect", no "Failed to get operation result", no timeout errors)
- Nodes show actual states (not `UNKNOWN`)

If any of those conditions are missing, the infrastructure is gone or unreachable. Do not try to rescue it. Treat it as "no cluster running" and proceed to Phase 3 (init with `--clean`).

Only if ALL conditions are met:
- Report the cluster status
- Present options: resume testing from a specific step, or tear down and restart with `--clean`
- Do NOT proceed to Phase 3 without user direction

---

## Phase 3: Build and Present Test Plan

Construct a specific, detailed test plan based on the detected scope. Present it to the user **before running anything**.

Format:

```
## Test Plan: <branch-name>

### What Changed
<For each relevant changed file, one line explaining what it does and why it's relevant>

Example:
- `src/main/kotlin/.../ClusterS3Path.kt` — S3 path construction logic → tests S3 backup/restore
- `packer/cassandra/install/install_cassandra.sh` — Cassandra install script → tests Cassandra provisioning

### Test Commands

**Infrastructure setup** (always):
1. easy-db-lab init -c 3 -i c5d.2xlarge test --clean --up -s 1 [--spark.enable if --spark scope]
2. [source env.sh — sets up kubectl and SSH aliases]
3. easy-db-lab hosts
4. easy-db-lab status

**Core verification** (always — minimal, just confirms cluster is alive):
5. easy-db-lab exec run -t cassandra -- hostname  → verify exec command

**Only include the following steps if the changes directly relate to them:**
- Server / `/status` endpoint → only if `server` startup, REST endpoints, or `ClusterS3Path` changed
- S3 backup file check → only if `ClusterBackupService`, `ClusterS3Path`, or S3 config changed
- Restore from VPC → only if restore/backup logic changed
- Observability (VictoriaMetrics, VictoriaLogs, Grafana) → only if observability stack changed
- Metrics/logs backup → only if `ClusterBackupService` or metrics/logs backup commands changed

**Cassandra steps** (if --cassandra):
16. easy-db-lab cassandra use 5.0
17. echo "storage_compatibility_mode: NONE" >> cassandra.patch.yaml
18. easy-db-lab cassandra update-config
19. easy-db-lab cassandra start
20. aws s3 ls s3://<fullpath>/config/cassandra.patch.yaml  → verify Cassandra config backed up
21. ssh db0 nodetool status
22. ssh db0 "curl -s http://<private-ip>:9043/api/v1/cassandra/schema" | jq 'keys'
23. ssh app0 "bash -l -c 'cassandra-easy-stress run KeyValue -d 10s'"
24. easy-db-lab cassandra stress run --name agent-test -- KeyValue -d 30s
25. easy-db-lab cassandra stress stop --all
26. easy-db-lab cassandra restart

**ClickHouse steps** (if --clickhouse):
... [similar detail]

**OpenSearch steps** (if --opensearch):
... [similar detail]

### Estimated Duration: ~15-20 min (--cassandra) / ~45-60 min (--all)
### Estimated Cost: ~$0.25-0.50 (3x c5d.2xlarge @ $0.384/hr)

---
Proceed? [yes/no, or adjust flags]
```

**Skip this phase if `--yes` was provided.**

Wait for confirmation before proceeding.

---

## Phase 4: Execute Test Plan

### Pre-flight

```bash
# Check SOCKS5 proxy port is free
lsof -Pi :1080 -sTCP:LISTEN -t 2>/dev/null && echo "PORT_BUSY" || echo "PORT_FREE"

# Export AWS profile
export AWS_PROFILE=sandbox-admin
```

If port 1080 is busy, tell user to check: `lsof -Pi :1080` or `source env.sh && stop-socks5`

Kill any stale server processes:
```bash
pgrep -f "easy-db-lab.*server" 2>/dev/null | xargs kill -9 2>/dev/null || true
```

Build the project if needed (ask the user first — they may already have a current build):
```bash
./gradlew shadowJar installDist
```

### Executing Steps

Run each step from the test plan using the Bash tool. After each step:

1. **If the step succeeded** → report ✅ and continue to the next step
2. **If the step failed** → report ❌ and immediately investigate (see Phase 5 — Debugging)

**Report format for each step:**
```
✅ Step N: <command>
   → <one-line result summary>
```

### Infrastructure Setup

```bash
# Include --spark.enable if the test scope includes Spark
SPARK_FLAG=""
# (set SPARK_FLAG="--spark.enable" if --spark scope detected)

easy-db-lab init -c 3 -i c5d.2xlarge test --clean --up -s 1 $SPARK_FLAG
```

After init completes:
```bash
source env.sh
kubectl get nodes
easy-db-lab status
easy-db-lab hosts
```

### Server Verification (only if server/REST endpoints or ClusterS3Path changed)

```bash
# Find a free port
SERVER_PORT=$(python3 -c "import socket; s=socket.socket(); s.bind(('', 0)); print(s.getsockname()[1]); s.close()")
SERVER_LOG=$(mktemp /tmp/agent-test-server-XXXXXX)
easy-db-lab server --port $SERVER_PORT > "$SERVER_LOG" 2>&1 &
SERVER_PID=$!

# Wait for it to be ready (up to 120s)
for i in $(seq 1 60); do
    HTTP_CODE=$(NO_PROXY="localhost,127.0.0.1" curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:${SERVER_PORT}/status" 2>/dev/null)
    [ "$HTTP_CODE" = "200" ] && break
    sleep 2
done

# Verify the response
RESPONSE=$(NO_PROXY="localhost,127.0.0.1" curl -s "http://127.0.0.1:${SERVER_PORT}/status")
echo "$RESPONSE" | jq '{has_cluster: (.cluster != null), has_nodes: (.nodes != null), s3_fullpath: .s3.fullpath}'

# Save fullpath for later steps
echo "$RESPONSE" | jq -r '.s3.fullpath' > .s3-fullpath

# Stop server
kill -9 $SERVER_PID 2>/dev/null || true
wait $SERVER_PID 2>/dev/null || true
rm -f "$SERVER_LOG"
```

Verify: response contains `.cluster`, `.nodes`, and `.s3.fullpath`.

### S3 Backup Verification

```bash
S3_FULLPATH=$(cat .s3-fullpath)

# Check required backup files
for f in config/kubeconfig config/cassandra_versions.yaml config/environment.sh config/setup_instance.sh; do
    aws s3 ls "s3://${S3_FULLPATH}/${f}" > /dev/null 2>&1 && echo "OK: $f" || echo "MISSING: $f"
done
```

### Exec Command Test

```bash
easy-db-lab exec run -t cassandra -- hostname
easy-db-lab exec run -t cassandra -p -- uptime
easy-db-lab exec run -t cassandra --hosts db0,db1 -- date
```

### Observability Verification

```bash
# Health checks via SSH (use -F sshConfig — env.sh aliases don't persist across shell invocations)
ssh -F sshConfig control0 "curl -s http://localhost:8428/health"
ssh -F sshConfig control0 "curl -s http://localhost:9428/health"
ssh -F sshConfig control0 "curl -s http://localhost:3000/api/health | jq -r .database"

# Verify datasources
ssh -F sshConfig control0 "curl -s http://localhost:3000/api/datasources | jq -r '.[].name'"

# Verify dashboards load
ssh -F sshConfig control0 "curl -s 'http://localhost:3000/api/search?type=dash-db' | jq length"
```

### Metrics and Logs Backup

```bash
easy-db-lab metrics backup
easy-db-lab logs backup

S3_FULLPATH=$(cat .s3-fullpath)
aws s3 ls "s3://${S3_FULLPATH}/victoriametrics/" | wc -l
aws s3 ls "s3://${S3_FULLPATH}/victorialogs/" | wc -l
```

> **Note**: All `ssh` commands must use `ssh -F sshConfig <host>` — the aliases set by `source env.sh` (e.g. `ssh db0`) only exist in the shell where env.sh was sourced and do not persist across Bash tool invocations.

### Cassandra Steps (if --cassandra)

```bash
easy-db-lab cassandra use 5.0

# Required for bulk writer compatibility
echo "storage_compatibility_mode: NONE" >> cassandra.patch.yaml
easy-db-lab cassandra update-config
easy-db-lab cassandra start

# Verify Cassandra config backed up
S3_FULLPATH=$(cat .s3-fullpath)
aws s3 ls "s3://${S3_FULLPATH}/config/cassandra.patch.yaml" && echo "OK" || echo "MISSING"

# SSH nodetool (use -F sshConfig — env.sh aliases don't persist across shell invocations)
ssh -F sshConfig db0 nodetool status

# Sidecar API
PRIVATE_IP=$(easy-db-lab ip db0 --private)
ssh -F sshConfig db0 "curl -s http://${PRIVATE_IP}:9043/api/v1/cassandra/schema" | jq 'keys'

# Stress test
ssh -F sshConfig app0 "bash -l -c 'cassandra-easy-stress run KeyValue -d 10s'"

# K8s stress job
easy-db-lab cassandra stress run --name agent-test -- KeyValue -d 30s
# Wait for job to complete (up to 120s)
for i in $(seq 1 12); do
    STATUS=$(kubectl get jobs -l app.kubernetes.io/name=cassandra-easy-stress \
        -o jsonpath='{.items[0].status.succeeded}' 2>/dev/null || echo "")
    [ "$STATUS" = "1" ] && echo "Stress job completed" && break
    echo "Waiting... ($((i*10))s)"
    sleep 10
done
easy-db-lab cassandra stress stop --all

# Start/stop cycle
easy-db-lab cassandra stop
sleep 10
easy-db-lab cassandra start
easy-db-lab cassandra restart
```

### ClickHouse Steps (if --clickhouse)

```bash
easy-db-lab clickhouse init
easy-db-lab clickhouse start
easy-db-lab clickhouse status

# Connectivity test via SSH to control0
# (ClickHouse ports are only accessible within VPC)
DATA_BUCKET=$(jq -r '.dataBucket // empty' state.json)

# Test basic query
# Test S3 tier policy
# Cleanup
easy-db-lab clickhouse stop --force

# Verify namespace deleted
for i in $(seq 1 12); do
    kubectl get namespace clickhouse &>/dev/null || { echo "Namespace deleted"; break; }
    sleep 5
done
```

### OpenSearch Steps (if --opensearch)

```bash
easy-db-lab opensearch start --wait
easy-db-lab opensearch status

ENDPOINT=$(easy-db-lab opensearch status --endpoint)
ssh -F sshConfig control0 "curl -s -k https://${ENDPOINT}/_cluster/health" | jq .

# Create test index, insert doc, verify
ssh -F sshConfig control0 "curl -s -k -X PUT https://${ENDPOINT}/agent-test-index"
ssh -F sshConfig control0 "curl -s -k -X POST https://${ENDPOINT}/agent-test-index/_doc/1 \
    -H 'Content-Type: application/json' -d '{\"message\": \"agent-test\"}'"
ssh -F sshConfig control0 "curl -s -k https://${ENDPOINT}/agent-test-index/_doc/1" | jq .

easy-db-lab opensearch stop --force
```

### Spark Bulk Writer Steps (if --spark)

Use `easy-db-lab` commands to get contact points — **never read state.json**.

Useful host commands:
- `easy-db-lab hosts -c` → Cassandra public IPs as comma-separated list (e.g. `34.x.x.x,54.x.x.x`)
- `easy-db-lab ip db0 --private` → private IP for a single host
- `easy-db-lab hosts` → full YAML with all node details

For Spark jobs, use **private IPs** (EMR is in the same VPC; sidecar binds to the private interface):

```bash
hosts=$(easy-db-lab hosts 2>/dev/null | awk '/^cassandra:/{found=1} /^stress:|^control:/{found=0} found && /private:/{gsub(/"/, "", $2); printf "%s,", $2}' | sed 's/,$//')
dc=$(easy-db-lab aws region 2>/dev/null)

# Direct (sidecar) bulk writer
jar=$(ls spark/bulk-writer-sidecar/build/libs/bulk-writer-sidecar*.jar | head -1)
easy-db-lab spark submit \
  --jar "$jar" \
  --main-class com.rustyrazorblade.easydblab.spark.DirectBulkWriter \
  --conf "spark.easydblab.contactPoints=$hosts" \
  --conf "spark.easydblab.keyspace=bulk_test" \
  --conf "spark.easydblab.table=data_sidecar" \
  --conf "spark.easydblab.localDc=$dc" \
  --conf "spark.easydblab.rowCount=10000" \
  --conf "spark.easydblab.parallelism=4" \
  --conf "spark.easydblab.partitionCount=100" \
  --conf "spark.easydblab.replicationFactor=3" \
  --wait
```

**IMPORTANT**: Do NOT append `:9043` to the hosts. Passing `ip:port` format causes Netty to fail with `invalid IPv6 address literal`. Pass bare IPs only.

### Teardown

If all tests passed and `--no-teardown` was NOT provided:

```bash
rm -f .s3-fullpath
easy-db-lab down --yes
```

If `--no-teardown` was provided, leave the cluster running and tell the user how to tear down manually.

---

## Phase 5: Inline Debugging

**When any step fails**, do NOT just record the failure and move on. Immediately investigate.

### Investigation Checklist

Run these in order, stopping when you find the root cause:

```bash
# 1. Pod status
kubectl get pods -A

# 2. Pods in bad state
kubectl get pods -A | awk 'NR==1 || ($4 != "Running" && $4 != "Completed" && $4 != "Pending")'

# 3. Logs for failing pods
# For each CrashLoopBackOff or Error pod:
kubectl logs -n <namespace> <pod> --tail=50
kubectl describe pod -n <namespace> <pod>

# 4. Recent K8s events
kubectl get events --sort-by=.lastTimestamp -A | tail -20

# 5. Node resource status
kubectl describe nodes | grep -A5 "Allocated resources"

# 6. If SSH-related failure
ssh -F sshConfig -o ConnectTimeout=10 control0 "echo ok"

# 7. For Cassandra failures (always use -F sshConfig)
ssh -F sshConfig db0 "systemctl status cassandra"
ssh -F sshConfig db0 "journalctl -u cassandra --no-pager -n 50"

# 8. For observability failures
ssh -F sshConfig control0 "kubectl get pods -A"
ssh -F sshConfig control0 "curl -s http://localhost:8428/health"
ssh -F sshConfig control0 "curl -s http://localhost:9428/health"

# 9. Disk space
kubectl exec -n kube-system -it <any-pod> -- df -h 2>/dev/null || true

# 10. Control node service status
ssh control0 "systemctl list-units --failed"
```

### After Investigation

Report:
- The specific error from the failed command
- What you found in the investigation
- Root cause assessment
- Recommended fix (code change, config change, or infra issue)

Ask the user:
1. Apply fix and retry from this step?
2. Skip this step and continue?
3. Tear down and investigate locally?

---

## Phase 6: Final Summary

```
Agent Test Results
==================

Branch: <branch>
Test Scope: <flags>
Duration: ~<X> minutes
Result: PASS | FAIL

Steps Run:
  ✅ Infrastructure setup
  ✅ K3s cluster verified
  ✅ MCP server /status
  ✅ S3 backup verified
  ✅ Restore from VPC verified
  ✅ Exec command
  ✅ Observability stack
  ✅ Metrics/logs backup
  [Cassandra steps if applicable]
  ❌ <step> — <error>

Root Cause (if failed):
<finding from investigation>

Recommended Fix:
<specific action>

Cluster: <name> — <RUNNING / torn down>
```

---

## Defaults (matching bin/end-to-end-test)

- Instance type: `c5d.2xlarge`
- Node count: `3`
- Node type flag: `-t cassandra`
- Seed count: `1` (`-s 1`)
- AWS profile: `sandbox-admin`
- Cluster name: `test`

Override instance type: `EASY_DB_LAB_INSTANCE_TYPE=c5d.4xlarge /agent-test --cassandra`
