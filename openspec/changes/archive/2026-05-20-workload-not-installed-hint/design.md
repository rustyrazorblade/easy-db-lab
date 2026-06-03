## Context

PicoCLI raises `UnmatchedArgumentException` (a subclass of `ParameterException`) when the user
types a top-level argument that doesn't match any registered subcommand. By default, PicoCLI
prints "Unmatched argument at index N: 'X'" plus fuzzy-matched suggestions from registered
subcommands. Installed workloads are registered as subcommands at parser construction time, but
uninstalled workloads are not — so `presto` would never appear in the suggestion list.

`CommandLineParser` already has a custom `executionExceptionHandler` (for runtime errors) but no
custom `parameterExceptionHandler`. PicoCLI's `CommandLine.setParameterExceptionHandler()` accepts
an `IParameterExceptionHandler` that runs before the default error printing, giving us a clean
interception point.

`InstallTemplateResolver.listAvailableTemplates()` returns the set of installable workload names
from built-in classpath templates and profile directories. This is the authoritative registry of
what can be installed.

## Goals / Non-Goals

**Goals:**
- Intercept `UnmatchedArgumentException` when the first unmatched token matches an available template name
- Print a clear, actionable message: `"<name> is not installed. Run: easy-db-lab install <name>"`
- Fall through to PicoCLI's default error behavior for genuinely unknown commands

**Non-Goals:**
- Fuzzy/partial matching of workload names (exact match only)
- Changing the error output for non-workload unmatched arguments
- Handling multi-token cases like `easy-db-lab presto start` (the first token `presto` is already sufficient)

## Decisions

**Use `IParameterExceptionHandler` over `IExecutionExceptionHandler`**
`ParameterException` fires at parse time before execution — exactly when unmatched arguments are
detected. `ExecutionException` fires after a command runs, which is too late. PicoCLI makes the
distinction clean via separate handler interfaces.

**Check first unmatched token only**
`UnmatchedArgumentException.getUnmatched()` returns a list; we only need the first token. If the
user types `easy-db-lab presto start`, the first token is `presto` — checking it is sufficient
to identify the uninstalled workload.

**Exact match against `listAvailableTemplates()`**
No fuzzy matching. The template names are the install subcommand names (e.g., `presto`,
`clickhouse`). The user must have typed the exact name for us to claim it's installable. Anything
else falls through to PicoCLI's own fuzzy "did you mean?" suggestions.

**Return exit code 2 (usage error) on the hint path**
PicoCLI's default for parameter errors is exit code 2. We match that to avoid surprising callers
or scripts.

## Risks / Trade-offs

[Risk] `listAvailableTemplates()` involves classpath scanning and file I/O — could be slow.
→ Mitigation: this path only runs on parse failure (unhappy path), so latency doesn't matter.

[Risk] A future workload template with the same name as a built-in subcommand (e.g., `install`,
`hosts`, `repl`) would never trigger the hint because PicoCLI would match the built-in first.
→ Acceptable: template names are curated; collision is a separate naming concern.
