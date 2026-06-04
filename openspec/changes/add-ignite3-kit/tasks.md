## 1. Gradle Dependency

- [x] 1.1 Add the Apache Ignite 3 thin client dependency (`org.apache.ignite:ignite-client`) to `build.gradle.kts`
- [x] 1.2 Verify the Ignite JDBC driver class (`org.apache.ignite.jdbc.IgniteJdbcDriver`) is resolvable at runtime after adding the dependency

## 2. Kit YAML

- [x] 2.1 Create `src/main/resources/com/rustyrazorblade/easydblab/kits/ignite3/kit.yaml` with:
  - `name: ignite3`, `type: db`
  - `--replicas` arg (default `${DB_NODE_COUNT}`)
  - `--storage` arg (values: `aimem`, `aipersist`, `rocksdb`; default `aipersist`)
  - `--version` arg for the Ignite 3 Docker image tag (default: `3.0.0`)
  - Endpoints for REST (port 10300) and JDBC (port 10800)
  - `sql` capability with `driver-class: org.apache.ignite.jdbc.IgniteJdbcDriver`
  - `start`, `stop`, and `uninstall` step sequences

## 3. K8s Manifest Templates

- [x] 3.1 Create `configmap.yaml.template` — Ignite `ignite-config.conf` with storage profile (`__STORAGE_PROFILE__`), network discovery via headless service, and node name from pod metadata
- [x] 3.2 Create `statefulset.yaml.template` — StatefulSet with `__REPLICAS__` replicas, `apache/ignite3:__VERSION__` image (default `3.1.0`), PVC for persistence, env vars for `IGNITE_NODE_NAME` and `IGNITE_WORK_DIR`, ports 10300 / 10800 / 3344
- [x] 3.3 Create `services.yaml.template` — headless Service (`ignite-svc-headless`, ClusterIP: None, port 3344) for pod discovery
- [x] 3.4 Create `nodeport-service.yaml.template` — NodePort Service exposing port 10300 (REST) and 10800 (thin client) on db node ports
- [x] 3.5 Create `cluster-init-job.yaml.template` — K8s Job that runs `ignite3 cluster init --name=ignite --url=http://ignite-svc-headless:10300`

## 4. Start / Stop / Uninstall Sequences in kit.yaml

- [x] 4.1 Wire the `start` sequence: apply ConfigMap → apply headless Service + StatefulSet → wait for pods Ready → apply cluster-init Job → wait for Job completion → configure OTLP via REST → apply NodePort Service
- [x] 4.2 Wire the `stop` sequence: delete StatefulSet → delete NodePort Service → delete cluster-init Job (PVCs retained)
- [x] 4.3 Wire the `uninstall` sequence: delete headless Service → delete ConfigMap → delete PVCs (platform-pvs-delete)
- [x] 4.4 Add shell step to configure OTLP exporter after cluster init:
  ```bash
  curl -s -X PATCH http://localhost:10300/management/v1/configuration/cluster \
    -H 'Content-Type: application/json' \
    -d '{"ignite":{"metrics":{"exporters":{"otel":{"exporterName":"otlp","endpoint":"http://${CONTROL_HOST_PRIVATE}:4318/v1/metrics","protocol":"http/protobuf"}}}}}'
  ```

## 5. OpenSpec Spec File

- [x] 5.1 Move `openspec/changes/add-ignite3-kit/specs/ignite3/spec.md` to `openspec/specs/ignite3/spec.md` as the canonical spec location

## 6. Documentation

- [x] 6.1 Add a documentation page for the `ignite3` kit in `docs/` covering: what Ignite 3 is, how to install/start/stop/uninstall, storage profile options, and the SQL capability
- [x] 6.2 Add `ignite3` to the kit navigation in `docs/SUMMARY.md` (or equivalent)

## 7. GitHub Issue

- [ ] 7.1 Update issue #668 body with the clarified scope (Ignite 3 only, ignite2 tracked separately) — needs manual gh permission
