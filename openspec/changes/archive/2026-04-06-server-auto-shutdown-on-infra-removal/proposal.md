## Why

When `easy-db-lab server` is running and the underlying AWS infrastructure is torn down (e.g., the VPC is deleted), the server process continues running indefinitely with no meaningful work to do. This wastes resources and leaves the user with a stale, misleading server process — especially important in automated or unattended scenarios where no human is watching the terminal.

## What Changes

- Add an optional `--auto-shutdown` flag to the `server` command that enables infrastructure watchdog behavior.
- When enabled, a background service checks whether the cluster's VPC still exists in AWS on each status refresh cycle.
- If the VPC is no longer found, the server logs a final shutdown event and exits cleanly.

## Capabilities

### New Capabilities

- `server-infra-watchdog`: Background watchdog that monitors AWS infrastructure health (VPC existence) while the server is running and triggers a clean shutdown if the infrastructure is gone.

### Modified Capabilities

- `server`: The `server` command gains a new `--auto-shutdown` flag and a new background service lifecycle hook.

## Impact

- `commands/Server.kt` — new CLI options
- New background service class (e.g., `InfraWatchdogService`) in `services/` or similar
- AWS VPC existence check via existing EC2 provider
- `events/Event.kt` — new domain event for watchdog shutdown
- `openspec/specs/server/spec.md` — new requirements for auto-shutdown behavior
