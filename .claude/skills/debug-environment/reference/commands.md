# Easy-DB-Lab Command Reference

This is a quick reference for easy-db-lab commands useful during debugging.

## Running Commands

All commands are executed via the JAR file:

```bash
# Build first if needed
./gradlew shadowJar

# Run a command
java -jar build/libs/easy-db-lab-*-all.jar <command> [options]

# Or use the alias if configured
easy-db-lab <command> [options]
```

## Cluster Management Commands

### `list`
List all easy-db-lab environments.

```bash
easy-db-lab list
```

**Use when:** You need to see all available clusters or confirm environment exists.

---

### `info`
Display detailed information about the current environment.

```bash
easy-db-lab info
```

**Output includes:**
- Cluster name
- Server type
- Node count and IPs
- AWS region
- Instance types
- Environment file locations

**Use when:** You need cluster details or want to verify environment configuration.

---

### `status`
Check the status of cluster nodes and services.

```bash
easy-db-lab status
```

**Use when:** Verifying if nodes are running and services are healthy.

---

### `start`
Start all cluster nodes.

```bash
easy-db-lab start
```

**Use when:** Nodes are stopped and need to be started.

---

### `stop`
Stop all cluster nodes.

```bash
easy-db-lab stop
```

**Use when:** Need to cleanly shut down the cluster.

---

### `restart`
Restart all cluster nodes.

```bash
easy-db-lab restart
```

**Use when:** Configuration changes require restart or services are in bad state.

---

### `ssh <node>`
SSH into a specific cluster node.

```bash
# SSH to control node
easy-db-lab ssh control

# SSH to first database node
easy-db-lab ssh db-0

# SSH to specific node by index
easy-db-lab ssh db-2
```

**Use when:** Need direct shell access to investigate issues.

---

## Observability Commands

### `logs query`
Query logs from VictoriaLogs.

```bash
# Query all logs
easy-db-lab logs query

# Query with filter
easy-db-lab logs query --filter 'service="cassandra"'

# Query with time range
easy-db-lab logs query --since "1h"

# Query specific log stream
easy-db-lab logs query --stream "systemd"
```

**Use when:** Investigating issues through log analysis.

---

### `logs ls`
List available log streams.

```bash
easy-db-lab logs ls
```

**Use when:** You need to see what log sources are available.

---

### `logs backup`
Backup logs to local storage.

```bash
easy-db-lab logs backup --output /path/to/backup
```

**Use when:** Preserving logs before cluster destruction or for offline analysis.

---

### `logs import`
Import previously backed up logs.

```bash
easy-db-lab logs import --input /path/to/backup
```

**Use when:** Restoring logs for analysis.

---

### `metrics backup`
Backup metrics from VictoriaMetrics.

```bash
easy-db-lab metrics backup --output /path/to/backup
```

**Use when:** Preserving metrics data.

---

### `metrics import`
Import previously backed up metrics.

```bash
easy-db-lab metrics import --input /path/to/backup
```

**Use when:** Restoring metrics for analysis.

---

### `metrics ls`
List available metrics.

```bash
easy-db-lab metrics ls
```

**Use when:** Exploring what metrics are being collected.

---

### `grafana update-config`
Update Grafana configuration and dashboards.

```bash
easy-db-lab grafana update-config
```

**Use when:**
- Dashboards need to be updated
- Grafana configuration changed
- New dashboards added

---

## Kubernetes Commands

### `k8s list`
List Kubernetes resources.

```bash
# List all resources
easy-db-lab k8s list

# List specific resource type
easy-db-lab k8s list pods
easy-db-lab k8s list deployments
easy-db-lab k8s list services

# List in specific namespace
easy-db-lab k8s list pods -n monitoring
```

**Use when:** Need to see K8s resources without manually using kubectl.

---

### `k8s logs <pod>`
Get logs from a Kubernetes pod.

```bash
# Get logs from pod
easy-db-lab k8s logs <pod-name>

# Get logs from pod in specific namespace
easy-db-lab k8s logs <pod-name> -n <namespace>

# Get previous logs (from crashed container)
easy-db-lab k8s logs <pod-name> --previous

# Follow logs
easy-db-lab k8s logs <pod-name> --follow
```

**Use when:** Investigating pod failures or service issues.

---

### `k8s describe <resource>`
Describe a Kubernetes resource.

```bash
# Describe a pod
easy-db-lab k8s describe pod <pod-name>

# Describe in specific namespace
easy-db-lab k8s describe pod <pod-name> -n <namespace>

# Describe other resources
easy-db-lab k8s describe deployment <name>
easy-db-lab k8s describe service <name>
```

**Use when:** Need detailed information about a resource, including events.

---

### `k8s exec <pod> -- <command>`
Execute a command in a pod.

```bash
# Run command in pod
easy-db-lab k8s exec <pod-name> -- ls -la

# Interactive shell
easy-db-lab k8s exec <pod-name> -it -- /bin/bash

# Execute in specific container (multi-container pods)
easy-db-lab k8s exec <pod-name> -c <container-name> -- <command>
```

**Use when:** Need to run commands inside a container for debugging.

---

## Service Commands

### `service start <service>`
Start a specific service.

```bash
easy-db-lab service start cassandra
```

**Use when:** Need to start a stopped service.

---

### `service stop <service>`
Stop a specific service.

```bash
easy-db-lab service stop cassandra
```

**Use when:** Need to stop a running service.

---

### `service restart <service>`
Restart a specific service.

```bash
easy-db-lab service restart cassandra
```

**Use when:** Service configuration changed or service is in bad state.

---

### `service status <service>`
Check service status.

```bash
easy-db-lab service status cassandra
```

**Use when:** Verifying if a specific service is running.

---

## Direct Command Execution

For debugging, you can also use these direct approaches:

### Direct SSH (using sshConfig)
```bash
# SSH to any node
ssh -F sshConfig <node-name>

# Execute command on remote node
ssh -F sshConfig <node-name> "command"

# Copy files
scp -F sshConfig <node-name>:/path/to/file ./local/path
```

### Direct Kubectl (using kubeconfig)
```bash
# Export kubeconfig
export KUBECONFIG=$(pwd)/kubeconfig

# Then use kubectl normally
kubectl get pods -A
kubectl logs <pod-name>
kubectl describe pod <pod-name>
kubectl exec -it <pod-name> -- /bin/bash
```

### Direct Systemd Commands (via SSH)
```bash
# Check service status
ssh -F sshConfig db-0 "systemctl status cassandra"

# View service logs
ssh -F sshConfig db-0 "journalctl -u cassandra -n 100"

# Check failed services
ssh -F sshConfig db-0 "systemctl list-units --failed"
```

### Direct Docker Commands (via SSH)
```bash
# List containers
ssh -F sshConfig control "docker ps"

# View container logs
ssh -F sshConfig control "docker logs <container-name>"

# Execute in container
ssh -F sshConfig control "docker exec -it <container> /bin/bash"
```

---

## Common Debugging Workflows

### Workflow 1: Service Not Starting

```bash
# 1. Check overall status
easy-db-lab status

# 2. SSH to the node
easy-db-lab ssh db-0

# 3. Check systemd service
systemctl status <service>
journalctl -u <service> -n 100

# 4. Check resource usage
df -h
free -h
top
```

### Workflow 2: Pod Not Running

```bash
# 1. List pods
export KUBECONFIG=$(pwd)/kubeconfig
kubectl get pods -A

# 2. Describe the pod
kubectl describe pod <pod-name> -n <namespace>

# 3. Check logs
kubectl logs <pod-name> -n <namespace>

# 4. Check events
kubectl get events -n <namespace> --sort-by='.lastTimestamp'
```

### Workflow 3: Observability Stack Issue

```bash
# 1. Check if Grafana is accessible
curl http://<control-ip>:3000

# 2. Check observability pods
export KUBECONFIG=$(pwd)/kubeconfig
kubectl get pods -n monitoring
kubectl get pods -n grafana

# 3. Check specific service logs
kubectl logs -n monitoring <victoriametrics-pod>
kubectl logs -n monitoring <victorialogs-pod>

# 4. Update Grafana config if needed
easy-db-lab grafana update-config
```

### Workflow 4: Connectivity Issues

```bash
# 1. Verify environment files
ls -la sshConfig kubeconfig state.json

# 2. Test SSH
ssh -F sshConfig control "echo 'SSH OK'"

# 3. Test K8s
export KUBECONFIG=$(pwd)/kubeconfig
kubectl cluster-info

# 4. Check network connectivity
ssh -F sshConfig control "ping -c 3 8.8.8.8"
ssh -F sshConfig control "curl -I http://google.com"
```

### Workflow 5: Recent Code Changes Breaking Deployment

```bash
# 1. Check what changed
git diff main

# 2. Focus on config changes
git diff main -- src/main/resources/
git diff main -- src/main/kotlin/com/rustyrazorblade/easydblab/configuration/

# 3. Rebuild
./gradlew clean shadowJar

# 4. Check for build issues
./gradlew detekt
./gradlew ktlintCheck

# 5. Review specific manifest changes
git diff main -- src/main/kotlin/com/rustyrazorblade/easydblab/configuration/*/
```

---

## Tips

1. **Always check environment files first:** Ensure `sshConfig`, `kubeconfig`, and `state.json` exist
2. **Use verbose output:** Add `-v` or `--verbose` to commands when available
3. **Check logs systematically:** Start with high-level status, drill down to specific services
4. **Export KUBECONFIG:** Always `export KUBECONFIG=$(pwd)/kubeconfig` before kubectl commands
5. **Use SSH config:** Always use `-F sshConfig` with SSH commands for correct configuration
6. **Check recent changes:** If on a feature branch, `git diff main` is your friend
7. **Resource usage matters:** Always check disk, memory, and CPU when services fail
8. **Events tell stories:** K8s events and systemd journal often have the answers
