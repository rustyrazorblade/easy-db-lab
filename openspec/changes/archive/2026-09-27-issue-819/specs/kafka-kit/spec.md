## MODIFIED Requirements

### Requirement: Kafka kit exposes Prometheus metrics
The kafka kit SHALL expose Prometheus metrics from the JMX exporter on the broker pods and from the kafka-exporter pod, both on container port `9404`, declared as `type: scrape` entries with a `pod-selector` in `kit.yaml` (`strimzi.io/cluster=kafka,strimzi.io/broker-role=true` and `strimzi.io/cluster=kafka,app.kubernetes.io/name=kafka-exporter`). Only the collector on the node running each pod SHALL scrape it.

#### Scenario: Metrics endpoint declared
- **WHEN** the kafka kit is running
- **THEN** the OTel collector scrapes each broker pod and the kafka-exporter pod at `<pod-ip>:9404/metrics` AND `up` has exactly one series per scraped pod
