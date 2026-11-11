# Overrides & conflicts — issue-932

## Overrides existing behavior

None — this change only adds new requirements (the new `cli-help-topics` capability). The `PromptLoader` refactor (Decision A2) is an internal implementation change that preserves MCP-prompt behavior, so no existing capability's requirements change.

## Conflicts with other in-flight changes

None found. The other open changes touch unrelated capabilities:

- `issue-888` — `kit-install-command`, `workload-runner`. No overlap with `cli-help-topics`.
- `issue-892` — `profile-command-group`, `setup`. No overlap with `cli-help-topics`.
- `cassandra-local-builds` — `cassandra-local-builds`. No overlap with `cli-help-topics`.

No other open change touches `cli-help-topics`, or the `mcp/PromptLoader.kt` file the A2 refactor edits.
