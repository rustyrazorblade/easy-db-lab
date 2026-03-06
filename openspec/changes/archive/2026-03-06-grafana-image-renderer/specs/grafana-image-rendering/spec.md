## ADDED Requirements

### Requirement: Grafana Image Renderer Sidecar

The system SHALL deploy the `grafana/grafana-image-renderer` container as a sidecar in the Grafana pod, enabling server-side rendering of dashboard panels as PNG images.

#### Scenario: Renderer container runs alongside Grafana

- **WHEN** the Grafana deployment is applied to the cluster
- **THEN** the pod SHALL contain a `grafana-image-renderer` container using the `grafana/grafana-image-renderer:latest` image
- **AND** the renderer SHALL listen on port 8081

#### Scenario: Grafana is configured to use the renderer

- **WHEN** the Grafana container starts
- **THEN** the environment variable `GF_RENDERING_SERVER_URL` SHALL be set to `http://localhost:8081/render`
- **AND** the environment variable `GF_RENDERING_CALLBACK_URL` SHALL be set to `http://localhost:3000/`

#### Scenario: Panel image rendering via Grafana API

- **WHEN** a client requests a panel render via Grafana's `/render/d-solo/` HTTP API
- **THEN** Grafana SHALL delegate rendering to the sidecar and return a PNG image
