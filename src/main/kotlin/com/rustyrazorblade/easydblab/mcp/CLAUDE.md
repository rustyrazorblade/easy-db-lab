# MCP Server Package

This package implements the Model Context Protocol (MCP) server that exposes CLI commands as tools for AI agents.

## Architecture

```
AI Agent → SSE transport → McpServer → McpToolRegistry → PicoCLI Command → Service Layer
                                ↓
                          StatusCache (background refresh every 30s)
                          ChannelMessageBuffer (captures output)
```

**Transport:** SSE (Server-Sent Events) via Ktor embedded Netty server
**SDK:** `io.modelcontextprotocol:kotlin-sdk` (official MCP Kotlin SDK)
**Endpoint:** `http://{bind}:{port}/sse` (default: `127.0.0.1:0`)

## Files

| File | Purpose |
|------|---------|
| `McpServer.kt` | Main server — Ktor setup, tool/prompt registration, background execution |
| `McpToolRegistry.kt` | Command discovery, JSON schema generation, argument mapping |
| `StatusCache.kt` | Background cluster status refresh (EC2, K3s, EMR, etc.) |
| `ChannelMessageBuffer.kt` | Thread-safe output buffering for status polling |
| `PromptLoader.kt` | Loads prompts from markdown with YAML frontmatter |
| `PromptResource.kt` | Prompt data class |
| `StatusResponse.kt` | Status API data classes (serializable) |

## How Commands Are Exposed

### 1. Mark with `@McpCommand`

```kotlin
@McpCommand
@Command(name = "start", description = ["Start Cassandra"])
class Start : PicoBaseCommand() { ... }
```

### 2. Register in `McpToolRegistry`

Commands are listed statically in `mcpCommandClasses` inside `McpToolRegistry.kt`. Adding a new `@McpCommand` class requires adding it to this list.

### 3. Tool Naming Convention

Names are derived from the package path relative to `commands/`:

| Command Class | Package | Tool Name |
|--------------|---------|-----------|
| `Status` | `commands` | `status` |
| `Start` | `commands.cassandra` | `cassandra_start` |
| `StressStart` | `commands.cassandra.stress` | `cassandra_stress_start` |
| `UpdateConfig` | `commands.cassandra` | `cassandra_update_config` |

Hyphens in `@Command(name=...)` are converted to underscores.

### 4. Schema Generation

`McpToolRegistry` generates JSON schemas from PicoCLI annotations:
- `@Option` fields → JSON properties with types, defaults, descriptions
- `@Mixin` fields → recursively scanned
- Kotlin types mapped to JSON types (String, Int, Boolean, Enum)

## Background Execution Pattern

Tools execute asynchronously to avoid SSE timeouts:

1. Client calls tool (e.g., `cassandra_start`)
2. `createToolHandler()` returns immediately: `"Tool started in background"`
3. Command runs in separate thread (semaphore enforces single execution)
4. Output captured to `ChannelMessageBuffer`
5. Client polls `get_server_status` for progress
6. Status returns: `{ status, command, runtimeSeconds, messages }`

Status values: `"idle"`, `"running"`, `"completed"`

## StatusCache

Periodically fetches live cluster status for the `/status` HTTP endpoint:
- Default refresh: 30 seconds (configurable via `--refresh`)
- Sources: EC2 instance states, K3s pods, SSH (Cassandra version), EMR, OpenSearch, security groups
- Sections: `cluster`, `nodes`, `networking`, `securityGroup`, `spark`, `opensearch`, `s3`, `kubernetes`, `cassandraVersion`, `accessInfo`
- Query params: `?section=nodes` (single section), `?live=true` (force refresh)

## Prompts

Loaded from markdown files with YAML frontmatter in `src/main/resources/com/rustyrazorblade/mcp/`:
- `activate.md` — activates easy-db-lab context for AI agent
- `provision.md` — guided cluster provisioning workflow

Uses ClassGraph for classpath scanning (works inside JARs).

## Adding a New MCP Tool

1. Add `@McpCommand` annotation to your command class
2. Add the class to `mcpCommandClasses` list in `McpToolRegistry.kt`
3. Ensure `@Option` fields have descriptions (used in JSON schema)
4. Test with `McpToolNamespacingTest` for correct name generation
