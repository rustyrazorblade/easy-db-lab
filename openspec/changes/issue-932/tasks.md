## 1. Extract the shared frontmatter-markdown loader (Decision A2)

- [x] 1.1 Create a feature-neutral loader (e.g. `services/FrontmatterMarkdownLoader`) that scans a given classpath package via ClassGraph for `.md` resources, parses the YAML frontmatter `name`/`description`, and returns a generic `MarkdownDocument(name, description, body)`.
- [x] 1.2 Implement per-resource skip-and-continue: a single missing/malformed/unreadable file is skipped and logged (via KotlinLogging), and never zeroes out the whole result set (do not carry over `PromptLoader`'s outer `emptyList()`-on-any-error behavior for a single bad file).
- [x] 1.3 Refactor `mcp/PromptLoader.kt` to be a thin adapter over the shared loader, mapping `MarkdownDocument → PromptResource`.
- [x] 1.4 Keep the existing MCP prompt tests green; add/adjust tests so the shared loader's discovery, parsing, and skip-one-not-skip-all behavior are covered directly.

## 2. Help topic service (discovery + lookup)

- [x] 2.1 Add `HelpTopic(name, description, body)` data class with class-level KDoc.
- [x] 2.2 Add `services/HelpTopicService.kt` — interface (`findAll(): List<HelpTopic>`, `find(key: String): HelpTopic?`) plus default impl backed by the shared loader over package `com.rustyrazorblade.easydblab.help`, with a `by lazy` cached scan.
- [x] 2.3 Implement case-insensitive lookup in `find`; return null for an unknown key (presentation stays in the command).
- [x] 2.4 Test discovery, case-insensitive resolution, the no-arg listing set, and skip-of-malformed via a test resource fixture.

## 3. Help command

- [x] 3.1 Add `commands/Help.kt` — `@Command(name = "help")`, `PicoCommand` + `KoinComponent`, injecting `HelpTopicService`; topic as a single optional `@Parameters(arity = "0..1")`. Class-level KDoc.
- [x] 3.2 No-arg path: print a short `help <topic>` explanation followed by every discovered topic and its description, via `println()`, exit 0.
- [x] 3.3 Known-topic path: print the topic's markdown body verbatim via `println()`, exit 0.
- [x] 3.4 Unknown-topic path (Decision B): print a clean, user-facing error naming the invalid topic and listing the valid topics — no exception-class prefix on the terminal — then force a non-zero exit.
- [x] 3.5 Test all three paths, including the case-insensitive match and the unknown-topic error content + non-zero exit.

## 4. Registration

- [x] 4.1 Register `factory { Help() }` in the Koin commands module.
- [x] 4.2 Add `Help::class` to the `subcommands` list in `CommandLineParser.kt`.

## 5. Seed topic content

- [x] 5.1 Create `src/main/resources/com/rustyrazorblade/easydblab/help/` with the first `.md` files — `provisioning`, `kits`, `stress-testing` — each with a valid YAML frontmatter header (`name`, `description`). (Cassandra config guidance lives in the `cassandra` topic, not a standalone `configs` topic — see 5.4.)
- [x] 5.2 Write each topic as task-oriented guidance (how to perform the operation), not a flag reference; use "database"/"db" rather than "Cassandra" except where Cassandra-specific.
- [x] 5.3 Add six more `.md` files — `profiles`, `connecting`, `querying`, `observability`, `spark`, `cassandra` — each with a valid frontmatter header, task-oriented, and concise/token-efficient for an LLM reader.
- [x] 5.4 Write the `cassandra` topic as an umbrella database-management tutorial: lifecycle (start/stop/restart), version `use`/`install`, and the Cassandra config patch-file workflow (`write-config`/`update-config`/`download-config`); point to `stress-testing` for load. There is no standalone `configs` topic — the config commands are Cassandra-specific.

## 6. Point `-h`/`--help` at the topics (Decision D)

- [x] 6.1 Add a topic-pointer footer to the root `@Command` in `CommandLineParser.kt`, generated from the `HelpTopicService` scan (generic "run `help` for task guides"), not a hardcoded topic list.
- [x] 6.2 Add a per-command footer naming the related `help <topic>` to each subcommand that maps to a topic, derived from the discovered topic set. Leave each command's existing option/description text untouched.
- [x] 6.3 Test that the root usage footer references the topic system and that a topic-mapped command's usage names its related topic, without a hardcoded topic list.

## 7. Documentation

- [x] 7.1 Document the `help` command in `docs/reference/commands.md`.
- [x] 7.2 Note the seed-topic set and the `-h` topic-pointer footers in `docs/reference/commands.md`.

## 8. Verification

- [x] 8.1 Run `./gradlew ktlintFormat` then `./gradlew check` (JDK 21) — all green, including the preserved MCP prompt tests.
- [ ] 8.2 Re-run `./gradlew ktlintFormat` then `./gradlew check` (JDK 21) after the new topics and footer wiring land — all green.
