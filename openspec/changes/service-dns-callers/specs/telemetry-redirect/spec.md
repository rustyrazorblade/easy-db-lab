## MODIFIED Requirements

### Requirement: Redirect mode is selected at bring-up with a single base host

The operator SHALL be able to select redirect mode at `init` by supplying one external base host (`--redirect-telemetry <host>`), from which the four signal endpoints are derived from the receiving easy-db-lab stack's published NodePorts (Mimir, Loki, Tempo OTLP gRPC and Pyroscope).  The operator MAY override any individual signal endpoint for a non-easy-db-lab target.  Redirect mode SHALL be a life-of-cluster property, persisted with the cluster state, and MUST NOT be a per-signal choice: all four signals move together.

#### Scenario: Base host derives all four endpoints

- **WHEN** a cluster is initialized with `--redirect-telemetry <host>` and no per-signal overrides
- **THEN** the metrics, logs, traces, and profiles endpoints are `<host>` with the Mimir, Loki, Tempo OTLP gRPC and Pyroscope NodePorts, and are stored on the cluster's init configuration as a single redirect value

#### Scenario: Per-signal override replaces a derived endpoint

- **WHEN** a cluster is initialized with `--redirect-telemetry <host>` and an explicit override for one signal
- **THEN** that signal uses the override endpoint and the other three remain derived from `<host>`

#### Scenario: A base host that cannot carry the derived endpoints is refused

- **WHEN** a cluster is initialized with `--redirect-telemetry` set to a value carrying a scheme or a port, and at least one signal is not overridden
- **THEN** `init` fails before writing any state, saying the host must be a node of an easy-db-lab cluster and naming the four `--redirect-*-endpoint` overrides for any other stack

#### Scenario: A redirected cluster lands all four signals on another easy-db-lab cluster

- **GIVEN** a receiving AWS cluster with no backend host ports
- **WHEN** a second cluster is brought up with `--redirect-telemetry <receiving control node private IP>` and no overrides
- **THEN** metrics, logs, traces and profiles from the second cluster appear in the receiving cluster's backends
