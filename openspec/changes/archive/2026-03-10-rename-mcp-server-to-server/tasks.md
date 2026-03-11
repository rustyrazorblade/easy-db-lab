## 1. Spec Rename

- [x] 1.1 Rename `openspec/specs/mcp-server/` directory to `openspec/specs/server/`
- [x] 1.2 Update spec.md content: rename title from "MCP Server" to "Server", broaden description, add requirements for REST endpoints, StatusCache, and MetricsCollector

## 2. User Documentation

- [x] 2.1 Rename `docs/integrations/mcp-server.md` to `docs/integrations/server.md`
- [x] 2.2 Update the page title and content to refer to "Server" instead of "MCP Server" (keep MCP-specific sections clearly labeled as the MCP integration feature)
- [x] 2.3 Update `docs/SUMMARY.md` to reference the new path and title

## 3. CLAUDE.md Files

- [x] 3.1 Update root `CLAUDE.md` — change "MCP server" references to "Server" where they refer to the server broadly (keep MCP-specific references like "Ktor + SSE" for the MCP transport)
- [x] 3.2 Update `src/main/kotlin/com/rustyrazorblade/easydblab/mcp/CLAUDE.md` — update the intro/title to frame it as the Server architecture, with MCP as one protocol layer
- [x] 3.3 Update `src/main/kotlin/com/rustyrazorblade/easydblab/commands/CLAUDE.md` — change "MCP server" to "Server" where applicable
- [x] 3.4 Update `src/main/kotlin/com/rustyrazorblade/easydblab/configuration/CLAUDE.md` if it references "MCP Server" (no changes needed — no references found)

## 4. Code Comments

- [x] 4.1 Search for "MCP Server" / "MCP server" in Kotlin source comments and KDoc, update to "Server" where the comment refers to the server broadly (not the MCP protocol specifically)

## 5. Verification

- [x] 5.1 Search the entire repo for remaining "MCP Server" / "MCP server" references and verify each is intentionally MCP-specific
- [ ] 5.2 Run `./gradlew check` to ensure nothing is broken
