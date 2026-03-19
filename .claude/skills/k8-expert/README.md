# Kubernetes Expert Skill

A specialized knowledge agent for Kubernetes in easy-db-lab, focusing on K3s cluster management, Fabric8 manifest builders, and K8s-based database deployments.

## Purpose

This skill provides expert guidance on all Kubernetes aspects of easy-db-lab without executing commands or debugging active clusters. It's a Q&A agent specializing in K8s architecture, manifest building, and troubleshooting patterns.

## When to Use

Use this skill when you need to:
- ✅ Understand K3s cluster architecture
- ✅ Learn about Fabric8 manifest builders
- ✅ Get help with K8s resource definitions
- ✅ Understand how databases deploy on K8s
- ✅ Learn K8s troubleshooting patterns
- ✅ Understand pod lifecycle and states

## When NOT to Use

Don't use this skill when you need to:
- ❌ Debug an active K8s cluster (use `/debug-environment`)
- ❌ Execute kubectl commands
- ❌ Fix actual pod failures (use `/debug-environment`)
- ❌ Run tests (use `/e2e-test`)

## Expertise Areas

### K3s Cluster
- K3s installation and setup
- Control node (server) configuration
- Database nodes (agents) joining
- Kubeconfig generation
- Cluster networking

### Fabric8 Builders
- Deployment builders
- StatefulSet builders
- DaemonSet builders
- Job/CronJob builders
- ConfigMap and Secret builders
- Service builders
- PersistentVolumeClaim builders

### Database Deployments
- **ClickHouse:** StatefulSet with 3 replicas
- **Cassandra Stress:** K8s Jobs
- Storage configuration
- Replication and clustering

### Observability Stack
- VictoriaMetrics Deployment
- VictoriaLogs Deployment
- Grafana Deployment
- Tempo Deployment
- Pyroscope Deployment
- Fluent Bit DaemonSet
- OTel Collector Deployment

### Pod Management
- Pod states (Pending, Running, CrashLoopBackOff, etc.)
- Resource requests and limits
- Health checks (readiness, liveness)
- Init containers
- Pod security

### Networking
- Services (ClusterIP, NodePort, LoadBalancer)
- DNS and service discovery
- Network policies
- Port mappings

### Storage
- PersistentVolumes
- PersistentVolumeClaims
- StorageClasses
- Volume types (local, EBS, S3)

## Usage Examples

### Example 1: Architecture Question

```
/k8-expert

How is the K3s cluster set up in easy-db-lab?
```

**Response:**
Explains control node as K3s server, database nodes as K3s agents, shows installation script locations, describes kubeconfig generation, outlines node roles, and provides architecture diagram.

### Example 2: Manifest Builder Question

```
/k8-expert

How do I create a new Deployment using Fabric8?
```

**Response:**
Shows complete Deployment builder example, explains the fluent API pattern, demonstrates metadata/spec/template structure, references existing builders, provides best practices.

### Example 3: Database Deployment

```
/k8-expert

How is ClickHouse deployed on Kubernetes?
```

**Response:**
Explains StatefulSet usage for ClickHouse, shows replica configuration, describes headless service for stable network IDs, covers PVC templates for storage, shows ClickhouseManifestBuilder implementation.

### Example 4: Pod Debugging

```
/k8-expert

What does CrashLoopBackOff mean and how do I debug it?
```

**Response:**
Explains CrashLoopBackOff state (container starts but crashes), lists common causes (missing config, bad image, resource limits), provides kubectl debug commands, suggests using `/debug-environment` for active debugging.

### Example 5: Resource Management

```
/k8-expert

How do I set CPU and memory limits in a manifest builder?
```

**Response:**
Shows Fabric8 API for resource requests/limits, provides example code, explains difference between requests and limits, discusses OOMKilled behavior, demonstrates best practices.

## Knowledge Sources

The skill draws from:

### K8s-Specific Documentation
- `src/main/kotlin/com/rustyrazorblade/easydblab/kubernetes/CLAUDE.md`
- `src/main/kotlin/com/rustyrazorblade/easydblab/configuration/CLAUDE.md`

### Manifest Builders
- `configuration/clickhouse/ClickhouseManifestBuilder.kt`
- `configuration/grafana/GrafanaManifestBuilder.kt`
- `configuration/victoriametrics/VictoriaMetricsManifestBuilder.kt`
- `configuration/victorialogs/VictoriaLogsManifestBuilder.kt`
- All other `*ManifestBuilder.kt` files

### K8s Client Code
- `kubernetes/KubernetesClient.kt`
- `kubernetes/` package

### Installation Scripts
- `packer/base/install/install_k3s.sh`

## Key Concepts

### K3s Deployment Architecture

```
┌──────────────────────────────────────┐
│  Control Node (K3s Server)           │
│  - K3s server process                │
│  - kubectl access                    │
│  - Observability stack pods          │
│  - Grafana, VictoriaMetrics, etc.    │
└──────────────────────────────────────┘
               ▲
               │ K3s token
               ▼
┌──────────────────────────────────────┐
│  Database Nodes (K3s Agents)         │
│  - K3s agent process                 │
│  - Join cluster via token            │
│  - ClickHouse pods                   │
│  - Stress job pods                   │
│  - Collector pods (OTel, Fluent Bit) │
└──────────────────────────────────────┘
```

### Fabric8 Builder Pattern

All K8s resources use type-safe Kotlin builders:

```kotlin
DeploymentBuilder()
    .withNewMetadata()
        .withName("my-service")
        .withNamespace("default")
    .endMetadata()
    .withNewSpec()
        .withReplicas(3)
        // ... spec details
    .endSpec()
    .build()
```

**Benefits:**
- Compile-time type checking
- IDE autocomplete
- Refactoring support
- No YAML syntax errors
- Unit testable

### Pod Lifecycle States

Understanding pod states:

| State | Meaning | Common Causes |
|-------|---------|---------------|
| Pending | Pod accepted but not running | Resource constraints, volume issues |
| Running | All containers running | Normal operation |
| Succeeded | All containers terminated successfully | Job completed |
| Failed | At least one container failed | Application error, config issue |
| CrashLoopBackOff | Container crashes repeatedly | Bad config, missing dependency |
| ImagePullBackOff | Can't pull container image | Image doesn't exist, auth issue |

### Resource Patterns

Easy-db-lab uses specific K8s resource patterns:

1. **StatefulSet for Stateful Services** (ClickHouse)
   - Stable network IDs
   - Persistent storage per pod
   - Ordered deployment/scaling

2. **DaemonSet for Node Agents** (Fluent Bit, Beyla)
   - One pod per node
   - Automatic scheduling on new nodes
   - Node-level functionality

3. **Deployment for Stateless Services** (Grafana, VictoriaMetrics)
   - Replicas for high availability
   - Rolling updates
   - Load balancing

4. **Job for One-Time Tasks** (Stress tests)
   - Run to completion
   - No restarts on success
   - Cleanup after completion

## Common Questions

### Q: Why K3s instead of full Kubernetes?

**A:** K3s is lightweight, easy to install, and perfect for test environments. It provides full Kubernetes API compatibility with minimal overhead.

### Q: Why Fabric8 instead of kubectl apply YAML?

**A:** Fabric8 builders provide:
- Type safety (catch errors at compile time)
- IDE support (autocomplete, refactoring)
- Testability (unit test manifest generation)
- Maintainability (code-based versioning)
- No YAML syntax errors

### Q: Can I use standard kubectl commands?

**A:** Yes! Easy-db-lab generates a standard kubeconfig:
```bash
export KUBECONFIG=$(pwd)/kubeconfig
kubectl get pods -A
```

Or use easy-db-lab wrappers:
```bash
easy-db-lab k8s list pods
```

### Q: Where are K8s manifests stored?

**A:** They're NOT stored as files - they're generated programmatically in Kotlin using Fabric8 builders in the `configuration/` packages.

### Q: How do I debug a failing pod?

**A:** For active debugging, use `/debug-environment`. For understanding debugging patterns, I can explain the steps and commands.

## Troubleshooting Patterns

### Pattern 1: Pod Won't Start

**Investigation steps:**
```bash
kubectl describe pod <pod-name> -n <namespace>  # Check events
kubectl logs <pod-name> -n <namespace>          # Check logs
kubectl get events -n <namespace>               # Check namespace events
```

**Common fixes:**
- Fix ConfigMap/Secret references
- Adjust resource limits
- Fix image name/tag
- Check volume availability

### Pattern 2: Service Not Accessible

**Investigation steps:**
```bash
kubectl get svc -n <namespace>              # Verify service exists
kubectl get endpoints <svc> -n <namespace>  # Check endpoints
kubectl describe svc <svc> -n <namespace>   # Check selector
```

**Common fixes:**
- Verify pod labels match service selector
- Check port mappings
- Verify pods are Running
- Check network policies

### Pattern 3: Resource Exhaustion

**Investigation steps:**
```bash
kubectl top nodes                # Node resource usage
kubectl top pods -A              # Pod resource usage
kubectl describe nodes           # Check for pressure conditions
```

**Common fixes:**
- Increase resource limits
- Scale to more/larger nodes
- Optimize application memory usage
- Remove unused resources

## Related Skills

- **`/easy-db-lab-expert`** - General easy-db-lab expertise
- **`/debug-environment`** - Active cluster debugging
- **`/e2e-test`** - Run end-to-end tests
- **`/e2e-test-expert`** - Testing expertise

## Invocation

```bash
/k8-expert
```

Then ask your Kubernetes question.

## Tips

1. **Ask about specific resources:** "How is VictoriaMetrics deployed?"
2. **Request builder examples:** "Show me a StatefulSet builder"
3. **Understand patterns:** "Why use DaemonSet for Fluent Bit?"
4. **Learn debugging:** "How do I debug CrashLoopBackOff?"
5. **Compare approaches:** "StatefulSet vs Deployment for my use case?"

## Limitations

This is a **knowledge agent**, not an executor:
- Cannot execute kubectl commands
- Cannot debug active clusters
- Cannot modify K8s resources
- Cannot run tests

For execution tasks, use `/debug-environment` or relevant executor skills.

---

**Maintained by:** easy-db-lab project
**Version:** 1.0.0
**Type:** Knowledge Agent (K8s Q&A)
**Specialty:** K3s, Fabric8, easy-db-lab K8s patterns
