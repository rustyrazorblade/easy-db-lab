## Context

The CLI (PicoCLI-based) has no in-tool operational guide. The repo already discovers packaged markdown with `name`/`description` YAML frontmatter in `mcp/PromptLoader.kt` (a ClassGraph scan with per-resource skip-and-continue on malformed files), and already ships classpath-scanned resources that work from a Homebrew install with no source tree (MCP prompts, kit commands, install templates). This change adds a `help` command that reuses that proven mechanism, and takes the arrival of a second frontmatter-parsing consumer as the moment to extract the shared logic.

## Goals / Non-Goals

**Goals:**
- One top-level `help` command: no-arg topic listing, `help <topic>` body print, clean unknown-topic error with a non-zero exit.
- Topic discovery from packaged classpath resources, no hardcoded list; adding a topic is adding a file.
- Case-insensitive topic matching; skip-and-continue on a malformed file (skip-one, never skip-all).
- A single shared frontmatter-markdown loader, consumed by both `PromptLoader` and the new `HelpTopicService`.
- Works from a Homebrew install with no source checkout.

**Non-Goals:**
- The broad per-command extended-help catalog (issue 128).
- JSON or structured help output (issue 657).
- Regenerating or replacing the mdbook docs under `docs/`.
- Changing per-command PicoCLI `--help` text.
- Paging, search, syntax highlighting, or markdown-to-ANSI rendering — raw markdown is printed as-is.
- Variable substitution or cluster-state interpolation in topic content.

## Decisions

**Command/service split.** `commands/Help.kt` (`@Command(name="help")`, `PicoCommand` + `KoinComponent`) is a thin display command: it injects `HelpTopicService`, formats, and prints via `println()`. The topic is a single optional `@Parameters(arity="0..1")` positional — sanctioned for non-kit `show`/`info`-style commands. `services/HelpTopicService.kt` (interface + default impl) owns discovery, case-insensitive lookup, and a `by lazy` cached scan, mirroring `DefaultKitCommandScanner`. `HelpTopic(name, description, body)` is a distinct data class so help does not inherit MCP-prompt semantics.

**Decision A — shared frontmatter loader (owner chose A2).** Extract the ClassGraph scan + frontmatter parse + per-resource skip-malformed logic out of `PromptLoader` into a feature-neutral loader (e.g. `services/FrontmatterMarkdownLoader` returning a generic `MarkdownDocument(name, description, body)`). `PromptLoader` and `HelpTopicService` both become thin adapters over it. This leaves the codebase with a single frontmatter parser and no cross-feature package dependency, and folds in the one nearby structural-debt item at the moment a second consumer justifies the abstraction. The MCP prompt tests must stay green through the refactor.

**Decision B — unknown-topic error path (owner chose clean output + non-zero exit).** `PicoCommand.call()` always returns 0, so the only route to a non-zero exit is throwing from `execute()`; the generic executor renders a thrown exception on stderr prefixed with its Java class name (`IllegalArgumentException: ...`), which violates the display-command output convention. Instead, the command SHALL print a clean, user-facing error (naming the bad topic and listing the valid topics) itself, then force a non-zero exit without leaking an exception-class prefix to the terminal. This satisfies the acceptance criterion's "listing valid topics" half explicitly and keeps the output clean.

**Decision C — resource package (owner chose `com.rustyrazorblade.easydblab.help`).** Topic `.md` files live in `src/main/resources/com/rustyrazorblade/easydblab/help/`, scanned via `acceptPackages("com.rustyrazorblade.easydblab.help")` — inside the app's own namespace, rather than mirroring the prompts' `com.rustyrazorblade.mcp` layout.

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
- **No PicoCLI `-h`/`--help` collision (verified):** `mixinStandardHelpOptions` adds only `-h`/`--help` flags; the repo registers no PicoCLI `HelpCommand`, so a user-defined `help` subcommand coexists cleanly and the root no-arg usage is untouched.
- **No collision with a kit named "help" (verified):** dynamic kit-subcommand registration skips any name already registered as a static subcommand, so the static `help` always wins.
- **Scan cost** is paid only when `help` runs, and once (lazy cache) — negligible, and imposed on no other command.
- **Duplicate `name` frontmatter across two files** is undefined (first match wins, both show in the listing); low likelihood for a curated seed set, left unaddressed as out of scope.
- **The "task-oriented, not a flag reference" criterion** for the four seed topics is a content-authoring concern no structural element can enforce; it needs a human content review during implementation.
