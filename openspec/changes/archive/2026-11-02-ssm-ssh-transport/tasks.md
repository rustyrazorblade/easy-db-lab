## 1. IAM

- [x] 1.1 Add `AWSPolicy.Inline.SessionManagerInstance`: AWS's minimal Session Manager instance policy, in place of the managed `AmazonSSMManagedInstanceCore`
- [x] 1.2 Define the instance role's inline policy set once (`InstanceRolePolicies`: `S3Access` + `SessionManagerInstance`), a collaborator rather than more functions on `AWS`, and apply it when `AWSResourceSetupService` creates the role
- [x] 1.3 Re-assert it in `up`'s re-apply step (`AccountBucketSetup.reapplyPolicies` → `AWSResourceSetupService.reapplyInstanceRolePolicies`), before instances launch
- [x] 1.4 `iam-policy-ec2.json`: `ssm:StartSession` on instances tagged `easy_cass_lab=1` and, unconditioned, on the SSH/port-forward documents; `ssm:TerminateSession` and `ssm:ResumeSession` on `session/*` only where the session's `aws:ssmmessages:session-id` tag equals `${aws:userid}`; `ssmmessages:OpenDataChannel` on `*` (ssmmessages takes no resource ARN)
- [x] 1.5 `ensureAWSResources` puts any missing policy of the set on an otherwise valid role, so AMI builds (`build-base`, `build-cassandra`) launch builders under the full set

## 2. Profile setting

- [x] 2.1 Add `SshTransport` (`direct` | `ssm`) and `User.sshTransport` defaulting to `direct`; the Jackson decoder accepts what the prompt accepts and throws, naming the value and the choices, for anything else
- [x] 2.4 `profile setup` on a profile with an unreadable saved transport reports it, rebuilds the profile from the raw file, and asks for the transport
- [x] 2.2 Prompt for the transport in `profile setup` (initial and update modes), re-prompting on an unrecognized value
- [x] 2.3 Show the transport in `profile show`

## 3. SSM session command

- [x] 3.1 `SsmSessionCommand`: argv + environment for `AWS-StartSSHSession` and `AWS-StartPortForwardingSession`, for named-profile and static-key credentials; static keys also set `AWS_CONFIG_FILE=/dev/null` so `~/.aws/config` cannot override them
- [x] 3.2 Koin wiring (`ssmModule`) that derives credentials from `User` (static keys → `AWSCredentialsManager` file), resolved lazily

## 4. OpenSSH path

- [x] 4.1 `ClusterConfigWriter.writeSshConfig` (and the `env.sh` fallback config) emits a per-host `ProxyCommand` after `Hostname`
- [x] 4.2 `ClusterConfigurationService` asks the `SshRoute` for each host's `ProxyCommand`, before the file is opened
- [x] 4.4 Under `ssm`, `sshConfig` sets `ConnectTimeout 30`, and every `ProxyCommand` runs through the packaged `edl-ssm-proxy` wrapper, which ends the CLI's process tree (aws and session-manager-plugin) when ssh goes away
- [x] 4.3 Under `ssm`, `sshConfig` sends keepalives (`ServerAliveInterval 30`, `ServerAliveCountMax 3`) so Session Manager's 20-minute idle timeout cannot silently drop the SOCKS tunnel (found when the tunnel died after an hour idle)

## 5. SSH route and in-process MINA path

- [x] 5.1 Add `Host.instanceId`, populated by `ClusterHost.toHost()` and by Packer diagnostics
- [x] 5.2 `SshRoute` (`endpoint` + `proxyCommand`) with `DirectSshRoute`; `DefaultSSHConnectionProvider` dials `route.endpoint` and closes the route on `stop()`
- [x] 5.3 `SsmSshRoute`: per-instance forward, readiness detection, dead-forward replacement, unchecked `SsmForwardNotReadyException` on failure, single instance-ID check, teardown on `close()` and JVM shutdown reaching processes still starting, any failure while waiting stops the process tree, a forward that dies after ready is logged
- [x] 5.4 Koin wiring in `sshModule`: the one place the transport is decided
- [x] 5.5 Widen the SOCKS tunnel verification window from about 5s to about 30s under `ssm` only (`SshRoute.tunnelVerifyAttempts`): over SSM the tunnel needs about 6s to come up (found on the first real `up`); `direct` keeps about 5s
- [x] 5.6 `RetryUtil.createSshConnectionRetryConfig` retries `SsmForwardNotReadyException`; `Provision.SshRetrying` carries the last failure's message
- [x] 5.12 TerminateSession budget 20s total (6s per SDK attempt); a failed SSM client preparation is prepared again, credentials included, shared by concurrent calls
- [x] 5.10 Lazy SSM client with a warm-up on the first forward; SSM-only 30s SSH auth timeout from the route; per-host locking in `SSHConnectionCache`
- [x] 5.11 HTTP wire and header loggers off in `easydblab-logback.xml` (they wrote the SSO bearer token, credentials and the account ID into `debug.log`)
- [x] 5.9 A failed SSH connection retires its path: `SshRoute.invalidate` (stops and ends the SSM forward; no-op for direct), called by `DefaultSSHConnectionProvider` on a failed connect/KEX/auth and through `SSHConnectionProvider.discard` by `DefaultRemoteOperationsService` on any `SshException` before retrying
- [x] 5.8 `SsmSshRoute` ends each stopped forward's Session Manager session (TerminateSession via the SDK `SsmClient`, `SsmSessionTerminator`), on every stop path, bounded so it cannot hold JVM exit
- [x] 5.7 `up`'s readiness wait and `DefaultRemoteOperationsService` use resilience4j's checked decorators, so MINA's checked `SshException` (a refused connection under `direct`) is retried; any other `IOException` fails at once

## 6. `up` preflight

- [x] 6.1 `LocalSsmTooling` check for `aws` and `session-manager-plugin`, on the shared `LocalCliRunner` (extracted from the Tailscale check)
- [x] 6.2 `Event.Ssh.SsmToolsMissing` error event carrying one fault per tool: not on PATH (with the install hint), exited non-zero (with exit code and output), or timed out
- [x] 6.3 Run the check from `ProvisioningPreflight.verify` (which `up` calls before any AWS resource is created), only when `ssm`

## 7. Tests

- [x] 7.1 `ClusterConfigurationService`: `ProxyCommand` per host under an ssm route (also in the `env.sh` fallback), none under direct, `Hostname` directly after `Host`, missing instance ID fails without writing
- [x] 7.2 `SsmSessionCommand`: argv/env per credential mode and document; shell rendering; lazy credential resolution; `forUser` with a real credentials manager
- [x] 7.3 `SsmSshRoute` against a stub `aws` script: readiness, early exit surfaces output, timeout, reuse, dead-forward replacement, failed start not cached, close terminates the processes and their children, the timeout kill stops children too, concurrent requests share one process, blank instance ID refused
- [x] 7.4 `LocalSsmTooling`: how each runner outcome maps to a fault; `LocalCliRunner` against real processes, stderr kept apart
- [x] 7.5 `SetupProfile`: transport prompt saved (initial + update); invalid value re-prompted; blank keeps current
- [x] 7.6 `ProfileShow`: transport displayed
- [x] 7.7 IAM: role creation and a valid role missing it both get the Session Manager inline policy; EC2 user policy limits sessions to tagged instances and own sessions
- [x] 7.8 `up`: preflight failure stops before AWS is touched; a failing tool shows its output and no install hint; tools present proceeds; direct never checks; the Session Manager policy is put on the role before provisioning; SSH readiness keeps waiting through `SsmForwardNotReadyException`
- [x] 7.9 `UserConfigProvider`: profile without the field reads `direct`; `ssm` round-trips lowercase; hand-edited `SSM` loads; an unknown value fails naming it and the choices
- [x] 7.11 `up` keeps waiting through `SshException` under `direct` and fails at once on another `IOException`; `DefaultRemoteOperationsService` retries `SshException` and fails at once on another `IOException`
- [x] 7.10 Koin: `sshModule` + `ssmModule` bind `SsmSshRoute` under `ssm` and `DirectSshRoute` under `direct`

## 8. Docs

- [x] 8.1 `docs/user-guide/network-connectivity.md`: SSH over SSM Session Manager section (SSM sits underneath both access methods, so no comparison-table column)
- [x] 8.2 `docs/getting-started/setup.md` and `docs/reference/commands.md`: transport prompt, `profile show`, IAM additions; fix the instance role name
- [x] 8.3 `providers/CLAUDE.md`: SSH transport architecture notes and directory listing
