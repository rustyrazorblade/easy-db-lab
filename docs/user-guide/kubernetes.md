# Kubernetes

easy-db-lab uses K3s to provide a lightweight Kubernetes cluster for deploying supporting services like ClickHouse, monitoring, and stress testing workloads.

## Overview

K3s is automatically installed on all nodes during provisioning:

- **Control node**: Runs the K3s server (Kubernetes control plane)
- **Cassandra nodes**: Run as K3s agents with label `type=db`
- **Stress nodes**: Run as K3s agents with label `type=app`

## Accessing the Cluster

### kubectl

After running `source env.sh`, kubectl is automatically configured:

```bash
source env.sh
kubectl get nodes
kubectl get pods -A
```

The kubeconfig is downloaded to your working directory. `source env.sh` puts the workspace's
`bin/` first on your `PATH`, and the `kubectl` there is a wrapper that reaches the cluster through
the SOCKS5 tunnel, or directly on a Tailscale cluster. See
[Tool Wrappers](network-connectivity.md#tool-wrappers).

### k9s

k9s provides a terminal-based UI for Kubernetes:

```bash
source env.sh
k9s
```

k9s uses the workspace kubeconfig and, like `kubectl`, runs through the workspace wrapper.

## Port Forwarding

easy-db-lab uses a SOCKS5 proxy for accessing the private Kubernetes cluster.

### Starting the Proxy

Any `easy-db-lab` command that needs the cluster starts the tunnel on its own. `source env.sh`
does not start it. To start it for your shell or a browser:

```bash
easy-db-lab start-socks
```

### Manual Proxy Control

```bash
# Start the SOCKS5 tunnel, or reuse the running one, and print its port
easy-db-lab start-socks

# Check the recorded port (after source env.sh)
socks5-status

# Stop the tunnel; the cluster keeps running
easy-db-lab stop-socks
```

### Running Commands Through the Proxy

`kubectl`, `helm`, `cilium`, `curl`, `skopeo` and `k9s` use the tunnel through the workspace
wrappers. For other commands:

```bash
# Route any command through the proxy
with-proxy curl http://10.0.1.50:8080/api
```

## Pushing Docker Images with Jib

easy-db-lab includes a private Docker registry accessible via HTTPS. You can push custom images using Jib.

### Gradle Configuration

Add Jib to your `build.gradle.kts`:

```kotlin
plugins {
    id("com.google.cloud.tools.jib") version "3.4.0"
}

jib {
    from {
        image = "eclipse-temurin:21-jre"
    }
    to {
        // Use the control node's registry
        image = "control0:5000/my-app"
        tags = setOf("latest", project.version.toString())
    }
    container {
        mainClass = "com.example.MainKt"
    }
}
```

### Pushing to the Registry

```bash
# Build and push to the cluster registry
./gradlew jib

# Or build locally first
./gradlew jibDockerBuild
```

### Using Images in Kubernetes

Reference your pushed images in Kubernetes manifests:

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: my-app
spec:
  containers:
  - name: my-app
    image: control0:5000/my-app:latest
```

## Node Labels

Nodes are automatically labeled for workload scheduling:

| Node Type | Labels |
|-----------|--------|
| Cassandra | `type=db` |
| Stress | `type=app` |
| Control | (no labels) |

### Using Node Selectors

Schedule pods on specific node types:

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: stress-worker
spec:
  nodeSelector:
    type: app
  containers:
  - name: worker
    image: my-stress-tool:latest
```

## Useful Commands

```bash
# List all nodes
kubectl get nodes

# List pods in all namespaces
kubectl get pods -A

# Watch pod status
kubectl get pods -w

# View logs
kubectl logs <pod-name>

# Execute command in pod
kubectl exec -it <pod-name> -- /bin/bash

# Port forward a service locally
kubectl port-forward svc/my-service 8080:80
```

## Architecture

### Networking

- K3s server runs on the control node
- All nodes communicate over the private VPC network
- External access is via SOCKS5 proxy through the control node

### Storage

- Local path provisioner for persistent volumes
- Data stored on node-local NVMe drives at `/mnt/db1/`

### Kubeconfig

The kubeconfig file is:

- Downloaded automatically during cluster setup
- Stored as `kubeconfig` in your working directory
- Backed up to S3 for recovery
