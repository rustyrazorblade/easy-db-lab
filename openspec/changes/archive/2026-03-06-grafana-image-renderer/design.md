## Context

Grafana runs as a single-container pod on the control node with `hostNetwork: true`. The `GrafanaManifestBuilder` builds the deployment using Fabric8. The image renderer is a separate process that Grafana calls via HTTP to render panels as PNG images. Grafana officially supports this via the `grafana/grafana-image-renderer` Docker image running as a sidecar.

## Goals / Non-Goals

**Goals:**
- Deploy the Grafana Image Renderer as a sidecar container in the existing Grafana pod
- Configure Grafana to use the renderer for server-side image rendering
- Ensure the renderer is included in integration tests

**Non-Goals:**
- Adding new CLI commands or MCP tools to consume rendered images
- Customizing renderer settings beyond defaults (Chrome flags, timeouts, etc.)

## Decisions

### Sidecar container in the Grafana pod (not a separate deployment)

The renderer needs fast, low-latency communication with Grafana. Running it in the same pod means they share the host network and communicate over localhost. This avoids needing a separate K8s Service or DNS resolution. It also ensures the renderer lifecycle is tied to Grafana's.

Alternative: Separate Deployment + Service. Rejected because it adds unnecessary complexity for a component that only serves Grafana.

### Communication via localhost on port 8081

Since the pod uses `hostNetwork: true`, both containers bind to the host's network stack. Grafana connects to the renderer at `http://localhost:8081`. The renderer's default port is 8081, which doesn't conflict with any existing service on the control node.

Grafana is configured via:
- `GF_RENDERING_SERVER_URL=http://localhost:8081/render`
- `GF_RENDERING_CALLBACK_URL=http://localhost:3000/`

### No resource limits

Consistent with the project's no-resource-limits policy. The control node has sufficient memory for Chromium rendering.

## Risks / Trade-offs

- **Chromium memory usage** — The renderer runs headless Chromium, which can use significant memory for complex dashboards. Mitigation: control nodes are sized for observability workloads, and no resource limits are set per project policy.
- **Startup time** — Chromium takes a few seconds to initialize. Mitigation: the renderer has its own readiness state; Grafana will retry if rendering fails during startup.
