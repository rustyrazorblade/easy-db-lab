## Why

The Presto kit deploys a functioning SQL query engine but provides no in-tool way to execute queries. Users must either install the Presto CLI separately or exec into a pod. `presto sql` gives the same "run a quick query" UX as `cassandra cql` — essential for validating catalogs, checking schemas, and running ad-hoc benchmarks.

## What Changes

- New `Presto` parent command group and `PrestoSql` subcommand registered in `CommandLineParser`
- New `PrestoService` that talks to the Presto HTTP API (`POST /v1/statement`) on the app node's private IP (port 8080), following `nextUri` for pagination
- New `Event.Presto` sealed class with typed events for query output and errors
- `CommandLineParser` gains a static `Presto` entry (like `Cassandra` and `OpenSearch`)
- New spec at `openspec/specs/presto/spec.md`

## Capabilities

### New Capabilities

- `presto-sql`: Execute SQL against a running Presto cluster via `presto sql <query>` or `presto sql --file <path>`

### Modified Capabilities

- `CommandLineParser`: registers static `Presto` command group alongside existing groups

## Impact

- `commands/presto/Presto.kt` — parent command group
- `commands/presto/PrestoSql.kt` — `sql` subcommand (positional arg or `--file`)
- `services/PrestoService.kt` + `DefaultPrestoService` — HTTP API client (paginated)
- `services/ServicesModule.kt` — Koin registration for `PrestoService`
- `events/Event.kt` — `Event.Presto` sealed class with `SqlQueryOutput`, `SqlQueryError`, `SqlUsage`, `NoAppNodes`
- `CommandLineParser.kt` — add `Presto::class` to top-level subcommands
