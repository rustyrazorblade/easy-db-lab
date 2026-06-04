## Context

Currently `Init.cidr` defaults to `Constants.Vpc.DEFAULT_CIDR` (`"10.0.0.0/16"`). This string is stored in `InitConfig.cidr` (in `state.json`) and passed to `AwsInfrastructureService.ensureInfrastructure()` → `vpcService.createVpc()`. Multiple users sharing an AWS account each create a VPC with the same block, producing routing conflicts in a Tailscale mesh.

The VPC is not created at `init` time — it is created during `up`. This means CIDR auto-selection naturally belongs in the `up` path, specifically in `AwsInfrastructureService` before calling `createVpc`. There is no need to make AWS calls during `init`.

## Goals / Non-Goals

**Goals:**
- Auto-select a non-conflicting `10.X.0.0/16` CIDR when the user omits `--cidr`
- Inform the user which CIDR was chosen at `up` time
- Allow `--cidr` to still override auto-selection

**Non-Goals:**
- Cross-region CIDR conflict detection (same region only)
- Supporting CIDR ranges other than `10.0.0.0/8` in auto-selection
- Prefix lengths other than `/16`

## Decisions

### Decision 1: `InitConfig.cidr` becomes `String?`

`null` in state.json means "auto-select at up time." This is cleanly distinguishable from an explicit user choice. The alternative — keeping a sentinel value like `"auto"` — would be stringly typed and harder to reason about.

`Init.validateParameters()` only validates the CIDR when it is non-null (i.e., when `--cidr` was explicitly provided). When null, there is nothing to validate at init time.

### Decision 2: Auto-selection lives in `AwsInfrastructureService`

The selection requires an AWS API call (`describeVpcs`), and `AwsInfrastructureService` already holds `vpcService`. Placing it here keeps the command layer thin and avoids adding any AWS dependency to `Init`.

The resolved CIDR is used in-place; it does not need to be written back to state because `AwsInfrastructureService` receives it through `VpcNetworkingConfig` which is constructed just before VPC creation.

### Decision 3: New `VpcDiscoveryOperations.listAllVpcCidrs()` method

Rather than calling `ec2Client` directly from `AwsInfrastructureService`, the CIDR listing capability is added to the existing `VpcDiscoveryOperations` interface. This keeps `AwsInfrastructureService` decoupled from the SDK and mockable in unit tests.

### Decision 4: Algorithm — first unused second octet in 0–254

```
usedOctets = existingCidrs
    .filter { it starts with "10." }
    .map { second octet as Int }
    .toSet()
    
candidate = (0..254).first { it !in usedOctets }
result = "10.$candidate.0.0/16"
```

If all 255 octets are taken, throw `IllegalStateException` with a clear message. 255 VPCs in a single region is well beyond any realistic scenario.

### Decision 5: Emit `Event.Setup.AutoSelectedCidr(cidr: String)`

The user needs to know which CIDR was chosen. A structured event (not a `println`) follows project conventions and makes the information available to MCP clients and Redis subscribers.

## Risks / Trade-offs

- **Race condition**: Two users could run `up` simultaneously, both see the same next-available octet, and both create `10.X.0.0/16`. This is unlikely in practice (small teams sharing an account), and AWS will reject the second `createVpc` call with a duplicate if it truly conflicts — which becomes a clear error rather than a silent routing problem.
- **Non-`10.x` VPCs ignored**: Existing `172.16.x.x` or `192.168.x.x` VPCs are not scanned. Auto-selection only produces `10.X.0.0/16` blocks; any conflicts with other private ranges require the user to pass `--cidr` explicitly.

## Migration Plan

No migration needed — clusters are ephemeral. Existing `state.json` files with `"cidr": "10.0.0.0/16"` will deserialize correctly because a non-null `String` is still valid for `String?`. No data loss or incompatibility.
