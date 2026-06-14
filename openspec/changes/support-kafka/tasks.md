## 1. Endpoint Type: Add KAFKA to KitEndpoint.EndpointType

- [x] 1.1 Add `KAFKA` variant (serialized as `"kafka"`) to `KitEndpoint.EndpointType` enum in `KitConfig.kt`
- [x] 1.2 Add `EndpointType.KAFKA` branch to `KitEndpoint.formatUrl()` returning `"$ip:$port"`
- [x] 1.3 Add unit tests to `KitConfigTest` (or equivalent) verifying `formatUrl` for KAFKA type returns `host:port` with no scheme

## 2. Kafka Kit Resource Files

- [x] 2.1 Create `src/main/resources/com/rustyrazorblade/easydblab/kits/kafka/kit.yaml` with `type: db`, dual endpoints (internal port 9092, external port 30092), metrics scrape on port 30093, args (`--version`, `--brokers`, `--storage-size`), and Helm install/start/stop/uninstall lifecycle steps
- [x] 2.2 Create `src/main/resources/com/rustyrazorblade/easydblab/kits/kafka/values.yaml.template` with KRaft enabled, ZooKeeper disabled, broker replicaCount `__BROKERS__`, storage `__STORAGE_SIZE__`, image tag `__KAFKA_VERSION__`, external access NodePort 30092 advertised at `__PRIVATE_IP__`, and JMX metrics exporter on port 30093
- [x] 2.3 Create `src/main/resources/com/rustyrazorblade/easydblab/kits/kafka/metrics-catalog.json` with key Kafka JMX metrics (broker byte rates, consumer lag, under-replicated partitions, request latency)
- [x] 2.4 Create `src/main/resources/com/rustyrazorblade/easydblab/kits/kafka/README.md.template` documenting bootstrap addresses, CDC use case, and benchmarking use case

## 3. Validation

- [x] 3.1 Run `./gradlew ktlintFormat` and resolve any style issues
- [x] 3.2 Run `./gradlew test` and confirm all tests pass
- [x] 3.3 Run `./gradlew detekt` and resolve any code quality issues
