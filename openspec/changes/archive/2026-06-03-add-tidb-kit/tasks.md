## 1. Gradle Dependencies

- [x] 1.1 Add `mysql-connector-j` version entry to `gradle/libs.versions.toml`
- [x] 1.2 Add `mysql-connector-j` library alias to `gradle/libs.versions.toml`
- [x] 1.3 Add `implementation(libs.mysql.connector.j)` to `build.gradle.kts`

## 2. Kit Directory and kit.yaml

- [x] 2.1 Create `src/main/resources/com/rustyrazorblade/easydblab/kits/tidb/` directory
- [x] 2.2 Create `kit.yaml` with: `type: db`, `collision-check: true`, metrics scrape on port 10080, endpoints (MySQL port 4000), args (`--version`, `--replicas`), `sql` capability with `com.mysql.cj.jdbc.Driver` and user `root`
- [x] 2.3 Add `install:` steps: TiDB Operator Helm repo (`https://charts.pingcap.org`) and Helm chart install (`pingcap/tidb-operator`)
- [x] 2.4 Add `start:` steps: pre-flight shell check, apply `tidbcluster.yaml.template`, wait for all component pods, apply `nodeport-service.yaml.template`
- [x] 2.5 Add `stop:` steps: delete `TidbCluster` CR, delete NodePort service
- [x] 2.6 Add `uninstall:` steps: Helm uninstall of tidb-operator (no platform-pvs-delete needed; local-path provisioner handles cleanup)
- [x] 2.7 Add `runtime:` block with pod selector targeting TiDB pods

## 3. Manifest Templates

- [x] 3.1 Create `tidbcluster.yaml.template` — `TidbCluster` CRD with:
  - PD: 1 replica, scheduled on app nodes via node selector
  - TiDB SQL: `__APP_NODE_COUNT__` replicas, scheduled on app nodes
  - TiKV: `__REPLICAS__` replicas, 10Ti storage, scheduled on db nodes
  - TiFlash: `__REPLICAS__` replicas, 10Ti storage, scheduled on db nodes
  - Version: `__TIDB_VERSION__` for all components
- [x] 3.2 Create `nodeport-service.yaml.template` — NodePort service exposing MySQL port 4000 and metrics port 10080 from TiDB SQL layer pods
- [x] 3.3 Create `README.md.template` — basic usage doc with connection instructions and TiFlash replica hint

## 4. Pre-flight Shell Check

- [x] 4.1 Verify the pre-flight shell check in `start:` correctly reads `DB_NODE_COUNT` and `APP_NODE_COUNT` env vars and exits non-zero with a clear message when either is `< 1`

## 5. Verification

- [x] 5.1 Run `./gradlew ktlintFormat && ./gradlew build` to confirm no compilation errors
- [x] 5.2 Verify `easy-db-lab tidb --help` shows the kit with correct args
- [x] 5.3 Update `CLAUDE.md` domain description to include TiDB as a supported database
