---
name: debug-environment
description: Comprehensive debugging for easy-db-lab environments. Diagnose cluster issues, service failures, connectivity problems, K8s pod failures, SSH issues, and configuration problems. Use when troubleshooting any easy-db-lab deployment or runtime issue.
allowed-tools: Bash, Read, Grep, Glob
disable-model-invocation: false
user-invocable: true
---

# Easy-DB-Lab Environment Debugging

You are debugging an easy-db-lab environment. This skill helps diagnose and fix issues with cluster deployments, service health, connectivity, and configuration.

## Critical Context

**IMPORTANT:** These clusters are NEVER long running. Issues are:
- NEVER due to old config files
- MAY be due to config or code changes in the current branch (if not on main)
- Usually related to recent changes in the codebase

Current branch: !`git branch --show-current`

## Step 1: Verify Environment Files

First, check that the current directory has the required environment files:

Required files:
- `sshConfig` - SSH configuration for connecting to cluster nodes
- `kubeconfig` - Kubernetes configuration for K8s API access
- `state.json` - Cluster state and metadata

Check for files: !`ls -lh sshConfig kubeconfig state.json 2>&1`

**If any files are missing:** The environment is not properly initialized. Check if you're in the correct directory or if the cluster needs to be created/started.

## Step 2: Review Cluster State

Cluster state information: !`cat state.json 2>/dev/null | head -50`

Extract key information from `state.json`:
- Cluster name
- Server type (Cassandra, ClickHouse, OpenSearch, etc.)
- Number of nodes
- AWS region
- Instance types
- Control node IP

## Step 3: Check SSH Connectivity

Test SSH connectivity to cluster nodes using the `sshConfig`:

```bash
# List all SSH hosts configured
grep "^Host " sshConfig

# Test connection to control node (usually named "control")
ssh -F sshConfig control "echo 'SSH connectivity OK'" 2>&1

# Test connection to first database node (usually named "db-0" or "cassandra-0")
ssh -F sshConfig db-0 "echo 'SSH connectivity OK'" 2>&1
```

## Step 4: Check Kubernetes Cluster

Test K8s connectivity and check pod status:

```bash
# Use the kubeconfig from the environment
export KUBECONFIG=$(pwd)/kubeconfig

# Check K8s connectivity
kubectl cluster-info 2>&1

# List all pods across all namespaces
kubectl get pods -A 2>&1

# Check for failed or pending pods
kubectl get pods -A | grep -v "Running\|Completed" 2>&1

# Check recent events
kubectl get events -A --sort-by='.lastTimestamp' | tail -20 2>&1
```

## Step 5: Available easy-db-lab Commands

You have access to all easy-db-lab commands. Build the project first if needed:

```bash
# Build the project (if not already built)
./gradlew shadowJar

# Common debugging commands:
java -jar build/libs/easy-db-lab-*-all.jar <command>
```

**Key commands for debugging:**

### Cluster Management
- `list` - List all environments
- `info` - Show cluster information
- `status` - Check cluster status
- `ssh <node>` - SSH into a specific node

### Service & Observability
- `logs query` - Query logs from VictoriaLogs
- `logs ls` - List available log streams
- `metrics backup` - Backup metrics
- `grafana update-config` - Update Grafana configuration

### Node Operations
- `start` - Start cluster nodes
- `stop` - Stop cluster nodes
- `restart` - Restart cluster nodes

### Kubernetes
- `k8s list` - List K8s resources
- `k8s logs <pod>` - Get logs from a pod

## Step 6: Common Debugging Scenarios

### Scenario A: Pods Not Starting

Check pod status and events:
```bash
export KUBECONFIG=$(pwd)/kubeconfig
kubectl get pods -A
kubectl describe pod <pod-name> -n <namespace>
kubectl logs <pod-name> -n <namespace>
```

Common causes:
- Image pull failures
- Resource limits
- ConfigMap/Secret missing
- Node issues

### Scenario B: SSH Connection Failures

Check sshConfig and test connectivity:
```bash
cat sshConfig
ssh -F sshConfig -v control
```

Common causes:
- Security group rules
- EC2 instance not running
- Key permissions
- Network connectivity

### Scenario C: Service Not Responding

Check service health on nodes:
```bash
# SSH to the node
ssh -F sshConfig <node-name>

# Check systemd services
systemctl status <service-name>
journalctl -u <service-name> -n 100

# Check ports
ss -tlnp | grep <port>
```

### Scenario D: Observability Stack Issues

Check observability services (VictoriaMetrics, VictoriaLogs, Grafana, etc.):
```bash
export KUBECONFIG=$(pwd)/kubeconfig
kubectl get pods -n monitoring
kubectl get pods -n grafana
kubectl logs <pod-name> -n <namespace>
```

### Scenario E: Configuration Issues

If the issue may be related to recent config changes:
```bash
# Check what changed in the current branch
git diff main -- src/main/resources/
git diff main -- src/main/kotlin/com/rustyrazorblade/easydblab/configuration/

# Check K8s manifests
ls -la src/main/resources/com/rustyrazorblade/easydblab/commands/k8s/
```

## Step 7: Direct Node Investigation

Use SSH to connect to nodes and investigate:

```bash
# SSH to control node
ssh -F sshConfig control

# SSH to database node
ssh -F sshConfig db-0

# Common investigation commands on nodes:
docker ps                          # Check running containers
docker logs <container>            # Check container logs
systemctl list-units --failed      # Check failed services
journalctl -n 100                  # Check system logs
df -h                              # Check disk space
free -h                            # Check memory
top                                # Check CPU/processes
```

## Step 8: Kubernetes Direct Investigation

Use kubeconfig to investigate K8s resources:

```bash
export KUBECONFIG=$(pwd)/kubeconfig

# Check all resources
kubectl get all -A

# Check specific resources
kubectl get deployments -A
kubectl get statefulsets -A
kubectl get daemonsets -A
kubectl get services -A
kubectl get configmaps -A
kubectl get secrets -A

# Check resource details
kubectl describe <resource-type> <resource-name> -n <namespace>

# Check logs
kubectl logs <pod-name> -n <namespace>
kubectl logs <pod-name> -n <namespace> --previous  # Previous container logs

# Check events
kubectl get events -n <namespace> --sort-by='.lastTimestamp'
```

## Your Debugging Process

Based on the information gathered:

1. **Identify the problem area:** Cluster startup? Service failure? Network issue? Configuration?

2. **Gather evidence:**
   - Read relevant logs (systemd, container, K8s)
   - Check service status
   - Review recent code/config changes if not on main branch

3. **Form hypothesis:**
   - Based on error messages and symptoms
   - Consider recent changes in current branch
   - Rule out "old config" as a cause (clusters are fresh)

4. **Test hypothesis:**
   - Use SSH to check node state
   - Use kubectl to check K8s resources
   - Use easy-db-lab commands to check cluster state

5. **Propose solution:**
   - Specific configuration fix
   - Code change needed
   - Service restart required
   - Resource adjustment

6. **Verify fix:**
   - After applying fix, verify services are healthy
   - Check logs for errors
   - Confirm expected behavior

## Additional Resources

- Project CLAUDE.md files for architecture details
- `docs/` directory for user documentation
- `src/main/kotlin/com/rustyrazorblade/easydblab/configuration/` for K8s manifest builders
- `src/main/resources/com/rustyrazorblade/easydblab/` for templates and configs

## Output Format

Provide a structured debugging report:

1. **Environment Summary** - Cluster name, type, nodes, region
2. **Issue Description** - What's failing or not working
3. **Evidence** - Logs, errors, status output
4. **Root Cause** - Your diagnosis
5. **Recommended Fix** - Specific steps to resolve
6. **Verification Steps** - How to confirm the fix works
