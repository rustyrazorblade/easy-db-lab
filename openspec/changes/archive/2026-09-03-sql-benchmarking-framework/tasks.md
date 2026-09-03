## 1. Endpoint model: new types and database field

- [x] 1.1 Add `POSTGRESQL` and `MYSQL` to `KitEndpoint.EndpointType` enum in
  `services/KitConfig.kt` with `@SerialName("postgresql")` and `@SerialName("mysql")`
- [x] 1.2 Add optional `database: String = ""` field to `KitEndpoint` data class
- [x] 1.3 Update `KitEndpoint.formatUrl()` to handle `POSTGRESQL` and `MYSQL` the same
  as `NATIVE` (return `"$ip:$port"`)
- [x] 1.4 Unit tests in `KitConfigTest`:
  - `postgresql` endpoint deserializes with correct type, port, and `database` field
  - `mysql` endpoint deserializes with correct type, port, and `database` field
  - Existing endpoint types (`jdbc`, `http`, `native`, `cql`) unaffected
  - `database` field absent → empty string default

## 2. Arg model: kit-ref type

- [x] 2.1 Add `KIT_REF` to `KitArgSpec.ArgType` enum in `services/KitConfig.kt` with
  `@SerialName("kit-ref")`
- [x] 2.2 Add optional `capability: String = ""` field to `KitArgSpec` data class
  (advisory metadata; not enforced in this version)
- [x] 2.3 Unit tests in `KitConfigTest`:
  - Arg with `type: kit-ref` deserializes to `ArgType.KIT_REF`
  - Arg with `type: kit-ref` and `capability: sql` deserializes with `capability = "sql"`
  - Existing arg types (`string`, `int`, `boolean`, `float`) unaffected

## 3. KitEndpointResolver service

- [x] 3.1 Create `services/KitEndpointResolver.kt`:
  - Interface: `resolveTargetVars(targetKitDir: File, clusterState: ClusterState): Map<String, String>`
  - Implementation reads `kit.yaml` from `targetKitDir`; if missing or unreadable, returns empty map
  - For each endpoint in the target kit's `endpoints:` list, resolves the node IP from
    `clusterState` using the endpoint's `node-type` (via `ServerType.from()`)
  - If no node of that type exists, skips that endpoint (no error)
  - Produces vars per endpoint type:
    - `JDBC` → `TARGET_JDBC_URL` (via `endpoint.formatUrl(ip)`), `TARGET_JDBC_USER`
      (from first `sql` capability `user`), `TARGET_JDBC_DRIVER` (from `driver-class`)
    - `POSTGRESQL` → `TARGET_PG_HOST`, `TARGET_PG_PORT`, `TARGET_PG_USER`,
      `TARGET_PG_DATABASE`
    - `MYSQL` → `TARGET_MYSQL_HOST`, `TARGET_MYSQL_PORT`, `TARGET_MYSQL_USER`,
      `TARGET_MYSQL_DATABASE`
    - `HTTP` → `TARGET_HTTP_URL` (via `endpoint.formatUrl(ip)`)
- [x] 3.2 Register `KitEndpointResolver` as a Koin singleton in `ServicesModule.kt`
- [x] 3.3 Unit tests in `KitEndpointResolverTest`:
  - JDBC endpoint with db node → correct `TARGET_JDBC_URL`, `TARGET_JDBC_USER`,
    `TARGET_JDBC_DRIVER`
  - POSTGRESQL endpoint with db node → correct `TARGET_PG_*` vars
  - MYSQL endpoint with db node → correct `TARGET_MYSQL_*` vars
  - Target kit directory missing → returns empty map, no exception
  - Target kit has unparseable `kit.yaml` → returns empty map, no exception
  - Endpoint node-type has no nodes in cluster state → that endpoint skipped, others
    still injected
  - Multiple endpoint types on one target kit → all vars injected

## 4. Install: instance directory naming

- [x] 4.1 In `KitInstallCommand.execute()`, detect if any arg has `type == ArgType.KIT_REF`
- [x] 4.2 If a `kit-ref` arg is present, read its resolved value from `argValues` and
  compute `instanceName = "${config.name}-${targetValue}"`
- [x] 4.3 Pass `instanceName` (instead of `config.name`) as the `kitName` parameter to
  `renderAndWrite()`
- [x] 4.4 Unit tests in `KitInstallCommandTest`:
  - Kit config with `kit-ref` arg and `--target clickhouse` → directory created as
    `sysbench-clickhouse`
  - Kit config with no `kit-ref` arg → directory created as kit's own name (unchanged)

## 5. Runtime: TARGET_* env var injection

- [x] 5.1 In `KitRunnerCommand.buildAugmentedEnv()`, check if the installed kit config
  has any arg with `type == ArgType.KIT_REF`
- [x] 5.2 If found, read the target kit name from `resolvedArgs` using the arg's `variable`
  field (typically `TARGET`)
- [x] 5.3 Resolve `targetKitDir = File(kitDir.parentFile, targetKitName)` and call
  `KitEndpointResolver.resolveTargetVars(targetKitDir, clusterState)`
- [x] 5.4 Merge the returned vars into the augmented env (highest priority — overrides
  any colliding key)
- [x] 5.5 Inject `KitEndpointResolver` into `KitRunnerCommand` via Koin
- [x] 5.6 Unit tests in `KitRunnerCommandTest`:
  - Kit with `kit-ref` arg and valid target dir → `TARGET_JDBC_URL` present in env
  - Kit with `kit-ref` arg and missing target dir → no `TARGET_*` vars, no exception
  - Kit with no `kit-ref` arg → resolver not called, env unchanged

## 6. Update existing kit.yaml files

- [x] 6.1 Add `database` field to the ClickHouse `JDBC` endpoint in
  `kits/clickhouse/kit.yaml` (value: `"default"`)
- [x] 6.2 Add `database` field to the Presto `JDBC` endpoint in
  `kits/presto/kit.yaml` (value: `""` or omit — Presto uses catalog.schema syntax)

## 7. Documentation

- [x] 7.1 Update `docs/` (user-facing) with a section on bench kits: how to install
  with `--target`, the `TARGET_*` env vars available, and the multi-instance naming
  convention
- [x] 7.2 Update `kits/CLAUDE.md` or equivalent internal guide with the `kit-ref` arg
  type, `postgresql`/`mysql` endpoint types, and the `database` field

## 8. Sysbench reference kit

- [x] 8.1 Create `src/main/resources/com/rustyrazorblade/easydblab/kits/sysbench/kit.yaml`:
  - `type: app`, `collision-check: true`
  - `--target` arg: `type: kit-ref`, `variable: TARGET`, `capability: sql`, required
  - `--threads` arg: `type: int`, default `4`
  - `--duration` arg: `type: int`, description "benchmark duration in seconds", default `60`
  - `--workload` arg: `type: string`, default `oltp_read_write`
    (sysbench built-in: `oltp_read_write`, `oltp_read_only`, `oltp_write_only`)
  - `--scale` arg: `type: int`, description "number of rows per table (thousands)", default `10`
  - `--tables` arg: `type: int`, default `10`

- [x] 8.2 Create `bin/prepare.sh`:
  - Detect driver from env: if `TARGET_MYSQL_HOST` is set use `--db-driver=mysql` with
    `TARGET_MYSQL_*` vars; else if `TARGET_PG_HOST` is set use `--db-driver=pgsql` with
    `TARGET_PG_*` vars; else exit 1 with a clear message
  - Run `sysbench ... --tables=${TABLES} --table-size=${SCALE}000 ${WORKLOAD} prepare`
    via `kubectl run sysbench-prepare-${KIT_NAME}` with `docker.io/severalnines/sysbench`
    image, `--restart=Never`, using `KUBECONFIG`
  - Wait for pod to complete (`kubectl wait --for=condition=complete`) then delete it

- [x] 8.3 Create `bin/start.sh`:
  - Same driver detection as `prepare.sh`
  - Run sysbench benchmark as a long-running pod:
    `kubectl run sysbench-${KIT_NAME}` with `--restart=Never`,
    `sysbench ... --threads=${THREADS} --time=${DURATION} ${WORKLOAD} run`
  - Tail pod logs so output is visible to the user

- [x] 8.4 Create `bin/stop.sh`:
  - Delete the running sysbench pod: `kubectl delete pod sysbench-${KIT_NAME} --ignore-not-found`
  - Run sysbench cleanup job (same pattern as prepare): `${WORKLOAD} cleanup`

- [x] 8.5 Validate end-to-end against TiDB (MySQL wire) and PostgreSQL (PG wire):
  - `easy-db-lab kit install sysbench --target tidb` → creates `sysbench-tidb/`
  - `easy-db-lab sysbench-tidb prepare` → loads test data
  - `easy-db-lab sysbench-tidb start` → benchmark runs and outputs TPS/latency
  - `easy-db-lab sysbench-tidb stop` → pod deleted, cleanup runs
  - Repeat with `--target postgres`
