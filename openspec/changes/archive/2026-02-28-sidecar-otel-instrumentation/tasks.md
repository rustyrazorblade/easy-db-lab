## 1. Packer: Install OTel Java Agent

- [x] 1.1 Create `packer/cassandra/install/install_otel_agent.sh` to download opentelemetry-javaagent.jar to `/usr/local/otel/`
- [x] 1.2 Add the install script to `packer/cassandra/cassandra.pkr.hcl` build sequence

## 2. Sidecar Systemd Service

- [x] 2.1 Update `packer/cassandra/services/cassandra-sidecar.service` to add `EnvironmentFile=-/etc/default/cassandra-sidecar` and append agent flags from JAVA_OPTS

## 3. SetupInstance: Write Sidecar Environment

- [x] 3.1 Extend `SetupInstance` to write `/etc/default/cassandra-sidecar` with OTel and Pyroscope agent JAVA_OPTS on Cassandra nodes

## 4. Testing

- [x] 4.1 Test Packer script locally with `./gradlew testPackerScript -Pscript=cassandra/install/install_otel_agent.sh`
- [x] 4.2 Run full test suite to confirm no regressions

## 5. Documentation

- [x] 5.1 Update `docs/reference/opentelemetry.md` with sidecar instrumentation section
- [x] 5.2 Update CLAUDE.md observability section to mention sidecar instrumentation
