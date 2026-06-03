## Context

The Presto kit deploys coordinator + workers on app nodes via Helm. The coordinator exposes the Presto HTTP API on port 8080. All cluster HTTP services (VictoriaMetrics, VictoriaLogs, Tempo) use `HttpClientFactory` for SOCKS-proxy-or-direct routing; `presto sql` follows the same pattern.

**Architectural constraints:**

1. `presto sql` must only be visible when the presto kit is installed. The dynamic kit registration loop guards `if (kitName in commandLine.subcommands.keys) return@forEach` — a static `Presto` in `EasyDBLabCommand` would cause `presto start`/`stop` to be lost.
2. External JARs with kits must work. A static `KClass` list breaks this — classpath scanning is required.
3. `kit info presto` must list `presto sql` in the Commands section with a description.

## Goals / Non-Goals

**Goals:**
- `presto sql <query>` and `presto sql --file <path>` visible and executable only when presto is installed
- `presto start` / `presto stop` continue to work unchanged
- `@KitCommand` is a general mechanism — any JAR on the classpath contributes commands to any installed kit
- `kit info <kit>` lists annotated commands alongside script commands, with description read from `@Command`
- Reuse `HttpClientFactory` for SOCKS-proxy-or-direct routing
- Tabular output with column headers

**Non-Goals:**
- Interactive REPL / multi-statement sessions
- Configurable user/catalog/schema
- Writing results to file

## @KitCommand Annotation

```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class KitCommand(
    /** Name of the installed kit this command belongs to (e.g. "presto"). */
    val kit: String,
    /** PicoCLI subcommand name under the kit group (e.g. "sql"). */
    val name: String,
)
```

`PrestoSql` is annotated:

```kotlin
@KitCommand(kit = "presto", name = "sql")
@McpCommand
@RequireProfileSetup
@RequireSSHKey
@Command(name = "sql", description = ["Execute SQL against the Presto cluster"])
class PrestoSql : PicoBaseCommand() { ... }
```

## KitCommandScanner

A Koin-managed singleton that scans the classpath once and caches results. Used by both `CommandLineParser` (registration) and `KitInfo` (display).

```kotlin
data class ScannedKitCommand(
    val kit: String,
    val name: String,
    val description: String,  // read from @Command(description[0])
    val cls: Class<*>,
)

interface KitCommandScanner {
    fun findAll(): Map<String, List<ScannedKitCommand>>
    fun forKit(kitName: String): List<ScannedKitCommand>
}
```

The description is sourced from the PicoCLI `@Command(description = [...])` annotation on the class — `description[0]` if present, empty string otherwise. This is a single source of truth: the same description appears in both `kit info` output and `--help`.

ClassGraph with `enableAnnotationInfo()` + `enableClassInfo()`, cached after first scan.

## Registration in CommandLineParser

```kotlin
private val kitCommandsByKit: Map<String, List<ScannedKitCommand>> by lazy {
    get<KitCommandScanner>().findAll()
}
```

`registerDynamicKitSubcommands()` injects annotated commands after building each kit group:

```kotlin
val kitGroup = factory.buildKitGroup(kitName, kitDir)

kitCommandsByKit[kitName]?.forEach { scanned ->
    kitGroup.addSubcommand(scanned.name, CommandLine(scanned.cls, KoinCommandFactory()))
}

commandLine.addSubcommand(kitName, kitGroup)
```

## kit info Integration

`KitInfo.buildInfoText` gains an `annotatedCommands` parameter:

```kotlin
fun buildInfoText(
    config: KitConfig,
    templateFiles: List<String>,
    annotatedCommands: List<Pair<String, String>> = emptyList(), // name → description
): String
```

Annotated commands are appended to the Commands section after the script-based commands, with their `@Command` description as the display text.

`KitInfo.execute()` injects `KitCommandScanner`:

```kotlin
class KitInfo : BaseInstallCommand() {
    private val scanner: KitCommandScanner by inject()

    override fun execute() {
        val source = resolver.resolve(kitName)
        val config = resolver.loadInstallConfig(source) ?: error("No kit.yaml found for '$kitName'")
        val templateFiles = resolver.listTemplateFiles(source).map { it.name }
        val annotatedCommands = scanner.forKit(kitName).map { it.name to it.description }
        println(buildInfoText(config, templateFiles, annotatedCommands))
    }
}
```

Example `kit info presto` Commands section (alphabetical):

```
Commands:
  presto sql             Execute SQL against the Presto cluster
  presto start           Start the workload
  presto status          Show running state and connection endpoints
  presto stop            Stop the workload
  presto uninstall       Uninstall the kit and remove all resources
  presto update-catalogs (script)
```

## Presto HTTP API

```
POST http://<app_node_private_ip>:8080/v1/statement
Content-Type: text/plain
X-Presto-User: easy-db-lab

<SQL>
```

Pagination: follow `nextUri` via `GET` until null. Columns and data arrive once results begin streaming.

## Data Classes (`kotlinx.serialization`)

```kotlin
@Serializable
data class PrestoResponse(
    val id: String = "",
    val infoUri: String = "",
    val nextUri: String? = null,
    val columns: List<PrestoColumn>? = null,
    val data: List<List<JsonElement>>? = null,
    val stats: PrestoStats? = null,
    val error: PrestoError? = null,
)
@Serializable data class PrestoColumn(val name: String, val type: String)
@Serializable data class PrestoStats(val state: String = "")
@Serializable data class PrestoError(val message: String = "", val errorCode: PrestoErrorCode? = null)
@Serializable data class PrestoErrorCode(val code: Int = 0, val name: String = "")
```

## PrestoService

```kotlin
interface PrestoService {
    fun execute(sql: String): Result<PrestoQueryResult>
}
data class PrestoQueryResult(val columns: List<String>, val rows: List<List<String>>)
```

`DefaultPrestoService` dependencies: `SocksProxyService`, `HttpClientFactory`, `ClusterStateManager`

**Execution loop:**
1. Load cluster state; get first app node private IP from `ServerType.Stress` (fail fast if none)
2. If `!tailscaleActive`, call `socksProxyService.ensureRunning(controlHost)`
3. `POST /v1/statement` with SQL body + headers
4. Loop: deserialize; accumulate columns + rows; `GET nextUri` until null
5. If `error != null` at any point, return `Result.failure`
6. Return `PrestoQueryResult(columns, rows)`

## Event Types

```kotlin
sealed interface Presto {
    data class SqlQueryOutput(val columns: List<String>, val rows: List<List<String>>) : Presto, Event
    data class SqlQueryError(val message: String) : Presto, Event
    object SqlUsage : Presto, Event
    object NoAppNodes : Presto, Event
}
```

## Console Output Format

```
 col1   | col2   | col3
--------+--------+--------
 value1 | value2 | value3

(1 row)
```

## Decisions

### No static `Presto` parent in `EasyDBLabCommand`

`presto sql` must only appear when installed. Static registration loses `start`/`stop`.

### Classpath scanning over static list

External JARs with kits won't be known at compile time. ClassGraph is already a dependency.

### `KitCommandScanner` as a Koin singleton

Both `CommandLineParser` and `KitInfo` need it. A singleton avoids scanning twice.

### Description from `@Command`, not a field on `@KitCommand`

`@Command(description)` is the single source of truth — it drives both `--help` output and `kit info` display. No duplication, no divergence risk.

### `buildInfoText` signature extension with default

`annotatedCommands` defaults to `emptyList()` so all existing `KitInfoTest` tests pass without modification.

### Ordering assertions target data, not rendered output

The sorted command list should be extractable for testing (e.g. via a package-private helper that returns the merged, sorted `List<Pair<String, String>>`). Assertions on alphabetical order go there — not on the rendered string, which conflates sorting logic with formatting.

## Risks / Trade-offs

- **Scan latency**: One additional ClassGraph scan at startup. Cached after first call. `PromptLoader` and `InstallTemplateResolver` already pay this cost.
- **Long-running queries**: OkHttpClient read timeout (30s) applies per page. Acceptable for lab use.
- **Large result sets**: All rows accumulated in memory. Users should add `LIMIT`.
