## MODIFIED Requirements

### Requirement: clickhouse-benchmark kit descriptor
The CLI SHALL ship a built-in kit `clickhouse-benchmark` under `kits/clickhouse-benchmark/`, with `type: app`.  Its `kit.yaml` SHALL declare:

- Install args: `--target` (variable `TARGET`, `type: kit-ref`, `endpoints: [native, http]`, no `capability`, required) and `--version` (variable `VERSION`, default `latest`).
- `prepare` args: `--workload` (default `clickbench`), `--source` (no default; the description names the pinned ClickBench URL), `--storage-policy` (default `default`) and `--recreate` (`boolean`, default `false`).
- `start` args: `--workload` (no default; the description says `(default clickbench)`), `--queries` (no default), `--concurrency` (`int`, default `4`), `--duration` (`int`, default `60`), `--iterations` (`int`, default `0`), `--randomize` (`boolean`, default `true`), `--continue-on-errors` (`boolean`, default `false`) and `--settings` (no default).
- A runtime probe of `type: pods` with selector `easydblab/kit=${KIT_NAME},easydblab/role=benchmark-run`, with no namespace, so it uses the cluster namespace.
- `collision-check: true`.

Shared kit code SHALL live in `lib/` and `pod/`, never in `bin/`, so only `prepare`, `start` and `stop` become phase commands.  The kit SHALL work from a Homebrew install with no source checkout.

#### Scenario: kit.yaml parsed
- **WHEN** the built-in `clickhouse-benchmark/kit.yaml` is parsed
- **THEN** it gives the install args, the `prepare` and `start` args and defaults listed above, the runtime probe on role `benchmark-run`, and `endpoints: [native, http]` on `--target`

#### Scenario: Only lifecycle scripts become commands
- **WHEN** the kit is installed
- **THEN** its subcommands are `prepare`, `start` and `stop`, and no subcommand comes from `lib/` or `pod/`


#### Scenario: Runtime probe in the cluster namespace
- **WHEN** the cluster namespace is `lab-a` and a `benchmark-run` pod of the kit runs in `lab-a`
- **THEN** the runtime probe finds it
