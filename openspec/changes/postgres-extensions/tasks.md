## 1. Extension Registry

- [x] 1.1 Create `extensions.yaml` in `src/main/resources/.../kits/postgres/` with aliases for `duckdb`, `postgis`, and `timescaledb` using the structure defined in design.md
- [x] 1.2 Create `ExtensionAlias` data class (kotlinx.serialization) with fields: `description`, `image`, `sharedPreloadLibraries`, `createExtensions`
- [x] 1.3 Create `ExtensionRegistry` class that loads and parses `extensions.yaml` from the classpath
- [x] 1.4 Write unit tests for `ExtensionRegistry`: verify all three aliases load correctly, verify unknown alias returns null/throws

## 2. Extension Resolution Logic

- [x] 2.1 Create `ExtensionResolver` that merges multiple `--extension` aliases with `--image`, `--shared-preload-libraries`, and `--create-extension` flags into a resolved `ExtensionConfig` (image, preload list, create-extension list)
- [x] 2.2 Implement multi-alias image conflict detection: when two aliases define different images and no `--image` is provided, throw with an actionable error message naming the conflicting aliases
- [x] 2.3 Implement `__PG_MAJOR__` substitution in the resolved image string
- [x] 2.4 Write unit tests for `ExtensionResolver`: single alias, `--image` override, multi-alias same image, multi-alias conflict, escape-hatch flags augmenting alias, pure escape hatch with no alias

## 3. Template Update

- [x] 3.1 Update `postgres-cluster.yaml.template` to accept optional `__IMAGE__`, `__SHARED_PRELOAD_LIBRARIES__`, and `__POST_INIT_SQL__` placeholders
- [x] 3.2 Ensure the template renders correctly when these fields are absent (default image, no preload section, no postInitSQL section)
- [x] 3.3 Write `TemplateService`-based tests verifying the template renders correctly for: no extensions, single extension, multiple extensions

## 4. postgres start Command Flags

- [x] 4.1 Add `--extension`, `--image`, `--shared-preload-libraries`, and `--create-extension` flags to the postgres `start` command class
- [x] 4.2 Wire `ExtensionResolver` into the start command; pass resolved `ExtensionConfig` into template variable construction
- [x] 4.3 Verify `postgres start` with no extension flags still deploys correctly (no regression)

## 5. postgres extensions Subcommand

- [x] 5.1 Create `PostgresExtensions` command class that reads `extensions.yaml` and prints a table of alias, image template, preload libraries, and create_extensions via `println()`
- [x] 5.2 Register the command under the `postgres` subcommand group
- [x] 5.3 Write a test verifying the command produces output containing all three built-in alias names

## 6. Extension Dashboards (requires live cluster per extension)

- [ ] 6.1 Run `postgres start --extension duckdb` against a live cluster; wait for metrics to flow; run `bin/export-workload-metrics postgres`; author `src/main/resources/.../kits/postgres/dashboards/duckdb.json` from the catalog
- [ ] 6.2 Run `postgres start --extension postgis` against a live cluster; wait for metrics to flow; run `bin/export-workload-metrics postgres`; author `src/main/resources/.../kits/postgres/dashboards/postgis.json` from the catalog
- [ ] 6.3 Run `postgres start --extension timescaledb` against a live cluster; wait for metrics to flow; run `bin/export-workload-metrics postgres`; author `src/main/resources/.../kits/postgres/dashboards/timescaledb.json` from the catalog
- [ ] 6.4 Author `src/main/resources/.../kits/postgres/dashboards/postgres.json` — base postgres metrics dashboard (connection count, query throughput, cache hit ratio, replication lag); requires live cluster without extensions

## 7. Documentation

- [x] 7.1 Update `docs/user-guide/install-postgres.md` with a new Extensions section documenting `--extension`, `--image`, `--shared-preload-libraries`, `--create-extension`, and `postgres extensions`
- [x] 7.2 Add examples for: single alias (`--extension duckdb`), custom image with alias config, pure escape hatch
