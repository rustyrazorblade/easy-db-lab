## Why

When a user omits `--cidr` on `init`, it defaults to `10.0.0.0/16`. If multiple users in the same AWS account and region each create a cluster without specifying a CIDR, every VPC ends up with the same block — causing routing conflicts when Tailscale connects them into a single mesh.

## What Changes

- `InitConfig.cidr` becomes `String?` — `null` means "auto-select at up time"
- `Init --cidr` option becomes optional with no default; omitting it stores `null` in state
- CIDR validation in `Init.validateParameters()` is skipped when `--cidr` is not provided
- `VpcDiscoveryOperations` gains a new `listAllVpcCidrs()` method
- `EC2VpcService` implements `listAllVpcCidrs()` via `describeVpcs()` with no filter
- `AwsInfrastructureService` auto-selects a non-conflicting `/16` when `config.cidr` is null, by scanning the first unused second octet in the `10.X.0.0/16` range (0–254)
- A new `Event.Setup.AutoSelectedCidr(cidr)` event informs the user which CIDR was chosen
- Fails fast with a clear error if all second octets 0–254 are taken

## Capabilities

### New Capabilities

- `vpc-cidr-allocation`: Auto-selects a non-conflicting `10.X.0.0/16` CIDR by querying existing VPCs in the target region when the user does not provide `--cidr`.

### Modified Capabilities

- `networking`: CIDR is no longer a required concept at init time; it becomes nullable and resolved lazily. No requirement-level change to SSH/Tailscale/SOCKS5 behavior.

## Impact

- `providers/aws/VpcService.kt` — new interface method on `VpcDiscoveryOperations`
- `services/aws/EC2VpcService.kt` — implements `listAllVpcCidrs()`
- `services/aws/AwsInfrastructureService.kt` — CIDR resolution logic added before VPC creation
- `configuration/ClusterState.kt` (`InitConfig.cidr`) — type change `String` → `String?`
- `commands/Init.kt` — `--cidr` option becomes nullable, validation conditional
- `events/Event.kt` — new `Event.Setup.AutoSelectedCidr(cidr: String)` event
- `ConsoleEventListener` — handles new event
- Existing tests that set `cidr = "10.0.0.0/16"` will continue to pass unchanged
