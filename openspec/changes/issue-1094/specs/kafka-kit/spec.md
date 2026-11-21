## MODIFIED Requirements

### Requirement: Kafka kit exposes dual bootstrap endpoints
The kafka kit SHALL expose two endpoints:
- `Kafka Internal` — type `kafka`, port `9092`, `node-type: db` — advertised as `kafka-kafka-bootstrap.<namespace>.svc:9092` (the cluster namespace) for in-cluster consumers
- `Kafka External` — type `kafka`, port `30092`, `node-type: db` — NodePort advertised as the db node's private IP for external consumers

#### Scenario: In-cluster consumer uses internal endpoint
- **WHEN** a pod in the cluster uses `kafka-kafka-bootstrap.<namespace>.svc:9092` as the bootstrap server
- **THEN** the connection succeeds and Kafka metadata is returned

#### Scenario: External consumer uses NodePort endpoint
- **WHEN** an external client connects to `<db-node-private-ip>:30092`
- **THEN** the connection succeeds and Kafka metadata is returned


#### Scenario: Strimzi reconciles in the cluster namespace
- **WHEN** the cluster namespace is `lab-a` and `kafka start` runs
- **THEN** the Strimzi operator watches `lab-a` and the Kafka cluster in `lab-a` becomes ready
