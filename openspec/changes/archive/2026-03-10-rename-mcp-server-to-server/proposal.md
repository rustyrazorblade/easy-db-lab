## Why

The `server` command exposes much more than just MCP — it includes REST status endpoints (`/status`, `/stress/status`), Swagger docs, a background StatusCache, and an optional MetricsCollector with Redis pub/sub. Referring to it as "MCP Server" throughout docs, specs, and CLAUDE.md files undersells the feature and confuses the mental model. The CLI command is already just `server`, so the documentation should match.

## What Changes

- Rename all references from "MCP Server" / "MCP server" to "Server" / "server" across docs, specs, CLAUDE.md files, and code comments
- Rename the `openspec/specs/mcp-server/` spec directory to `openspec/specs/server/`
- Update the spec content to cover the full server (MCP + REST + background services)
- Rename `mcp/CLAUDE.md` references to describe the server holistically
- Keep the `mcp/` Kotlin package name as-is (it's an implementation detail, not user-facing)
- Keep class names like `McpServer`, `McpToolRegistry` as-is (these describe the MCP protocol layer specifically)

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `mcp-server`: Rename to `server` and broaden requirements to cover REST endpoints, StatusCache, and MetricsCollector — not just MCP protocol features.

## Impact

- **Docs**: `docs/integrations/mcp-server.md` and any references in `docs/SUMMARY.md`
- **Specs**: `openspec/specs/mcp-server/` directory rename
- **CLAUDE.md files**: Root `CLAUDE.md`, `mcp/CLAUDE.md`, `commands/CLAUDE.md`, `configuration/CLAUDE.md`
- **Code comments**: Any comments that say "MCP server" when referring to the server broadly
- **No code/API changes**: Class names, package names, and CLI command name stay the same
