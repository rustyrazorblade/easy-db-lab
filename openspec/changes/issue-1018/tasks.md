## 1. Strict CIDR parsing

- [x] 1.1 Write `Ipv4CidrTest` first: bare IP → `/32`, `/0`, `/32`, exact `/24`; rejects `300.1.1.1`, `10.0.0.0/33`, `foo`, `+1.2.3.4/32`, `01.2.3.4/32`, `1.2.3/24`, leading/trailing whitespace, `::1`; host bits (`198.51.100.7/24`) rejected naming `198.51.100.0/24`; every error names the value
- [x] 1.2 Add `network/Ipv4Cidr.kt` (value class, private constructor, `parse(raw): Result<Ipv4Cidr>`)
- [x] 1.3 Make `CidrBlock` use `Ipv4Cidr`, require an explicit prefix, keep the `/20` rule; add `CidrBlockTest` cases asserting the "Invalid CIDR" message for `10.0.0.0` and the network form for `10.0.0.5/16`; fix any `init` caller that relied on the loose parse

## 2. Security-group code split (behavior-preserving move)

- [x] 2.1 Move the `SecurityGroupOperations` implementation and its helpers (including `describeIngressPorts`) from `EC2VpcService` into a new `services/aws/EC2SecurityGroupService`; `EC2VpcService` delegates `SecurityGroupOperations` to it; move the matching unit and integration tests; all existing tests pass unchanged in behavior
- [x] 2.2 Remove the unreachable `securityGroups.isEmpty()` branches and wrap the describe calls in `withAwsRetry`

## 3. Typed rule results

- [x] 3.1 Add `IngressAuthorization`, `IngressRevocation` and `SecurityGroupNotFoundException` to `providers/aws/VpcService.kt`; `authorizeSecurityGroupIngress` returns `IngressAuthorization`; map `InvalidGroup.NotFound` to the exception
- [x] 3.2 Add `revokeSecurityGroupIngress` (describe, then revoke one exact permission; `InvalidPermission.NotFound` → `NOT_FOUND`)
- [x] 3.3 LocalStack tests in `EC2VpcServiceIntegrationTest` (or its split successor): `ADDED` then `ALREADY_PRESENT`; revoke removes only its CIDR's tcp/22 rule, leaving another tcp/22 rule, the VPC tcp/udp/ICMP rules and egress; missing rule → `NOT_FOUND`; both methods throw `SecurityGroupNotFoundException` for a missing group
- [x] 3.4 Stop emitting `Infra.SecurityGroupRuleConfigured` in the provider; emit it from `AwsInfrastructureService` on `ADDED` at every call site (cluster, Packer, compactor), same description; move the event tests from `EC2VpcServiceTest` to `AwsInfrastructureService` tests; update tests that mock `VpcService` to stub the non-null result

## 4. Public IP detection timeouts

- [x] 4.1 Add `Constants.Network.EXTERNAL_IP_CONNECT_TIMEOUT_MS` (5000) and `EXTERNAL_IP_READ_TIMEOUT_MS` (10000); apply them in `DefaultExternalIpService`; make the URL injectable for tests
- [x] 4.2 Integration-tier test: a local server that accepts and then stalls makes detection fail after the read timeout

## 5. SshIngressService

- [x] 5.1 Write `SshIngressServiceTest` first (`BaseKoinTest`, real `ClusterStateManager` on `@TempDir`, fake `ExternalIpService`, mocked `VpcService`): bad value among good → `InvalidCidrs` with every bad value and no `VpcService` call; no state / no security group → precondition result, no AWS call; detection throws or returns garbage → `DetectionFailed`; bare IP reaches AWS as `/32`; mixed revoke → `Removed` + `NotFound`; loop continues after `Failed`; duplicates collapse; `SecurityGroupNotFoundException` → `SecurityGroupGone`
- [x] 5.2 Add `services/aws/SshIngressService.kt` (interface, `DefaultSshIngressService`, `IngressResult`, `AllowOutcome`, `RevokeOutcome`); register it in `AWSModule`

## 6. Events and commands

- [x] 6.1 Add the `Event.Ingress` domain and its ten events to `events/Event.kt`, with serialization registration per `events/CLAUDE.md`
- [x] 6.2 Add `commands/mixins/SshCidrMixin.kt` (repeatable `--cidr`), `commands/ingress/Ingress.kt` (parent), `IngressAllow.kt`, `IngressRevoke.kt` (`@RequireProfileSetup`); map results to events; throw `CommandFailedException` naming failed CIDRs; register `Ingress` in `CommandLineParser` and `Repl`
- [x] 6.3 Small command test: `NotFound`, `Failed` and each precondition result exit non-zero; which event types are emitted

## 7. Hint

- [x] 7.1 Update `DirectSshRoute.tunnelConnectFailureHint` to name `easy-db-lab ingress allow` and drop "recorded when `up` ran"; update its tests

## 8. Docs, CLAUDE.md, verification

- [x] 8.1 `docs/reference/commands.md`: `ingress allow` / `ingress revoke`; `docs/user-guide/network-connectivity.md`: the changed-public-IP text names `ingress allow`, and `ingress revoke` for the old IP
- [ ] 8.2 Update `commands/CLAUDE.md` (`ingress/`, `SshIngressService`), `events/CLAUDE.md` (`Ingress` domain), `services/aws/CLAUDE.md` (`SshIngressService.kt`, `EC2SecurityGroupService.kt`)
- [ ] 8.3 `./gradlew ktlintFormat`, `detekt`, `test`, `integrationTest` pass with no new suppressions
- [ ] 8.4 Real cluster: `ingress allow` with no value; `allow --cidr` a `/24`; `revoke` it; `revoke` own IP and see SSH fail; `allow` again and see SSH work
