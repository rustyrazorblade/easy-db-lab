## Why

The observability stack includes Grafana dashboards for monitoring cluster health, but there's no way to programmatically retrieve dashboard panel images. Adding the Grafana Image Renderer enables server-side rendering of panel snapshots via Grafana's rendering API, which is a prerequisite for features like alerting with embedded images and direct link sharing of panel screenshots.

## What Changes

- Deploy `grafana/grafana-image-renderer` as a sidecar container alongside Grafana in the same pod
- Configure Grafana to use the image renderer plugin via environment variables

## Capabilities

### New Capabilities
- `grafana-image-rendering`: Server-side rendering of Grafana dashboard panels as PNG images, including the renderer sidecar deployment and Grafana configuration to use it

### Modified Capabilities
- `observability`: Grafana deployment now includes an image renderer sidecar container

## Impact

- `GrafanaManifestBuilder` — add renderer sidecar container to the Grafana pod
- `GrafanaUpdateConfig` command — renderer deploys automatically with Grafana (no new command needed)
- K8s integration tests — updated to cover the new container
