## Why

The CLI has no in-tool guide to common operations. A user who wants to know how to provision a cluster, connect to the database, run kits, or drive stress tests must leave the tool and read the mdbook docs, which are not present in a Homebrew install. A set of task-oriented `help` topics, packaged with the distribution, puts that guidance one command away, and the standard `-h` output points to it so the topics are discoverable from the first command a user types. This is a scoped first slice; the broader per-command catalog (issue 128) and JSON output (issue 657) remain separate follow-ons.

## What Changes

- Add a top-level `help` command that takes an optional single topic argument.
- `help` with no argument prints a short explanation of `help <topic>`, then lists every discovered topic with its one-line description; exits 0.
- `help <topic>` prints that topic's markdown body verbatim to stdout; exits 0. Topic matching is case-insensitive.
- `help <unknown>` prints a clean error naming the invalid topic and listing the valid topics, then exits non-zero. The error is plain user-facing text, with no Java exception-class prefix.
- Ship nine seed topics as packaged markdown resources, each task-oriented (how to perform the operation, not a flag reference): `provisioning`, `kits`, `stress-testing`, `profiles`, `connecting`, `querying`, `observability`, `spark`, and `cassandra`. The `cassandra` topic is an umbrella guide to managing the database on a running cluster — lifecycle (start/stop/restart), version select/install, and the Cassandra config patch-file workflow (`write-config`/`update-config`) — pointing into `stress-testing` for load. Cassandra config lives here rather than in a standalone `configs` topic because the config commands are Cassandra-specific.
- Wire the standard PicoCLI `-h`/`--help` output to point at the topic system: the root usage carries a footer directing users to `help`, and each subcommand that maps to a topic carries a footer naming the related `help <topic>`. The footer text is generated from the `HelpTopicService` scan, not a second hardcoded topic list, so adding a topic keeps updating the pointers automatically.
- Topics are discovered by scanning packaged classpath resources, not a hardcoded Kotlin list. Adding a topic is adding a markdown file with a valid frontmatter header; no Kotlin change is required.
- Each topic file carries a YAML frontmatter header with `name` (the topic key) and `description` (the one-line summary). A file with a missing or malformed header is skipped and logged; the remaining topics still list and resolve.
- Extract the existing frontmatter-markdown discovery and parsing logic (today embedded in `mcp/PromptLoader.kt`) into a shared, feature-neutral loader that both the MCP prompt loader and the new help-topic service consume. This removes duplication at the moment a second consumer appears.
- Update `docs/reference/commands.md` to document the new `help` command.

## Capabilities

### New Capabilities
- `cli-help-topics`: A top-level `help` command that discovers task-oriented topic guides from packaged markdown resources and prints them, with a no-argument topic listing and a clean unknown-topic error. The standard `-h`/`--help` output points to the topic system — a root-usage footer and per-command footers generated from the same topic scan.

### Modified Capabilities
<!-- None. The PromptLoader refactor is an internal implementation change; MCP prompt behavior (a spec-level contract elsewhere) is unchanged, so no existing capability's requirements change. -->

## Impact

- New command: `src/main/kotlin/com/rustyrazorblade/easydblab/commands/Help.kt`.
- New service: `src/main/kotlin/com/rustyrazorblade/easydblab/services/HelpTopicService.kt` (interface + default impl).
- New shared loader extracted from `mcp/PromptLoader.kt` (e.g. `services/FrontmatterMarkdownLoader` returning a generic `MarkdownDocument`); `PromptLoader` becomes a thin adapter over it. Its existing tests must stay green.
- New resource directory: `src/main/resources/com/rustyrazorblade/easydblab/help/*.md` (nine seed topics).
- Command registration: a `factory { Help() }` in the Koin commands module and `Help::class` in the `subcommands` list in `CommandLineParser.kt`.
- `-h` footer integration: a root-usage footer on the top-level `@Command` in `CommandLineParser.kt`, and per-command footers on the subcommands that map to a topic, both fed from the `HelpTopicService` scan rather than a hardcoded list.
- Documentation: `docs/reference/commands.md`.
- No database, no cluster state, no new external dependency. Read-only display command: uses `println()`, emits no event.
