## Why

When a kit requires node pools that don't exist in the current cluster, the failure happens deep
inside the deployment — Helm times out, K8s pods stay Pending, and the user waits several minutes
before seeing an unhelpful scheduler error. Presto, for example, requires app nodes; if none exist,
`easy-db-lab install presto` should fail immediately with a clear message, not silently scaffold
files that can never be started.

## What Changes

- `kit.yaml` gains an optional `type` field — either `"db"` or `"app"` — declaring the primary
  node pool the kit targets
- `KitInstallCommand` checks this field at the start of `execute()`, before any template rendering
  or install steps run
- If the required node pool is empty, the command fails with a clear error and exits — nothing
  is written to disk
- Built-in kits are updated: `clickhouse` declares `type: db`, `presto` declares `type: app`

## Capabilities

### Modified Capabilities

- `install-command`: The kit descriptor (`kit.yaml`) gains a `type` field. When present, the
  `install` command validates that the cluster has at least one node of the declared type before
  proceeding. The field is optional — kits without it have no node-type requirement.

## Impact

- `services/KitConfig.kt`: add `KitType` enum (`DB`, `APP`) and `val type: KitType? = null` to
  `KitConfig`
- `commands/install/KitInstallCommand.kt`: add requirement check at the top of `execute()`,
  before `renderAndWrite()` — fail with `Event.Kit.RequirementNotMet` if the required pool is
  empty
- `events/Event.kt`: add `Event.Kit.RequirementNotMet(kit, type, message)` event
- `kits/presto/kit.yaml`: add `type: app`
- `kits/clickhouse/kit.yaml`: add `type: db`
- `openspec/specs/install-command/spec.md`: document `type` field and install-time requirement
  check
- Tests: `KitInstallCommandTest` — verify install fails fast when required node pool is absent;
  verify install proceeds when pool exists; verify no-type kits are unaffected
