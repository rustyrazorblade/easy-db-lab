## 1. Add Image Renderer Sidecar to GrafanaManifestBuilder

- [x] 1.1 Add `grafana/grafana-image-renderer:latest` image constant to GrafanaManifestBuilder
- [x] 1.2 Add renderer sidecar container to the Grafana pod in `buildDeployment()` — port 8081, no resource limits
- [x] 1.3 Add `GF_RENDERING_SERVER_URL` and `GF_RENDERING_CALLBACK_URL` environment variables to the Grafana container

## 2. Testing

- [x] 2.1 Update GrafanaManifestBuilder unit test to verify the deployment has two containers and the renderer env vars are set
- [x] 2.2 Update K8sServiceIntegrationTest to include an image pull test for `grafana/grafana-image-renderer:latest`

## 3. Documentation

- [x] 3.1 Update `configuration/CLAUDE.md` to mention the image renderer sidecar in the Grafana section
