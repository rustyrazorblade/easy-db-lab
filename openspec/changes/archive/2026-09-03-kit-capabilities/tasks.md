## 1. KitCapability model

- [ ] 1.1 Add `KitCapability` sealed class to `services/KitConfig.kt`:
  - `@Serializable sealed class KitCapability`
  - `@SerialName("sql") data class Sql(val user: String = "", @SerialName("driver-class") val driverClass: String = "") : KitCapability()`
- [ ] 1.2 Add `capabilities: List<KitCapability> = emptyList()` to `KitConfig`
- [ ] 1.3 Update `installStepModule` `SerializersModule` to include polymorphic registration for `KitCapability` and its subclasses
- [ ] 1.4 Unit test in `KitConfigTest`:
  - `kit.yaml` with `capabilities: [{type: sql, user: easy-db-lab, driver-class: com.example.Driver}]` deserializes to `KitCapability.Sql` with correct fields
  - `kit.yaml` with `capabilities: [{type: sql}]` (no user or driver-class) deserializes with empty string defaults
  - `kit.yaml` with no `capabilities:` block deserializes with empty list
  - `kit.yaml` with an unrecognised capability type is ignored gracefully (no exception)

## 2. KitJdbcSqlService

- [ ] 2.1 Create `services/sql/KitJdbcSqlService.kt`:
  - Constructor: `clusterStateManager: ClusterStateManager`, `endpoint: KitEndpoint`, `user: String`, `connectionFactory: JdbcConnectionFactory = defaultJdbcConnectionFactory`
  - `execute(sql: String): Result<SqlQueryResult>` — resolves node IP via `ServerType.from(endpoint.nodeType)`, builds URL via `endpoint.formatUrl(ip)`, connects, executes, maps ResultSet to `SqlQueryResult`
- [ ] 2.2 Delete `AbstractJdbcSqlService` from `services/sql/SqlQueryService.kt`; retain `SqlQueryResult`, `JdbcConnectionFactory`, and `defaultJdbcConnectionFactory`
- [ ] 2.3 Unit test `KitJdbcSqlService`:
  - Single-row result → correct `SqlQueryResult` with column names and row values
  - NULL column value → represented as `"NULL"` string
  - No nodes of the endpoint's node type in cluster state → `Result.failure` before any connection attempt
  - Connection factory failure → `Result.failure` with original exception

## 3. KitSqlCommand

- [ ] 3.1 Create `commands/kit/KitSqlCommand.kt`:
  - Constructor: `kitName: String`, `endpoint: KitEndpoint`, `user: String`, `driverClass: String`
  - Extends `PicoBaseCommand`; annotated `@McpCommand`, `@RequireProfileSetup`, `@RequireSSHKey`
  - `@Parameters(index = "0", arity = "0..1")` for inline SQL
  - `@Option(names = ["--file", "-f"])` for file path
  - If `driverClass` is non-blank, call `Class.forName(driverClass)` before connecting
  - Inject `ClusterStateManager` via Koin; construct `KitJdbcSqlService` with endpoint and user
  - Emit `Event.Sql.QueryOutput` on success, `Event.Sql.QueryError` on failure, print usage if no SQL provided
  - Strip trailing semicolon before execution
- [ ] 3.2 Unit test `KitSqlCommand`:
  - Inline SQL executes and emits `QueryOutput`
  - Trailing semicolon stripped before execution
  - `--file` reads file and executes
  - Missing file emits `FileNotFound` error
  - No SQL provided: service is not called, usage text printed
  - Service failure emits `QueryError`
  - `driverClass` non-blank: `Class.forName` called before execution
  - `driverClass` blank: `Class.forName` not called

## 4. KitRunnerCommandFactory capability registration

- [ ] 4.1 Convert `KitRunnerCommandFactory` to a Koin component (inject `ClusterStateManager`)
- [ ] 4.2 Add private `buildCapabilityCommands(kitName: String, kitConfig: KitConfig): List<Pair<String, CommandLine>>`:
  - Iterates `kitConfig.capabilities`
  - For `KitCapability.Sql`: finds first `KitEndpoint` with `type == JDBC`; if found, constructs `KitSqlCommand` and wraps in `CommandLine`; if no JDBC endpoint, logs a warning and skips
- [ ] 4.3 Update `buildKitGroup()`: call `buildCapabilityCommands()` and add each result via `groupCL.addSubcommand()`
- [ ] 4.4 Update `ServicesModule.kt`: register `KitRunnerCommandFactory` as a Koin singleton (or factory as appropriate)
- [ ] 4.5 Update `CommandLineParser`: obtain `KitRunnerCommandFactory` via Koin `get()` instead of direct construction
- [ ] 4.6 Unit test `KitRunnerCommandFactory`:
  - Kit config with `sql` capability and a JDBC endpoint → `sql` subcommand present in built kit group
  - Kit config with `sql` capability but no JDBC endpoint → `sql` subcommand absent, no exception
  - Kit config with no capabilities → kit group contains only lifecycle phases and status

## 5. kit.yaml updates

- [ ] 5.1 Add to `kits/presto/kit.yaml`:
  ```yaml
  capabilities:
    - type: sql
      user: easy-db-lab
      driver-class: com.facebook.presto.jdbc.PrestoDriver
  ```
- [ ] 5.2 Add to `kits/clickhouse/kit.yaml`:
  ```yaml
  capabilities:
    - type: sql
      user: default
  ```

## 6. Deletions

- [ ] 6.1 Delete `services/presto/PrestoService.kt` (interface + `DefaultPrestoService`)
- [ ] 6.2 Delete `services/clickhouse/ClickHouseService.kt` (interface + `DefaultClickHouseService`)
- [ ] 6.3 Delete `commands/presto/PrestoSql.kt`
- [ ] 6.4 Delete `commands/clickhouse/ClickHouseSql.kt`
- [ ] 6.5 Remove `PrestoService` and `ClickHouseService` Koin registrations from `ServicesModule.kt`
- [ ] 6.6 Delete `commands/presto/` directory if empty after removing `PrestoSql.kt`
- [ ] 6.7 Delete `commands/clickhouse/` directory if empty after removing `ClickHouseSql.kt`
- [ ] 6.8 Delete test files for deleted classes: `PrestoSqlTest`, `PrestoServiceTest`, `ClickHouseSqlTest`, `ClickHouseServiceTest`

## 7. Tests

- [ ] 7.1 Verify all existing tests pass after deletions (`./gradlew test`)
- [ ] 7.2 Run `./gradlew ktlintFormat && ./gradlew detekt` and fix any issues

## 8. Documentation

- [ ] 8.1 If any user-facing docs reference `presto sql` or `clickhouse sql` as kit-specific commands, update them to describe the generic `sql` capability pattern
