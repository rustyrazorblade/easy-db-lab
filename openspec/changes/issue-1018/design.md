## Context

The cluster security group gets one SSH rule at `up` (`services/aws/AwsInfrastructureService.kt:268-269`, `0.0.0.0/0` when open, else the detected `/32`). Nothing changes ingress afterwards. `EC2VpcService.authorizeSecurityGroupIngress` (`services/aws/EC2VpcService.kt:322-389`) returns `Unit`, detects a duplicate but only logs it, and emits `Infra.SecurityGroupRuleConfigured` itself. There is no single-rule revoke; `revokeSecurityGroupRules` strips every rule at teardown. `CidrBlock.isValidCidr` (`network/CidrBlock.kt:123-143`) rejects a bare IP and accepts signs, leading zeros and host bits. `DefaultExternalIpService` reads ipify with no timeout. `EC2VpcService` is 1236 lines and implements four segregated interfaces.

## Goals / Non-Goals

**Goals:** add and remove SSH ingress for any IPv4 CIDR on a running cluster, from the workspace, with typed outcomes; one strict CIDR parser shared with `init` and #936; bounded IP detection; no duplicate output; a hint that names the fix; split the security-group code out of `EC2VpcService`.

**Non-Goals:** a `list` action, automatic IP-change handling, other ports, IPv6, automatic removal of old IPs, rule tags, MCP exposure.

## Decisions

The command reads the security group ID from cluster state, turns the `--cidr` values (or the detected IP) into validated canonical IPv4 CIDRs, and then adds or removes one exact `tcp/22/22` rule per CIDR. Typed per-CIDR outcomes come back; the command emits one event per outcome.

Layers: `IngressAllow` / `IngressRevoke` (`commands/ingress/`) → `SshIngressService` (`services/aws/`) → `ClusterStateManager`, `ExternalIpService`, `VpcService` (typed results). `Ipv4Cidr` (`network/`) is a pure parser.

```kotlin
// network/Ipv4Cidr.kt
@JvmInline value class Ipv4Cidr private constructor(val value: String) {
    companion object { fun parse(raw: String): Result<Ipv4Cidr> }  // failure message names the value and why
}

// providers/aws/VpcService.kt (SecurityGroupOperations)
enum class IngressAuthorization { ADDED, ALREADY_PRESENT }
enum class IngressRevocation { REMOVED, NOT_FOUND }
fun authorizeSecurityGroupIngress(securityGroupId: SecurityGroupId, fromPort: Int, toPort: Int,
    cidr: Cidr, protocol: String = "tcp"): IngressAuthorization
fun revokeSecurityGroupIngress(securityGroupId: SecurityGroupId, fromPort: Int, toPort: Int,
    cidr: Cidr, protocol: String = "tcp"): IngressRevocation
class SecurityGroupNotFoundException(val securityGroupId: SecurityGroupId) : RuntimeException(...)

// services/aws/SshIngressService.kt
interface SshIngressService {
    fun allow(rawCidrs: List<String>): IngressResult<AllowOutcome>
    fun revoke(rawCidrs: List<String>): IngressResult<RevokeOutcome>
}
sealed interface IngressResult<out T> {
    data object NotInWorkspace : IngressResult<Nothing>
    data object NoSecurityGroupInState : IngressResult<Nothing>
    data class InvalidCidrs(val invalid: List<InvalidCidr>) : IngressResult<Nothing>
    data class DetectionFailed(val reason: String) : IngressResult<Nothing>
    data class SecurityGroupGone(val securityGroupId: String) : IngressResult<Nothing>
    data class Applied<T>(val securityGroupId: String, val detected: Boolean, val outcomes: List<T>) : IngressResult<T>
}
sealed interface AllowOutcome { val cidr: Ipv4Cidr  // Added, AlreadyPresent, Failed(reason)
}
sealed interface RevokeOutcome { val cidr: Ipv4Cidr  // Removed, NotFound, Failed(reason)
}
```

- **Revoke** describes the group; no exact `tcp 22-22` permission with that `cidrIp` → `NOT_FOUND`; else `RevokeSecurityGroupIngress` with one permission; `InvalidPermission.NotFound` also → `NOT_FOUND`.
- **Exit code:** non-zero on any precondition failure, `NotFound` or `Failed`, via `kernel/CommandFailedException` after the events (pattern: `commands/socks/SocksStop.kt`).
- **`@RequiresProxy` is not used.** The commands only make EC2 API calls; a tunnel would also fail in exactly the changed-IP case.
- **Events:** new `Event.Ingress` domain.

  | Event | Fields | Error |
  |---|---|---|
  | `Ingress.RuleAdded` | `securityGroupId`, `cidr`, `detected` | no |
  | `Ingress.RuleAlreadyPresent` | `securityGroupId`, `cidr`, `detected` | no |
  | `Ingress.RuleRemoved` | `securityGroupId`, `cidr`, `detected` | no |
  | `Ingress.RuleNotFound` | `securityGroupId`, `cidr` | yes |
  | `Ingress.RuleChangeFailed` | `securityGroupId`, `cidr`, `action` (ALLOW/REVOKE), `reason` | yes |
  | `Ingress.InvalidCidrs` | `invalid: List<{value, reason}>` | yes |
  | `Ingress.PublicIpDetectionFailed` | `reason` (display names `--cidr`) | yes |
  | `Ingress.NotInWorkspace` | — | yes |
  | `Ingress.NoSecurityGroupInState` | — | yes |
  | `Ingress.SecurityGroupNotFound` | `securityGroupId` | yes |

- **`Ipv4Cidr` is the only validator.** `CidrBlock` uses it, requires an explicit prefix (so `init --cidr 10.0.0.0` gives "Invalid CIDR"), rejects host bits through it, and keeps the `/20` rule (owner decision).
- **Rule-change events move out of the provider.** `describeIngressPorts` moves with the security-group code into `EC2SecurityGroupService` as an `internal` function that `AwsInfrastructureService` uses. Every call site that adds a rule (`AwsInfrastructureService.kt:106`, `:174`, `:269`, `:272`, `:279`, `:288`) emits `Infra.SecurityGroupRuleConfigured` on `ADDED`. The event tests in `EC2VpcServiceTest.kt:143-190` move to `AwsInfrastructureService` tests.
- **`EC2SecurityGroupService`** (folded in by owner decision): a new class in `services/aws/` implementing `SecurityGroupOperations`, holding the security-group methods now in `EC2VpcService` (find/create, authorize, revoke, revoke-all, describe, delete) and their helpers. `EC2VpcService` delegates `SecurityGroupOperations` to it by Kotlin interface delegation so `VpcService` keeps one injectable type. The move preserves behavior except the changes listed here.
- **Ipify timeouts** (folded in): `Constants.Network.EXTERNAL_IP_CONNECT_TIMEOUT_MS = 5000` and `EXTERNAL_IP_READ_TIMEOUT_MS = 10000`, applied on the `URLConnection`.
- **Hint** (folded in): `DirectSshRoute.tunnelConnectFailureHint` says the security group allows SSH only from the IPs it lists, that a changed public IP is the likely cause, and that `easy-db-lab ingress allow` adds the current public IP.
- **Tests:** a pure `Ipv4CidrTest`; LocalStack `EC2VpcServiceIntegrationTest` cases for authorize (`ADDED` then `ALREADY_PRESENT`), exact revoke, `NOT_FOUND`, and `SecurityGroupNotFoundException`; `SshIngressServiceTest` (`BaseKoinTest`, real `ClusterStateManager` on `@TempDir`, fake `ExternalIpService`, mocked `VpcService`); a small command test for exit codes and event types; a `CidrBlockTest` case that asserts the bare-IP message; an `ExternalIpService` timeout test against a local socket that accepts and stalls (integration tier). Real-cluster check: allow with no value, allow a `/24`, revoke it, revoke own IP and see SSH fail, allow again and see SSH work.

## Risks / Trade-offs

- Small blast radius: one new command, two `VpcService` signatures; existing callers ignore the result.
- A tunnel can outlive a revoke, because security groups are stateful; an open connection may keep working until it reconnects.
- The EC2 client uses the profile's region, not `InfrastructureState.region`; a changed profile region shows as `SecurityGroupNotFound`.
- Many CIDRs can hit the rules-per-group quota; it shows as `RuleChangeFailed` with AWS's message.
- On the `ssm` transport the command still works; port 22 is unused.
- The `EC2SecurityGroupService` split moves about 250 lines and their tests; it raises review size. Behavior must be preserved apart from the listed changes.
- `init --cidr` is now stricter (bare address and host bits rejected). Clusters are ephemeral, so no existing workspace is affected.

## Alternatives Considered

- **D1 Where the use case lives.** Chosen: `SshIngressService` (keeps AWS logic out of commands, the port-22 binding in one place, testable on return values). Rejected: commands call `VpcService` directly, as `Status.kt:300` does; less code, but the resolve-and-loop logic would be duplicated in allow and revoke.
- **D2 VpcService result shape.** Chosen: one enum per operation plus `SecurityGroupNotFoundException`, because a missing group is a whole-command failure. Rejected: (a) one sealed result per operation including `GroupMissing`, which pushes a whole-command case into every per-CIDR result; (b) keep `Unit` and have the service describe first, which duplicates the matching rules in the EC2 code.
- **D3 Event domain.** Chosen: new `Event.Ingress`. Rejected: `Infra.SshIngress*`, which mixes operator actions into provisioning lifecycle.
- **D4 Host bits set.** Chosen: reject before any AWS call, naming the network form. Rejected: normalize silently (surprising); pass through unchanged (if EC2 canonicalizes, exact-string matching would wrongly report "not found" and "added").
- **D5 Where validation runs.** Chosen: in the service, all bad values in one `InvalidCidrs` event. Rejected: a picocli `ITypeConverter`, which stops at the first bad value, prints usage instead of an event, and is harder for #936 to reuse.
- **D6 AWS error part-way.** Chosen: carry on, report each `Failed`, exit non-zero at the end (matches the issue's rule that matches are still removed). Rejected: stop at the first error.
- **D7 How revoke works.** Chosen: describe, then revoke the one exact permission (deterministic). Rejected: blind revoke and read `UnknownIpPermissions` / `Return`, which saves one call but behaves differently in EC2 and LocalStack.
- **D9 Duplicate values.** Chosen: dedupe after normalizing. Rejected: report each occurrence ("added" then "already exists").
- **`init --cidr` rules.** Chosen by owner: the strict rules (explicit prefix, host bits rejected, `/20` kept). Rejected: keep host bits legal on `init` and fix only the bare-IP message.
- **`EC2VpcService` split.** The architect recommended a separate issue. **Owner override:** fold the split into this change.
- **SshRoute hint and ipify timeouts.** Raised by the design critic as out of the issue's literal scope; the owner chose to fold both in.
