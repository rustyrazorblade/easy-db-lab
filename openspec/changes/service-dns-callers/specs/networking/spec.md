## ADDED Requirements

### Requirement: Observability backends and Grafana are published on fixed NodePorts on owned nodes

Where the environment owns its nodes, the Grafana, Mimir, Loki, Tempo and Pyroscope Services SHALL be `type: NodePort`, publishing only their client ports on fixed node ports held in `Constants`: Grafana 31300, Mimir 31009, Loki 31100, Tempo query 31200, Tempo OTLP gRPC 31320 and Pyroscope 31040. gRPC, memberlist and other internal ports SHALL NOT be published. No published port SHALL equal a kit NodePort or the Hubble UI NodePort. Where the environment does not own its nodes, these Services SHALL stay ClusterIP. Callers outside the pod network — the CLI under Socks or Direct exposure, browsers, redirect senders, EMR and operator scripts — SHALL reach the backends and Grafana through these ports.

#### Scenario: Owned nodes publish the client ports as NodePorts
- **GIVEN** an AWS environment
- **WHEN** the observability Services are rendered
- **THEN** each of the five is `type: NodePort` with exactly its client ports on the fixed node ports
- **AND** no internal port carries a node port

#### Scenario: Each NodePort reaches its backend
- **GIVEN** the observability stack deployed on K3s with owned-node exposure
- **WHEN** a client connects to the node address on each published node port
- **THEN** it reaches the matching backend or Grafana

#### Scenario: Off owned nodes the Services stay ClusterIP
- **GIVEN** an environment that does not own its nodes
- **WHEN** the observability Services are rendered
- **THEN** each is `type: ClusterIP` and carries no node port

#### Scenario: Published ports do not collide
- **WHEN** the observability NodePort constants are compared with every packaged kit NodePort and the Hubble UI NodePort
- **THEN** no value repeats
