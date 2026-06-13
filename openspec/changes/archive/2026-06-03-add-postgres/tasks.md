## 1. Dependencies

- [x] 1.1 Add `postgresql-jdbc` version entry to `gradle/libs.versions.toml`
- [x] 1.2 Add `postgresql-jdbc` library alias to `gradle/libs.versions.toml` libraries section
- [x] 1.3 Add `implementation(libs.postgresql.jdbc)` to `build.gradle.kts`

## 2. PostgreSQL Kit — Core Files

- [x] 2.1 Create `kits/postgres/kit.yaml` with install/start/stop/uninstall phases, `--version` and `--instances` and `--size` args, JDBC endpoint on port 30432, metrics on 30987, and `sql` capability
- [x] 2.2 Create `kits/postgres/postgres-cluster.yaml.template` — CNPG `Cluster` CR with `__INSTANCES__`, `__POSTGRES_VERSION__`, `__STORAGE_SIZE__` template variables
- [x] 2.3 Create `kits/postgres/nodeport-service.yaml.template` — NodePort 30432 (postgres), 30987 (metrics)
- [x] 2.4 Create `kits/postgres/README.md.template` with connection info and usage examples

## 3. Presto Integration

- [x] 3.1 Create `kits/presto/catalogs/postgres.properties.template` with `connector.name=postgresql` and CNPG primary service URL

## 4. Tests

- [x] 4.1 Add test in `KitRunnerCommandFactoryTest` verifying that the `postgres` kit registers a `sql` subcommand when a JDBC endpoint and `sql` capability are declared
- [x] 4.2 Verify existing ClickHouse `--version` flag rename test passes (`KitInfoTest` — already updated)

## 5. Build Verification

- [x] 5.1 Run `./gradlew ktlintFormat && ./gradlew test && ./gradlew detekt` — all checks pass
