## Context

The CLI (PicoCLI-based) has no in-tool operational guide. The repo already discovers packaged markdown with `name`/`description` YAML frontmatter in `mcp/PromptLoader.kt` (a ClassGraph scan with per-resource skip-and-continue on malformed files), and already ships classpath-scanned resources that work from a Homebrew install with no source tree (MCP prompts, kit commands, install templates). This change adds a `help` command that reuses that proven mechanism, and takes the arrival of a second frontmatter-parsing consumer as the moment to extract the shared logic.

## Goals / Non-Goals

**Goals:**
- One top-level `help` command: no-arg topic listing, `help <topic>` body print, clean unknown-topic error with a non-zero exit.
- Topic discovery from packaged classpath resources, no hardcoded list; adding a topic is adding a file.
- Case-insensitive topic matching; skip-and-continue on a malformed file (skip-one, never skip-all).
- A single shared frontmatter-markdown loader, consumed by both `PromptLoader` and the new `HelpTopicService`.
- Works from a Homebrew install with no source checkout.
- The standard PicoCLI `-h`/`--help` output points to the topic system — a root-usage footer and per-command footers — generated from the discovered topic set, so the pointers never drift from the packaged topics.

**Non-Goals:**
- The broad per-command extended-help catalog (issue 128).
- JSON or structured help output (issue 657).
- Regenerating or replacing the mdbook docs under `docs/`.
- Rewriting per-command PicoCLI `--help` bodies. In scope is only appending a topic-pointer footer to the root usage and to the subcommands that map to a topic; the existing option/description text is untouched.
- Paging, search, syntax highlighting, or markdown-to-ANSI rendering — raw markdown is printed as-is.
- Variable substitution or cluster-state interpolation in topic content.

## Decisions

**Command/service split.** `commands/Help.kt` (`@Command(name="help")`, `PicoCommand` + `KoinComponent`) is a thin display command: it injects `HelpTopicService`, formats, and prints via `println()`. The topic is a single optional `@Parameters(arity="0..1")` positional — sanctioned for non-kit `show`/`info`-style commands. `services/HelpTopicService.kt` (interface + default impl) owns discovery, case-insensitive lookup, and a `by lazy` cached scan, mirroring `DefaultKitCommandScanner`. `HelpTopic(name, description, body)` is a distinct data class so help does not inherit MCP-prompt semantics.

**Decision A — shared frontmatter loader (owner chose A2).** Extract the ClassGraph scan + frontmatter parse + per-resource skip-malformed logic out of `PromptLoader` into a feature-neutral loader (e.g. `services/FrontmatterMarkdownLoader` returning a generic `MarkdownDocument(name, description, body)`). `PromptLoader` and `HelpTopicService` both become thin adapters over it. This leaves the codebase with a single frontmatter parser and no cross-feature package dependency, and folds in the one nearby structural-debt item at the moment a second consumer justifies the abstraction. The MCP prompt tests must stay green through the refactor.

**Decision B — unknown-topic error path (owner chose clean output + non-zero exit).** `PicoCommand.call()` always returns 0, so the only route to a non-zero exit is throwing from `execute()`; the generic executor renders a thrown exception on stderr prefixed with its Java class name (`IllegalArgumentException: ...`), which violates the display-command output convention. Instead, the command SHALL print a clean, user-facing error (naming the bad topic and listing the valid topics) itself, then force a non-zero exit without leaking an exception-class prefix to the terminal. This satisfies the acceptance criterion's "listing valid topics" half explicitly and keeps the output clean.

**Decision C — resource package (owner chose `com.rustyrazorblade.easydblab.help`).** Topic `.md` files live in `src/main/resources/com/rustyrazorblade/easydblab/help/`, scanned via `acceptPackages("com.rustyrazorblade.easydblab.help")` — inside the app's own namespace, rather than mirroring the prompts' `com.rustyrazorblade.mcp` layout.

**Decision D — `-h` topic-pointer footers, generated from the scan.** The root `@Command` on `CommandLineParser` gets a `footer` directing the user to run `help` for task guides; each subcommand that maps to a topic (by convention, a subcommand whose name matches a topic name — `up`/`init` → `provisioning`, `cassandra` → `cassandra`, and so on) gets a `footer` naming its related `help <topic>`. The footer strings are built from `HelpTopicService`'s discovered topic set, not a hardcoded list, so a new topic file updates the pointers with no code change and the two sources cannot drift. PicoCLI evaluates `footer` at usage-render time, so pulling it from the injected service is straightforward. The root footer stays generic ("run `help` to list topics") rather than enumerating names inline, so it needs no regeneration as topics grow.

**Umbrella `cassandra` topic.** The `cassandra` topic guides database management on a running cluster — lifecycle (`start`/`stop`/`restart`), version `use`/`install`, and the Cassandra config patch-file workflow (`write-config`/`update-config`/`download-config`) — and points to `stress-testing` (load) rather than duplicating it. It absorbs both the standalone "versions" topic that was considered and dropped, and the config workflow that was briefly a separate `configs` topic: the config commands live under the `cassandra` command namespace and are Cassandra-specific, so their guidance belongs in the database-management tutorial where a user looks for it, not in a general-sounding `configs` topic.

**Skip-one, not skip-all (from the design-critic review).** The extracted loader's malformed-file handling SHALL skip a single bad resource and continue — the per-resource `try/catch` must sit in the discovery loop, and a resource that throws something other than the expected parse error must still degrade to skip-one, never zero out the whole list (the existing `PromptLoader.loadAllPrompts` outer catch returns `emptyList()`, which the extraction must not preserve as the failure mode for a single bad file).

## Alternatives Considered

- **Decision A — where the frontmatter parsing lives.**
  - **A1 — reuse `PromptLoader` as-is:** `HelpTopicService` calls `PromptLoader().loadAllPrompts(...)` and maps `PromptResource → HelpTopic`. Rejected: smallest diff, but `services/` would depend on the `mcp/` package for an unrelated feature, and help would borrow the `PromptResource` domain name at the boundary.
  - **A2 — extract a shared loader (chosen, and the architect's recommendation):** one feature-neutral parser, no cross-feature coupling; the abstraction is justified now that there are two concrete consumers.
  - **A3 — duplicate the ~40 lines:** fully decoupled packages, but a knowing DRY violation — a future frontmatter tweak would have to be made twice. The architect would not choose this.
- **Decision B — how the unknown-topic error returns non-zero.**
  - **Throw with the full message, accept the `IllegalArgumentException:` prefix:** smallest code, lets the standard `ExecutionError` path render everything, but the class-name prefix reaches the terminal and runs against the display-command output convention. Rejected in favor of clean output + non-zero exit.
- **Decision C — resource package location.**
  - **Mirror the prompts under `com.rustyrazorblade.mcp`:** consistent with the sibling `PromptLoader` layout, but sits oddly outside the `easydblab` namespace. Rejected in favor of `com.rustyrazorblade.easydblab.help`.

## Risks / Trade-offs

- **A2 refactor risk:** extracting the loader touches working `mcp/` code; the MCP prompt tests are the guardrail and must stay green. Contained and testable.
- **No PicoCLI `-h`/`--help` collision (verified):** `mixinStandardHelpOptions` adds only `-h`/`--help` flags; the repo registers no PicoCLI `HelpCommand`, so a user-defined `help` subcommand coexists cleanly and the root no-arg usage is untouched. The `-h` footers added here are `@Command` `footer` text on the usage message, orthogonal to the `help` subcommand itself — they extend the auto-generated usage, they do not replace `-h`.
- **Footer/topic drift:** hardcoding topic names in a footer would create a second list that could fall out of sync with the packaged files. Mitigated by generating the footer from the `HelpTopicService` scan (Decision D) and keeping the root footer generic rather than enumerating names.
- **No collision with a kit named "help" (verified):** dynamic kit-subcommand registration skips any name already registered as a static subcommand, so the static `help` always wins.
- **Scan cost** is paid only when `help` runs, and once (lazy cache) — negligible, and imposed on no other command.
- **Duplicate `name` frontmatter across two files** is undefined (first match wins, both show in the listing); low likelihood for a curated seed set, left unaddressed as out of scope.
- **The "task-oriented, not a flag reference" criterion** for the nine seed topics is a content-authoring concern no structural element can enforce; it needs a human content review during implementation.
