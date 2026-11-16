## Why

On a cluster created without `init --open`, the security group allows SSH only from the operator's public IP as it was when `up` ran. When that IP changes (an overnight lease change, a new network, a VPN), SSH, the SOCKS tunnel and every command that uses them stop working. There is also no supported way to let another person's IP in. Today the only fix is a manual change in the AWS console or CLI.

## What Changes

**New commands**
- `easy-db-lab ingress allow [--cidr V]...` and `easy-db-lab ingress revoke [--cidr V]...`, under a new parent `ingress` command (`commands/ingress/`), registered in `CommandLineParser` and `Repl`. `--cidr` is repeatable (shared `commands/mixins/SshCidrMixin.kt`); a bare IP means `/32`; with no value, the detected public IP is used.
- Both use `@RequireProfileSetup` only. They make EC2 API calls and never start the SOCKS tunnel.
- `allow` adds a tcp/22 rule per CIDR to the workspace cluster's security group (`InfrastructureState.securityGroupId`), and reports an existing rule as already present. `revoke` removes the one exact tcp/22 rule per CIDR, and fails, naming the CIDR, when no rule matches; matches that exist are still removed.
- All values are validated before any AWS call; one bad value changes nothing. Duplicates collapse after normalizing. An AWS error on one CIDR does not stop the others; the command exits non-zero at the end.

**New service**
- `services/aws/SshIngressService` (interface + `DefaultSshIngressService`, registered in `AWSModule`): reads cluster state, resolves the targets (given or detected), runs the per-CIDR loop, returns a sealed `IngressResult` with typed `AllowOutcome` / `RevokeOutcome` values. It owns the SSH/tcp/port-22 binding and emits no events.

**New events**
- A new `Event.Ingress` domain: `RuleAdded`, `RuleAlreadyPresent`, `RuleRemoved`, `RuleNotFound`, `RuleChangeFailed`, `InvalidCidrs`, `PublicIpDetectionFailed`, `NotInWorkspace`, `NoSecurityGroupInState`, `SecurityGroupNotFound`.

**Strict CIDR parsing**
- New `network/Ipv4Cidr` value class with `parse(raw): Result<Ipv4Cidr>`: four decimal octets 0-255, no sign, leading zero or whitespace, prefix 0-32, host bits rejected with the network form named, IPv4 only, bare address as `/32`.
- `CidrBlock` (the VPC block for `init --cidr`) uses it, requires an explicit prefix, and keeps its `/20` rule. `init --cidr 10.0.0.0` now fails with "Invalid CIDR" instead of a `NumberFormatException`; `init --cidr 10.0.0.5/16` now fails and names `10.0.0.0/16`.

**VpcService and EC2 code**
- `SecurityGroupOperations.authorizeSecurityGroupIngress` returns `IngressAuthorization` (`ADDED` / `ALREADY_PRESENT`). New `revokeSecurityGroupIngress` returns `IngressRevocation` (`REMOVED` / `NOT_FOUND`): it describes the group, then revokes one exact permission. Both throw a new `SecurityGroupNotFoundException` for `InvalidGroup.NotFound`.
- The security-group code moves out of `EC2VpcService` into a new composed `services/aws/EC2SecurityGroupService` that implements `SecurityGroupOperations`; `EC2VpcService` delegates to it. The dead `securityGroups.isEmpty()` branches go, and the describe calls use `withAwsRetry`.
- The provider no longer emits `Infra.SecurityGroupRuleConfigured`. `AwsInfrastructureService` emits it on `ADDED` at every call site (cluster, Packer and compactor paths), with the same description, so `up` output is unchanged and `ingress allow` prints one line per CIDR.

**Public IP detection**
- `DefaultExternalIpService` uses a bounded connect and read timeout (new `Constants`). A timeout is a detection failure.

**Hint**
- `DirectSshRoute.tunnelConnectFailureHint` names `easy-db-lab ingress allow` and no longer says SSH is allowed only from the IP recorded when `up` ran.

**Docs and CLAUDE.md**
- `docs/reference/commands.md`: `ingress allow` / `ingress revoke`. `docs/user-guide/network-connectivity.md`: the changed-public-IP text names `ingress allow`, and `ingress revoke` for the old IP.
- `commands/CLAUDE.md`, `events/CLAUDE.md`, `services/aws/CLAUDE.md`: the new package, domain, and services.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `networking`: adds SSH ingress management on a running cluster, strict CIDR parsing, bounded public IP detection, single reporting of rule changes, and the updated changed-IP hint.

## Impact

- Code: `commands/ingress/`, `commands/mixins/SshCidrMixin.kt`, `services/aws/SshIngressService.kt`, `services/aws/EC2SecurityGroupService.kt`, `services/aws/EC2VpcService.kt`, `services/aws/AwsInfrastructureService.kt`, `providers/aws/VpcService.kt`, `providers/aws/AWSModule.kt`, `network/Ipv4Cidr.kt`, `network/CidrBlock.kt`, `services/ExternalIpService.kt`, `providers/ssh/SshRoute.kt`, `events/Event.kt`, `Constants.kt`, `CommandLineParser.kt`, `commands/Repl.kt`.
- Out of scope: a `list` action, automatic IP-change handling in other commands, other ports, IPv6, automatic removal of old IPs, rule description tags, and MCP exposure.
- Related: #936 (explicit CIDRs at `init`) reuses `Ipv4Cidr.parse`.
