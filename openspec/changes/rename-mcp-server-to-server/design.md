## Context

The server command (`easy-db-lab server`) provides a hybrid HTTP server with MCP protocol support, REST endpoints, background status caching, and optional metrics collection. Throughout the codebase, docs, specs, and CLAUDE.md files, it's referred to as "MCP Server" — which only describes one of its features.

The CLI command is already `server`. The Kotlin package is `mcp/` and classes like `McpServer`, `McpToolRegistry` use the MCP prefix because they specifically implement the MCP protocol layer.

## Goals / Non-Goals

**Goals:**
- Rename all user-facing references from "MCP Server" to "Server" in docs, specs, and CLAUDE.md files
- Rename the spec directory from `openspec/specs/mcp-server/` to `openspec/specs/server/`
- Broaden the spec to cover all server capabilities (REST, StatusCache, MetricsCollector)
- Update docs page title and SUMMARY.md references

**Non-Goals:**
- Renaming the `mcp/` Kotlin package — it's an implementation detail, not user-facing
- Renaming Kotlin classes (`McpServer`, `McpToolRegistry`, etc.) — these specifically implement MCP protocol functionality
- Changing the CLI command name — it's already `server`
- Changing any functional behavior

## Decisions

**Keep `mcp/` package and class names unchanged.** The Kotlin package and classes like `McpServer` specifically implement the MCP protocol layer within the broader server. Renaming them to `Server` would lose precision about what they do. The rename is purely for user-facing documentation where "MCP Server" is used to refer to the whole server.

**Rename the docs page from `mcp-server.md` to `server.md`.** This is the primary user-facing documentation entry point. The URL/path should reflect the broader scope.

**Rename the spec from `mcp-server` to `server`.** Specs describe capabilities, and the server capability is broader than just MCP.

## Risks / Trade-offs

- **[Broken links]** → Search for all references to old paths (`mcp-server.md`, `specs/mcp-server/`) and update them.
- **[Ambiguity]** → "Server" is generic, but context always makes it clear (the tool only has one server mode). When referring specifically to the MCP protocol features, use "MCP integration" or "MCP protocol layer".
