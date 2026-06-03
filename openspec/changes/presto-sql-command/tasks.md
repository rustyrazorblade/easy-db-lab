## 1. Spec

- [ ] 1.1 Verify `openspec/specs/presto/spec.md` covers `REQ-PRS-001` through `REQ-PRS-003`

## 2. Annotation

- [ ] 2.1 Create `annotations/KitCommand.kt`:
  - `@Target(CLASS) @Retention(RUNTIME) annotation class KitCommand(val kit: String, val name: String)`
  - No `description` field — description is read from `@Command(description[0])` at scan time

## 3. KitCommandScanner

- [ ] 3.1 Create `services/KitCommandScanner.kt`:
  - `data class ScannedKitCommand(val kit: String, val name: String, val description: String, val cls: Class<*>)`
  - Interface: `findAll(): Map<String, List<ScannedKitCommand>>` and `forKit(kitName: String): List<ScannedKitCommand>`
  - Implementation: ClassGraph with `enableAnnotationInfo()` + `enableClassInfo()`, cached after first scan
- [ ] 3.2 Register in `ServicesModule.kt` as a Koin singleton: `single<KitCommandScanner> { DefaultKitCommandScanner() }`

## 4. Registration Mechanism

- [ ] 4.1 Update `CommandLineParser`: lazy-init `kitCommandsByKit` via `get<KitCommandScanner>().findAll()`
- [ ] 4.2 Update `registerDynamicKitSubcommands()`: after `factory.buildKitGroup()`, look up `kitCommandsByKit[kitName]` and call `kitGroup.addSubcommand(scanned.name, CommandLine(scanned.cls, KoinCommandFactory()))` for each entry

## 5. kit info Integration

- [ ] 5.1 Update `KitInfo.buildInfoText` signature: add `annotatedCommands: List<Pair<String, String>> = emptyList()`
- [ ] 5.2 Update `buildInfoText` body: merge script-based and annotated commands into a single list, then sort alphabetically before rendering
- [ ] 5.3 Update `KitInfo` class: inject `KitCommandScanner`; in `execute()` call `scanner.forKit(kitName)` and pass results to `buildInfoText`

## 6. Events

- [ ] 6.1 Add `sealed interface Presto` to `events/Event.kt`:
  - `data class SqlQueryOutput(val columns: List<String>, val rows: List<List<String>>)`
  - `data class SqlQueryError(val message: String)`
  - `object SqlUsage`
  - `object NoAppNodes`
- [ ] 6.2 Add `Presto.*` rendering in `ConsoleEventListener`:
  - `SqlQueryOutput` → tabular format (column widths from data)
  - `SqlQueryError` → stderr
  - `SqlUsage` → usage hint to stderr
  - `NoAppNodes` → error to stderr

## 7. Data Classes

- [ ] 7.1 Create `services/presto/PrestoResponse.kt` with `@Serializable` data classes:
  - `PrestoResponse`, `PrestoColumn`, `PrestoStats`, `PrestoError`, `PrestoErrorCode`
  - `data` field typed as `List<List<JsonElement>>?`

## 8. Service

- [ ] 8.1 Create `services/presto/PrestoService.kt` (interface + `DefaultPrestoService`):
  - Constructor: `SocksProxyService`, `HttpClientFactory`, `ClusterStateManager`
  - `PrestoQueryResult` data class alongside the interface
  - `execute(sql: String): Result<PrestoQueryResult>` — POST `/v1/statement`, poll `nextUri`, accumulate rows, handle `error` field
  - Port `8080`, headers `X-Presto-User: easy-db-lab` and `Content-Type: text/plain`
- [ ] 8.2 Register in `ServicesModule.kt`: `single<PrestoService> { DefaultPrestoService(get(), get(), get()) }`

## 9. Command

- [ ] 9.1 Create `commands/presto/PrestoSql.kt`:
  - `@KitCommand(kit = "presto", name = "sql")`
  - `@McpCommand`, `@RequireProfileSetup`, `@RequireSSHKey`, `@Command(name = "sql")`
  - `@Parameters(index = "0", arity = "0..1")` for inline SQL
  - `@Option(names = ["--file", "-f"])` for file path
  - Inject `PrestoService`; emit `Event.Presto.*` based on outcome

## 10. Tests

- [ ] 10.1 Unit test `KitCommandScanner`:
  - `PrestoSql` (on test classpath) is found with correct kit, name, and description
  - `forKit("clickhouse")` returns empty list
- [ ] 10.2 Unit test `KitInfo.buildInfoText` with annotated commands:
  - `presto sql` appears in output with its declared description
  - Assert alphabetical ordering on the merged command name list (the data), not on the rendered string
- [ ] 10.3 If any existing `KitInfoTest` assertions check ordering via string output, move them to assert on the sorted command name list directly — ordering assertions belong on the data, not the rendered text
- [ ] 10.4 Add test to `KitInfoTest`: `presto info shows annotated sql command sorted alphabetically with other commands`
- [ ] 10.5 Unit test `DefaultPrestoService.execute()`:
  - Single-page response → correct `PrestoQueryResult`
  - Two-page response → all rows accumulated
  - Error field set → `Result.failure` with error message
  - No app nodes → `Result.failure` before any HTTP call
- [ ] 10.6 Unit test `PrestoSql` command:
  - Inline SQL → emits `SqlQueryOutput` on success
  - File SQL → reads file, emits `SqlQueryOutput`
  - File not found → emits error event
  - No SQL provided → emits `SqlUsage`
  - Service failure → emits `SqlQueryError`
- [ ] 10.7 Unit test `registerDynamicKitSubcommands()` injection:
  - Kit directory present + scanner returns command for that kit → subcommand added to kit group
  - Kit directory absent → kit group not registered
