## Context

SSH is how easy-db-lab operates, not just how a user logs in. Two SSH clients reach cluster nodes:

- **OpenSSH subprocesses** driven by the workspace `sshConfig`: the SOCKS tunnel
  (`ssh -N -D … -F sshConfig control0`) and every `env.sh` helper (`ssh`, `scp`, `rsync`, `c0`,
  `c-all`, flame graphs), all of which pass `-F "$SSH_CONFIG"`.
- **The in-process Apache MINA SSHD client** (`DefaultSSHConnectionProvider`), behind
  `RemoteOperationsService`: `up`'s readiness wait, instance setup, K3s, helm/kubectl, Tailscale
  bootstrap, uploads and downloads. It dials `host.public:22`.

Both need a TCP path to port 22. On networks that block or mis-route port-22 egress to
unregistered destinations, neither has one.

## Goals / Non-Goals

**Goals:**
- One per-profile switch that moves *both* SSH clients onto SSM Session Manager.
- Zero behaviour change when the switch is `direct` (the default).
- No new commands or aliases — every existing workflow works unchanged under `ssm`.

**Non-Goals:**
- Packer AMI builds over SSM: the separate `ssm-packer-builds` change covers them.
- VPC endpoints for SSM — the tool's VPCs always have an internet gateway.
- Removing the port-22 security group rule — harmless under `ssm`, still needed for `direct`.

## Decisions

### The transport is a profile setting, read at runtime — not snapshotted into `state.json`

`User.sshTransport` (`direct` | `ssm`). Tailscale is snapshotted into state because it changes how
the cluster is *provisioned* (the control node joins a tailnet). SSM does not: the role policy is
always attached and the agent always runs, so a cluster provisioned under either setting is
identical, and the choice is purely about how this machine reaches it. Reading it from the profile
keeps one source of truth. Only `up` writes `sshConfig`, so the transport should be chosen before
`up`; the in-process client follows the profile immediately.

Serialized with Jackson (the existing `User` mechanism) as lowercase `direct` / `ssm`. The decoder
(`@JsonCreator SshTransport.fromProfile`) accepts what the setup prompt's `parse` accepts, so a
hand-edited `SSM` loads, and throws `IllegalArgumentException` naming the bad value and the valid
choices for anything else, rather than falling back silently. A profile without the field reads
as `direct`. `profile setup` on a profile with an unreadable value reports it, rebuilds the
profile from the raw file, and asks for the transport again.

### One builder for every `aws ssm start-session` command line

`SsmSessionCommand` builds the argv and environment for a target instance and an SSM document,
given the profile's region and credentials:

- `awsProfile` set → `--profile <awsProfile>`; the AWS CLI handles SSO refresh itself.
- static keys → `AWS_SHARED_CREDENTIALS_FILE=<profileDir>/awscredentials` + `--profile default`,
  with `AWS_CONFIG_FILE=/dev/null`, so a `[default]` section in the operator's `~/.aws/config`
  (SSO, `role_arn`, `credential_process`) cannot replace the static keys. That file is the one
  `AWSCredentialsManager` already writes for Packer, so no secret is written anywhere new and none
  appears in `sshConfig`.

Both SSH paths below render from this builder, so they cannot drift.

### One `SshRoute` per transport, decided once

`SshRoute` has one implementation per transport, bound by transport in `SSHModule`, which is the
only place the transport is decided. It answers both questions the two SSH clients ask:
`endpoint(host)` for the in-process client and `proxyCommand(host)` for `sshConfig`.
`DirectSshRoute` returns the public IP and no `ProxyCommand`; `SsmSshRoute` is described below.
`ClusterConfigurationService` asks the route rather than switching on the transport itself.

### OpenSSH path: per-host `ProxyCommand` in `sshConfig`

With `ssm`, each `Host` block gains, after its `Hostname` line:

```
ProxyCommand aws ssm start-session --target <instance-id> --document-name AWS-StartSSHSession --parameters portNumber=%p --region <region> --profile <name>
```

In the generated file the command is prefixed with the packaged `edl-ssm-proxy` wrapper (see
below), and the global section carries `ConnectTimeout 30`.

`Hostname` stays immediately after `Host` because `env.sh` reads it with `grep -A 1`. Values are
shell-quoted. The fallback config `env.sh` writes when `sshConfig` is missing carries the same
`ProxyCommand`s. A host with no instance ID fails `sshConfig` generation, before the file is
opened, rather than emitting a broken `ProxyCommand`.

**A stuck session on the OpenSSH path.** QA saw `ssh db0 'sleep 2'` hang 7m46s: Session Manager
had ended the session after 19s, but the plugin kept its WebSocket open and ssh received nothing.
`ServerAliveInterval` applies only after authentication. `ConnectTimeout` bounds the wait for the
server's banner even with a ProxyCommand; on OpenSSH 9.6 (Ubuntu 24.04) and 10.3 (macOS) a
ProxyCommand that sends nothing fails after the timeout ("Connection timed out during banner
exchange"). Contrary to the ssh_config man page, neither version bounds key exchange once a banner
has arrived through a ProxyCommand; no ssh_config option does before authentication, so that case
stays unbounded. The value matches the 30s SSM auth timeout on the in-process path.

**Orphaned session processes.** When ssh ends, it sends SIGHUP to the ProxyCommand. The AWS CLI dies
on it but does not end the `session-manager-plugin` it started; a healthy plugin then exits on
stdin EOF, but a stuck one does not, and survives under PID 1 (QA saw both alive 60s after ssh was
killed). The ProxyCommand therefore runs through `edl-ssm-proxy`, a POSIX sh wrapper packaged as a
resource and written into the profile directory (`SsmProxyWrapper`). It relays ssh's stdin to the
CLI through a FIFO, so it sees EOF, and ends the CLI's whole process tree (TERM, then KILL after a
2s grace) when stdin reaches EOF, when ssh exits or is killed (it is re-parented), or on
SIGHUP/SIGTERM/SIGINT.

### In-process path: per-instance SSM port forward

MINA SSHD's client supports ProxyJump but not ProxyCommand, so under `ssm` it dials
`SsmSshRoute.endpoint(host)`, a loopback port:

- `SsmSshRoute` runs one `AWS-StartPortForwardingSession` process per instance on an
  OS-assigned local port, waits for the plugin's `Waiting for connections` line, and caches it.
  A cached forward whose process has died is replaced on the next request, so MINA's existing
  stale-session reconnect recovers from an SSM idle timeout.
- A forward that exits or times out before becoming ready throws `SsmForwardNotReadyException`
  carrying the plugin's output. It is unchecked, so the SSH operations retry
  (`DefaultRemoteOperationsService`) retries it as a `RuntimeException`.
  `RetryUtil.createSshConnectionRetryConfig` lists it, so `up`'s SSH-readiness retry covers the
  window where a freshly booted instance's agent has not yet registered, and each retry event
  (`Provision.SshRetrying`) carries the failure's message.
- The same readiness retry had a gap under `direct` too (owner decision, 2026-10-06): MINA reports
  a refused or timed-out connection as `SshException`, a checked `IOException`, and resilience4j's
  plain `decorateRunnable`/`decorateSupplier` catch only `RuntimeException`, so the first refused
  connection aborted `up`. `up`'s readiness wait and every `DefaultRemoteOperationsService`
  operation now use the checked decorators. Both retry `SshException` (and, through the SSH
  connection policy or as a `RuntimeException`, `SsmForwardNotReadyException`), and both fail at
  once on any other `IOException`, which waiting will not fix.
- Forward processes, including the `session-manager-plugin` child the AWS CLI spawns, are torn
  down by `close()` and by the route's JVM shutdown hook (nothing calls the SSH provider's `stop()`
  today; if something does, it closes the route too). A process is reachable by both from the
  moment it is spawned, any failure while waiting for ready (an interrupt included) stops it and
  its descendants, and processes are stopped together, against one shared grace deadline.
- Killing a forward's processes does not end its session in Session Manager, which stays
  "Connected" until the 20-minute idle timeout (QA found 46 open at once). Each forward records the
  session ID the CLI prints (`Starting session with SessionId:`), and every stop (close, the
  shutdown hook, replacing a dead forward, the ready-timeout kill, an interrupted start) calls
  TerminateSession for it through `SsmSessionTerminator` (the AWS SDK `SsmClient`, the profile's
  SDK identity). The calls run in parallel on daemon threads, bounded by
  `Constants.Ssm.TERMINATE_SESSION_TIMEOUT_SECONDS` (20s, final: HTTPS to SSO and SSM took 5-8s at
  times in QA, and with the earlier 5s budget 4 of 43 sessions stayed open until the idle
  timeout). The SSM client's overall API call timeout is the same 20s, and each attempt is bounded
  at `TERMINATE_SESSION_ATTEMPT_TIMEOUT_SECONDS` (6s) so the SDK's three standard attempts fit
  inside it; a failure is logged at warn with the session ID
  and never replaces the error that caused the stop.
- A forward can report ready, accept the TCP connection, and then carry no SSH data, while its
  process stays alive (seen on a QA cluster: six 120s auth timeouts in a row through one forward,
  741s for `exec run`). The SSH layer therefore reports every failed connection:
  `DefaultSSHConnectionProvider` calls `SshRoute.invalidate(host)` when connect, key exchange or
  auth fails, and `DefaultRemoteOperationsService` calls `SSHConnectionProvider.discard(host)` on
  any `SshException` before its retry, which drops the cached session as well.
  `SsmSshRoute.invalidate` stops that forward (ending its session) so the next attempt starts a new
  one; `DirectSshRoute.invalidate` does nothing.
- The SSM client that ends sessions is built lazily. Building it at Koin resolution cost about
  0.5s on every `ssm` command, and the first AWS call at exit (the SSO credential fetch, then
  TerminateSession) about 1.5s more. The first forward's start now starts a daemon warm-up (client
  build and credential resolution) that overlaps the forward getting ready; TerminateSession waits
  for it within its deadline. A failed preparation (the SSO fetch timing out on a slow network) is
  never kept: the next warm-up or TerminateSession starts a new one, client and credentials both,
  and concurrent TerminateSession calls share it.
- Key exchange and auth through a forward are bounded at 30s (`SshRoute.authTimeout`,
  `Constants.Ssm.SSH_AUTH_TIMEOUT_SECONDS`) instead of MINA's 120s: a healthy one takes about
  1-1.5s, and a stuck forward should be retired and replaced quickly. `direct` keeps MINA's default.
- `SSHConnectionCache` connects under a per-host lock instead of inside
  `ConcurrentHashMap.compute`, which made hosts that share a bin connect one after another (only
  two of three forwards started at once on a `-p` run).
- A forward that dies after it was ready is logged at warn with the instance, local port, exit
  code and the plugin's last output; the next request replaces it, logged at info.
- `Host` gains `instanceId`, populated by `ClusterHost.toHost()`. `SsmSshRoute` is the single
  place a blank instance ID is rejected, for both `endpoint` and `proxyCommand`.

**Alternatives considered:**
- *One SSM forward to control0, ProxyJump to the rest.* Fewer processes, but makes every db-node
  connection depend on control0 and diverges from the per-host `ProxyCommand` model.
- *Route MINA through the SOCKS tunnel + `SocksTcpBridge`.* The tunnel is not up during `up`'s
  readiness wait or instance setup, and does not exist at all when Tailscale is active.
- *Implement the Session Manager WebSocket protocol in the JVM.* Far larger than spawning the
  official plugin.

### `up` preflight for local tooling

With `ssm`, `up` runs `aws --version` and `session-manager-plugin --version` before creating any
AWS resource, mirroring the local Tailscale check, through the same `LocalCliRunner` the
Tailscale check uses. A missing, failing, or hung tool emits `Event.Ssh.SsmToolsMissing` and
stops. Each fault says which case it is: a tool not on PATH carries its install hint, one that
exits non-zero carries its exit code and output (stdout and stderr), and one that hangs carries
the timeout.

### IAM

- **Instance role:** the role's inline policy set (`S3Access` and `SessionManagerInstance`) is
  defined once, in `InstanceRolePolicies` (`providers/aws/`), a collaborator rather than more
  functions on `AWS`. `SessionManagerInstance` is AWS's documented minimal Session Manager
  instance policy (`ssm:UpdateInstanceInformation`, `ssmmessages:CreateControlChannel`,
  `CreateDataChannel`, `OpenControlChannel`, `OpenDataChannel`), without `ec2messages:*`, which SSM
  Agent 3.3.40.0 and later does not use, and without KMS, since sessions are not KMS-encrypted. The
  managed `AmazonSSMManagedInstanceCore` is not used: it also grants Parameter Store reads on every
  parameter. The set is applied when `AWSResourceSetupService` creates the role, on every `up`
  (`AccountBucketSetup` → `reapplyInstanceRolePolicies`), and whenever `ensureAWSResources` finds
  a valid role missing one of the policies, which is the path `build-base` and `build-cassandra`
  take before a builder instance launches under the role. `PutRolePolicy` is idempotent.
- **Operator policy:** `iam-policy-ec2.json` allows `ssm:StartSession` on account instances only
  when they carry `easy_cass_lab=1` (`ssm:resourceTag/easy_cass_lab`; cluster nodes and Packer
  builders both carry it), and, without a condition, on the `AWS-StartSSHSession` /
  `AWS-StartPortForwardingSession` documents. `ssm:TerminateSession` and `ssm:ResumeSession` are
  allowed on `session/*` only with `StringEquals ssm:resourceTag/aws:ssmmessages:session-id =
  ${aws:userid}`. Session IDs start with the IAM user name or the role session name, never with
  `aws:userid`, so the `session/${aws:userid}-*` resource in AWS's quickstart matches no real
  session; the tag Session Manager puts on every session holds the user ID for an IAM user and
  `role-id:session-name` for an assumed or SSO role, both exactly `aws:userid` ("Allow a user to
  end only sessions they started", Method 2, in the Session Manager user guide). The Service
  Authorization Reference lists that tag key for both actions. `ssmmessages:OpenDataChannel` is
  granted on `*`: ssmmessages supports no resource ARN, so a session ARN there would match nothing.

## Risks / Trade-offs

- **The proxy may break SSM too** (TLS inspection of the `ssmmessages` WebSocket) → validate
  manually on the affected network before relying on it.
- **Agent registration lag** after boot → covered by `up`'s SSH-readiness retry.
- **Slower tunnel start-up** → over SSM the SOCKS tunnel takes about 6s to become reachable (AWS
  CLI start-up, StartSession, the plugin's WebSocket, then SSH key exchange and auth). The SSH
  route supplies the tunnel verification budget (`SshRoute.tunnelVerifyAttempts`): about 30s under
  `ssm` (`Constants.Proxy.SSM_TUNNEL_VERIFY_ATTEMPTS`), and the original 5s under `direct`
  (`DIRECT_TUNNEL_VERIFY_ATTEMPTS`). The loop still returns on first success and bails when ssh
  dies.
- **Leaked forward processes** if the JVM is killed with SIGKILL → shutdown hook covers normal
  exit and Ctrl-C; SIGKILL is accepted.
- **Plugin output contract** — readiness keys on `Waiting for connections`; a change surfaces as a
  readiness timeout with the plugin's output, not a silent failure.
- **Per-host processes** — one `aws` + plugin pair per node per CLI invocation; acceptable for
  lab-sized clusters.
