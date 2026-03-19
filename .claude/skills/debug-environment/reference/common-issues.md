# Common Issues and Solutions

This reference guide covers frequently encountered issues when debugging easy-db-lab environments.

## SSH and Connectivity Issues

### Cannot SSH to nodes

**Symptoms:**
- `ssh: connect to host <ip> port 22: Connection refused`
- `ssh: connect to host <ip> port 22: Connection timed out`

**Diagnosis:**
```bash
# Check if sshConfig exists and has correct permissions
ls -la sshConfig

# Verify SSH key permissions
ls -la ~/.ssh/id_rsa

# Check security group rules (if on AWS)
# The control node should allow SSH from your IP
```

**Solutions:**
1. Verify security groups allow SSH from your IP
2. Check that EC2 instances are running
3. Ensure SSH key has correct permissions (0600)
4. Verify sshConfig points to correct IP addresses

### SSH host key verification failed

**Symptoms:**
- `WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED!`
- `Host key verification failed`

**Solutions:**
```bash
# Remove old host keys
ssh-keygen -R <ip-address>

# Or use sshConfig with StrictHostKeyChecking=no (already configured)
ssh -F sshConfig <node-name>
```

## Kubernetes Issues

### Pods stuck in Pending state

**Symptoms:**
- Pods show `Pending` status indefinitely

**Diagnosis:**
```bash
export KUBECONFIG=$(pwd)/kubeconfig
kubectl describe pod <pod-name> -n <namespace>
```

**Common causes:**
1. **Insufficient resources:** Node doesn't have enough CPU/memory
2. **PersistentVolume issues:** Storage not available
3. **Node selector mismatch:** Pod requires specific node labels
4. **Image pull issues:** Cannot pull container image

**Solutions:**
- Check node resources: `kubectl top nodes`
- Check PV/PVC: `kubectl get pv,pvc -A`
- Check events: `kubectl get events -n <namespace>`

### Pods in CrashLoopBackOff

**Symptoms:**
- Pods repeatedly restart
- Status shows `CrashLoopBackOff`

**Diagnosis:**
```bash
export KUBECONFIG=$(pwd)/kubeconfig
kubectl logs <pod-name> -n <namespace>
kubectl logs <pod-name> -n <namespace> --previous
kubectl describe pod <pod-name> -n <namespace>
```

**Common causes:**
1. **Configuration error:** Missing ConfigMap/Secret, wrong environment variables
2. **Application error:** Bug in the application code
3. **Dependency unavailable:** Database or service not ready
4. **Resource limits:** OOMKilled (out of memory)

**Solutions:**
- Check logs for specific error messages
- Verify ConfigMaps and Secrets exist
- Check resource limits and requests
- Ensure dependencies are healthy

### Cannot connect to K8s cluster

**Symptoms:**
- `Unable to connect to the server`
- `The connection to the server localhost:8080 was refused`

**Diagnosis:**
```bash
# Check if kubeconfig exists
ls -la kubeconfig

# Verify kubeconfig content
cat kubeconfig

# Test with explicit kubeconfig
KUBECONFIG=$(pwd)/kubeconfig kubectl cluster-info
```

**Solutions:**
1. Ensure you're in the environment directory
2. Export KUBECONFIG: `export KUBECONFIG=$(pwd)/kubeconfig`
3. Check control node is running
4. Verify K8s API server is accessible

### ImagePullBackOff errors

**Symptoms:**
- Pods cannot start
- Status shows `ImagePullBackOff` or `ErrImagePull`

**Diagnosis:**
```bash
kubectl describe pod <pod-name> -n <namespace>
# Look for "Failed to pull image" messages
```

**Common causes:**
1. Image doesn't exist in registry
2. Image tag incorrect
3. Registry authentication required
4. Network connectivity to registry

**Solutions:**
- Verify image name and tag are correct
- Check if image exists: `docker pull <image>`
- For private registries, ensure pull secrets are configured

## Observability Stack Issues

### Grafana not accessible

**Symptoms:**
- Cannot access Grafana on port 3000
- Connection refused or timeout

**Diagnosis:**
```bash
export KUBECONFIG=$(pwd)/kubeconfig
kubectl get pods -n grafana
kubectl get svc -n grafana
kubectl logs -n grafana <grafana-pod>
```

**Solutions:**
1. Verify Grafana pod is running
2. Check service is exposing port 3000
3. Verify port forwarding or LoadBalancer
4. Check firewall/security group rules

### VictoriaMetrics not collecting metrics

**Symptoms:**
- Grafana dashboards show no data
- Metrics queries return empty results

**Diagnosis:**
```bash
export KUBECONFIG=$(pwd)/kubeconfig
kubectl get pods -n monitoring
kubectl logs -n monitoring <victoriametrics-pod>

# Check if OTel collector is running
kubectl get pods -n opentelemetry
kubectl logs -n opentelemetry <otel-collector-pod>
```

**Solutions:**
1. Verify VictoriaMetrics pod is healthy
2. Check OTel collector is scraping targets
3. Verify network connectivity between collector and VictoriaMetrics
4. Check VictoriaMetrics configuration

### VictoriaLogs not receiving logs

**Symptoms:**
- Log queries return no results
- Logs not visible in Grafana

**Diagnosis:**
```bash
export KUBECONFIG=$(pwd)/kubeconfig
kubectl get pods -n monitoring
kubectl logs -n monitoring <victorialogs-pod>

# Check Fluent Bit collectors
kubectl get pods -n fluent-bit
kubectl logs -n fluent-bit <fluent-bit-pod>
```

**Solutions:**
1. Verify VictoriaLogs pod is healthy
2. Check Fluent Bit is running on all nodes
3. Verify Fluent Bit configuration
4. Check network connectivity

### Tempo not receiving traces

**Symptoms:**
- Traces not visible in Grafana
- Distributed tracing not working

**Diagnosis:**
```bash
export KUBECONFIG=$(pwd)/kubeconfig
kubectl get pods -n monitoring
kubectl logs -n monitoring <tempo-pod>

# Check OTel collector trace pipeline
kubectl logs -n opentelemetry <otel-collector-pod>
```

**Solutions:**
1. Verify Tempo pod is healthy
2. Check OTel collector trace configuration
3. Verify applications are instrumented for tracing
4. Check receiver endpoints are correct

## Database-Specific Issues

### Cassandra nodes not starting

**Symptoms:**
- Cassandra service fails to start
- Nodes not joining cluster

**Diagnosis:**
```bash
ssh -F sshConfig db-0
systemctl status cassandra
journalctl -u cassandra -n 100
```

**Common causes:**
1. Insufficient disk space
2. Java version mismatch
3. Configuration errors
4. Seed node unavailable

**Solutions:**
- Check disk space: `df -h`
- Verify Java version: `java -version`
- Review Cassandra logs
- Check seed node configuration

### ClickHouse not starting

**Symptoms:**
- ClickHouse container/pod not running
- Database not accessible

**Diagnosis:**
```bash
export KUBECONFIG=$(pwd)/kubeconfig
kubectl get pods -A | grep clickhouse
kubectl logs <clickhouse-pod> -n <namespace>

# Or via SSH if running directly
ssh -F sshConfig db-0
docker logs clickhouse
```

**Solutions:**
- Check ClickHouse configuration
- Verify storage volumes
- Check for port conflicts
- Review ClickHouse logs for specific errors

### OpenSearch cluster not forming

**Symptoms:**
- OpenSearch nodes not joining cluster
- Cluster health red/yellow

**Diagnosis:**
```bash
export KUBECONFIG=$(pwd)/kubeconfig
kubectl get pods -n opensearch
kubectl logs <opensearch-pod> -n opensearch

# Check cluster health
kubectl exec <opensearch-pod> -n opensearch -- curl -s localhost:9200/_cluster/health
```

**Solutions:**
- Verify node discovery configuration
- Check network connectivity between nodes
- Ensure minimum master nodes configured
- Review OpenSearch logs

## Configuration and Build Issues

### Recent code changes breaking deployment

**Remember:** Clusters are fresh, not long-running. If on a branch other than main:

**Diagnosis:**
```bash
# Check what changed
git diff main

# Focus on configuration changes
git diff main -- src/main/resources/
git diff main -- src/main/kotlin/com/rustyrazorblade/easydblab/configuration/

# Check K8s manifest builders
git diff main -- src/main/kotlin/com/rustyrazorblade/easydblab/configuration/*/
```

**Solutions:**
1. Review recent commits for breaking changes
2. Check if new configuration is valid
3. Verify K8s manifest builders produce valid YAML
4. Test locally before deploying

### Build failures

**Diagnosis:**
```bash
./gradlew clean build
./gradlew detekt
./gradlew ktlintCheck
```

**Solutions:**
- Fix compilation errors
- Address code quality issues
- Format code: `./gradlew ktlintFormat`
- Check dependency versions

## AWS/Cloud Issues

### EC2 instances not starting

**Symptoms:**
- Instances stuck in pending
- Instances terminate immediately

**Diagnosis:**
```bash
# Check instance status
aws ec2 describe-instances --instance-ids <instance-id>

# Check instance status checks
aws ec2 describe-instance-status --instance-ids <instance-id>
```

**Solutions:**
- Check service quotas/limits
- Verify IAM permissions
- Check subnet and VPC configuration
- Review instance type availability in region

### Security group issues

**Symptoms:**
- Cannot access services
- Timeouts when connecting

**Diagnosis:**
```bash
# Check security group rules
aws ec2 describe-security-groups --group-ids <sg-id>
```

**Solutions:**
- Add inbound rules for required ports
- Verify source IP restrictions
- Check outbound rules
- Use `bin/set-policies` tool if needed

### IAM permission errors

**Symptoms:**
- `AccessDenied` errors
- Cannot create/modify AWS resources

**Solutions:**
```bash
# Set correct IAM policies
bin/set-policies --group-name LabUser
```

## Network Issues

### Port conflicts

**Symptoms:**
- Service fails to bind to port
- `Address already in use` errors

**Diagnosis:**
```bash
# Check what's using the port
ssh -F sshConfig <node> "ss -tlnp | grep <port>"

# Or locally
ss -tlnp | grep <port>
```

**Solutions:**
- **NEVER disable the service** - assign a different port
- Stop conflicting service
- Update configuration to use alternative port

### DNS resolution failures

**Symptoms:**
- Cannot resolve hostnames
- `Name or service not known`

**Diagnosis:**
```bash
ssh -F sshConfig control
nslookup <hostname>
cat /etc/resolv.conf
```

**Solutions:**
- Verify DNS configuration
- Check VPC DNS settings (if AWS)
- Update /etc/hosts if needed
- Verify security groups allow DNS

## Resource Issues

### Out of disk space

**Symptoms:**
- Services failing with write errors
- Logs showing "No space left on device"

**Diagnosis:**
```bash
ssh -F sshConfig <node>
df -h
du -sh /* | sort -h
```

**Solutions:**
- Clean up old logs
- Remove unused containers/images
- Increase volume size
- Configure log rotation

### Out of memory

**Symptoms:**
- OOMKilled pods
- Services crashing
- System freezing

**Diagnosis:**
```bash
ssh -F sshConfig <node>
free -h
top
dmesg | grep -i oom
```

**Solutions:**
- Increase memory allocation
- Adjust Java heap sizes
- Configure resource limits properly
- Scale to larger instance types

## General Troubleshooting Tips

1. **Check logs first** - Most issues have clear error messages in logs
2. **Verify prerequisites** - Required files, services, dependencies
3. **Test connectivity** - SSH, K8s API, database ports
4. **Review recent changes** - Git diff if on a feature branch
5. **Check resource usage** - CPU, memory, disk, network
6. **Consult documentation** - CLAUDE.md files, docs/, openspec/specs/
7. **Test incrementally** - Start one service at a time
8. **Use verbose output** - Enable debug logging when needed

## Getting More Help

If none of the above helps:

1. Gather comprehensive diagnostic info
2. Check project CLAUDE.md files for architecture details
3. Review openspec specifications for feature requirements
4. Consult user documentation in `docs/`
5. Check recent commits and pull requests
6. Review CI/CD logs for build/test failures
