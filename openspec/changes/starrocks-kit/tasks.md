## 1. Dependency

- [x] 1.1 Add `mysql-connector-j` as a runtime dependency in `build.gradle.kts`
- [ ] 1.2 Verify `com.mysql.cj.jdbc.Driver` is loadable at runtime (confirm in a test or by running `easy-db-lab starrocks sql "SELECT 1"` after install)

## 2. kit.yaml

- [x] 2.1 Create `src/main/resources/com/rustyrazorblade/easydblab/kits/starrocks/kit.yaml` with name, type `db`, description, version, and collision-check
- [x] 2.2 Add `metrics` block: type scrape, port 8030, path `/metrics`
- [x] 2.3 Add `runtime` block: type pods, selector targeting StarRocksCluster pods
- [x] 2.4 Add `endpoints`: MySQL on 9030 (jdbc, scheme mysql), HTTP on 8030
- [x] 2.5 Add `--version` arg (variable `STARROCKS_VERSION`, default `latest`)
- [x] 2.6 Add `sql` capability: user `root`, driver-class `com.mysql.cj.jdbc.Driver`
- [x] 2.7 Add `install` lifecycle: helm-repo add starrocks, helm install kube-starrocks operator into `starrocks-operator` namespace
- [x] 2.8 Add `start` lifecycle: shell guard for APP_NODE_COUNT, platform-pvs for db nodes, manifest for StarRocksCluster, wait for FE and BE pods Ready, manifest for NodePort service
- [x] 2.9 Add `stop` lifecycle: delete StarRocksCluster CR, delete NodePort service
- [x] 2.10 Add `uninstall` lifecycle: platform-pvs-delete, helm-uninstall starrocks-operator

## 3. Manifest Templates

- [x] 3.1 Create `starrocks-cluster.yaml.template`: `StarRocksCluster` CR with FE nodeSelector `type: app`, FE replicas `__APP_NODE_COUNT__`, BE nodeSelector `type: db`, BE replicas `__DB_NODE_COUNT__`, BE storage using `local-storage-wfc`, image tag `__STARROCKS_VERSION__`
- [x] 3.2 Create `nodeport-service.yaml.template`: NodePort service configured directly in `starRocksFeSpec.service` within the CR — operator manages the service lifecycle, no separate template needed

## 4. Validation

- [ ] 4.1 Run `./gradlew ktlintFormat && ./gradlew test && ./gradlew detekt`
- [ ] 4.2 Confirm kit appears in `easy-db-lab kit list` output after build
- [ ] 4.3 Confirm `easy-db-lab starrocks --help` shows install/start/stop/uninstall subcommands
