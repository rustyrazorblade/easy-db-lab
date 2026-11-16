## 1. Interface and Data Model

- [x] 1.1 Add `listAllVpcCidrs(): List<String>` to `VpcDiscoveryOperations` in `providers/aws/VpcService.kt`
- [x] 1.2 Change `InitConfig.cidr` from `String` to `String?` (default `null`) in `configuration/ClusterState.kt`
- [x] 1.3 Add `Event.Setup.AutoSelectedCidr(cidr: String)` to `events/Event.kt`
- [x] 1.4 Handle `Event.Setup.AutoSelectedCidr` in `ConsoleEventListener`

## 2. Service Implementation

- [x] 2.1 Implement `listAllVpcCidrs()` in `EC2VpcService` — call `describeVpcs()` with no filter, return all VPC CIDR blocks
- [x] 2.2 Add CIDR auto-selection logic to `Up` — when `initConfig.cidr` is null, call `listAllVpcCidrs()`, pick first unused second octet in `10.X.0.0/16` via `CidrBlock.selectAvailable()`, emit `AutoSelectedCidr` event, persist resolved CIDR to state, fail fast if all 0–254 are taken

## 3. Command Layer

- [x] 3.1 Change `Init.cidr` from `String = Constants.Vpc.DEFAULT_CIDR` to `String? = null` in `commands/Init.kt`
- [x] 3.2 Update `Init.validateParameters()` to skip CIDR validation when `cidr` is null
- [x] 3.3 Update `--cidr` option description to reflect auto-selection default

## 4. Tests

- [x] 4.1 Add `listAllVpcCidrs()` integration test to `EC2VpcServiceIntegrationTest` — create VPCs, assert CIDRs returned
- [x] 4.2 Add unit tests for the auto-selection algorithm (find first unused second octet, exhausted range error)
- [x] 4.3 Add integration test verifying `listAllVpcCidrs()` + `CidrBlock.selectAvailable()` picks non-conflicting CIDR
- [x] 4.4 Add test verifying explicit `--cidr` is stored in state; null stored when omitted

## 5. Verification

- [x] 5.1 Run `./gradlew ktlintFormat && ./gradlew test && ./gradlew detekt` — all pass
