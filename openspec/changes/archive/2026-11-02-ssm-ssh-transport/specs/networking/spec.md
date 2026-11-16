## ADDED Requirements

### Requirement: SSH transport over SSM Session Manager

The system MUST support carrying every SSH connection to cluster nodes over AWS Systems Manager
Session Manager instead of a direct TCP connection to the node's public IP, selected by the user
profile's SSH transport setting (`direct` or `ssm`, default `direct`).

When the transport is `ssm`, both the generated SSH configuration (used by the SOCKS proxy and by
the shell helpers in `env.sh`) and the CLI's in-process SSH connections SHALL reach each node
through an SSM session targeting that node's instance ID. No inbound security group rule from the
operator SHALL be required for these connections to succeed.

When the transport is `direct`, SSH behaviour SHALL be unchanged.

The SSM sessions SHALL authenticate with the same AWS identity the profile is configured with: the
named AWS profile when one is set, otherwise the profile's static credentials. Static credentials
SHALL NOT be written into the generated SSH configuration, and when they are used the operator's
own AWS CLI config file SHALL NOT apply, so it cannot replace them with another identity.

#### Scenario: Generated SSH configuration routes through SSM

- **GIVEN** a profile whose SSH transport is `ssm`
- **WHEN** the cluster's SSH configuration is generated
- **THEN** every host entry routes its connection through an SSM SSH session targeting that host's instance ID in the profile's region
- **AND** each host entry's `Hostname` line still immediately follows its `Host` line

#### Scenario: Direct transport leaves the SSH configuration unchanged

- **GIVEN** a profile whose SSH transport is `direct` or unset
- **WHEN** the cluster's SSH configuration is generated
- **THEN** no host entry routes through SSM

#### Scenario: Idle SSM connections are kept alive

- **GIVEN** a profile whose SSH transport is `ssm`
- **WHEN** a long-lived SSH connection such as the SOCKS proxy carries no traffic for longer than Session Manager's idle timeout
- **THEN** the connection stays usable, because the generated SSH configuration sends keepalives

#### Scenario: A session that passes no data does not hang ssh

- **GIVEN** a profile whose SSH transport is `ssm`
- **WHEN** an OpenSSH connection's Session Manager session connects but passes no data
- **THEN** ssh fails within the configured connect timeout instead of waiting indefinitely

#### Scenario: Session processes do not outlive ssh

- **GIVEN** a profile whose SSH transport is `ssm`
- **WHEN** an ssh process using the generated SSH configuration exits or is killed, even mid-handshake
- **THEN** the AWS CLI and session-manager-plugin processes its ProxyCommand started exit too

#### Scenario: Shell helpers work unchanged over SSM

- **GIVEN** a provisioned cluster and a profile whose SSH transport is `ssm`
- **WHEN** the user runs `ssh db0`, a `c0` alias, or starts the SOCKS proxy after `source env.sh`
- **THEN** the connection is established over SSM with no change to the command

#### Scenario: In-process SSH connections use an SSM port forward

- **GIVEN** a profile whose SSH transport is `ssm`
- **WHEN** the CLI opens an SSH connection to a cluster node
- **THEN** it connects through a local SSM port-forwarding session to that node's port 22, not to the node's public IP

#### Scenario: A refused direct connection is retried while the node boots

- **GIVEN** a profile whose SSH transport is `direct` and a freshly launched node whose sshd is not accepting connections yet
- **WHEN** `up` waits for SSH readiness
- **THEN** the refused connection is retried until sshd accepts it, and each retry notice carries the failure's message

#### Scenario: Other I/O failures are not retried

- **GIVEN** an SSH operation, `up`'s readiness wait included
- **WHEN** it fails with an I/O error that is not an SSH connection failure, such as a missing remote file
- **THEN** it fails at once, without waiting out the retry window

#### Scenario: Port forward that cannot start is retried while the node boots

- **GIVEN** a freshly launched node whose SSM agent has not yet registered
- **WHEN** `up` waits for SSH readiness over SSM
- **THEN** the failed session is treated like any other not-yet-ready SSH connection and retried
- **AND** each retry notice carries the failure's message
- **AND** a session that never becomes ready surfaces the Session Manager plugin's own output in the error

#### Scenario: A stuck port forward is replaced, not retried

- **GIVEN** a profile whose SSH transport is `ssm` and a port forward whose process is alive but carries no SSH data
- **WHEN** an SSH connection through it fails, and the operation is retried
- **THEN** the stuck forward is stopped and its session ended, and the retry connects through a new forward
- **AND** no later step reuses the failed connection

#### Scenario: Missing instance ID fails fast

- **GIVEN** a profile whose SSH transport is `ssm`
- **WHEN** an SSH connection is requested for a host with no recorded instance ID
- **THEN** the operation fails with an error naming the host, rather than attempting a direct connection

#### Scenario: Local SSM tooling is verified before provisioning

- **GIVEN** a profile whose SSH transport is `ssm`
- **WHEN** the user runs `up` on a machine missing the AWS CLI or the Session Manager plugin
- **THEN** `up` stops before creating any AWS resource
- **AND** the error names the missing tool and how to install it

#### Scenario: A local SSM tool that is installed but fails

- **GIVEN** a profile whose SSH transport is `ssm`
- **WHEN** the user runs `up` and the AWS CLI or the Session Manager plugin exits non-zero or hangs on `--version`
- **THEN** `up` stops before creating any AWS resource
- **AND** the error shows the tool's exit code and output, or that it timed out, and no install hint

#### Scenario: Port forwards do not outlive the CLI

- **GIVEN** SSM port-forwarding sessions started by a CLI invocation
- **WHEN** the invocation exits normally or is interrupted
- **THEN** the forwarding processes it started, and their child processes, are terminated, including any still waiting to become ready
- **AND** each of their Session Manager sessions is ended on the AWS side, rather than left open until the idle timeout

#### Scenario: SOCKS tunnel start-up allowance follows the transport

- **GIVEN** a SOCKS tunnel being started
- **WHEN** the profile's SSH transport is `ssm`
- **THEN** the tunnel is given about 30 seconds to become reachable
- **AND** under `direct` it is given about 5 seconds, as before
