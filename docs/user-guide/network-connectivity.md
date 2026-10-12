# Network Connectivity

This guide covers how to connect to your easy-db-lab cluster from your local machine.

## Overview

easy-db-lab clusters run in a private AWS VPC. By default, `up` picks a random `10.X.0.0/16` block that no existing VPC in the region uses, and prints the one it chose. If creating the VPC fails, it retries a few times, each time on a new random unused block. To choose the block yourself, pass `--cidr`; it is used as-is and not retried:

```bash
easy-db-lab init --cidr 10.14.0.0/20 ...
```

There are two methods to access your cluster:

| Method | Best For |
|--------|----------|
| **Tailscale VPN** (Recommended) | Production use, team sharing, persistent access |
| **SOCKS Proxy** | Quick testing when you don't want to set up Tailscale |

Both methods, and every command easy-db-lab runs on a node, travel over SSH. If your network blocks or
re-routes outbound SSH (port 22), switch the profile's SSH transport to
[SSM Session Manager](#ssh-over-ssm-session-manager). It works underneath either method.

## Tailscale VPN (Recommended)

Tailscale provides a persistent VPN connection to your cluster. Once connected, you can access cluster resources directly—no proxy configuration needed.

### Why Tailscale?

- **Native access** - Use any tool (browsers, kubectl, ssh) without proxy configuration
- **Persistent** - Connection survives terminal sessions
- **Team sharing** - Share cluster access with teammates
- **Reliable** - No SSH tunnels to maintain or reconnect

### Setup (One-Time)

#### Step 1: Configure Tailscale ACL

Go to [Tailscale ACL Editor](https://login.tailscale.com/admin/acls) and add:

```json
{
  "tagOwners": {
    "tag:easy-db-lab": ["autogroup:admin"]
  },
  "autoApprovers": {
    "routes": {
      "10.0.0.0/8": ["tag:easy-db-lab"]
    }
  }
}
```

The `autoApprovers` section automatically approves subnet routes, so you don't need to manually approve each cluster.

#### Step 2: Create OAuth Client

1. Go to [Tailscale OAuth Settings](https://login.tailscale.com/admin/settings/oauth)
2. Click **Generate OAuth Client**
3. Configure:
   - **Description**: easy-db-lab
   - **Scopes**: **Auth Keys** write (`auth_keys`), to create the control node's key, and **Devices › Core** write (`devices:core`), so `down` and `tailscale stop` can remove the control node's device
   - **Tags**: Add `tag:easy-db-lab`
4. Click **Generate** and save the **Client ID** and **Client Secret**

#### Step 3: Configure easy-db-lab

```bash
easy-db-lab profile setup
```

Enter your Tailscale OAuth credentials when prompted.

#### Step 4: Connect this machine

Your own machine must be on the tailnet too. A Tailscale cluster routes every connection over the tailnet and starts no SOCKS proxy, so a logged-out client has no route to the cluster at all.

```bash
tailscale up
tailscale status
```

`easy-db-lab up` checks this before it creates any AWS resource. If the local client is logged out, or the `tailscale` command is missing, `up` stops immediately and names the cause.

### Usage

Tailscale starts automatically with `easy-db-lab up`. Once connected:

```bash
# Direct access to private IPs
ssh ubuntu@10.0.1.50
curl http://10.0.1.50:3100/ready
kubectl get pods

# Web UIs work directly in your browser
# http://10.0.1.50:3000 (Grafana)
```

`easy-db-lab down` and `easy-db-lab tailscale stop` remove the cluster's control node from the tailnet, using the device ID recorded when Tailscale started on it (`tailscale stop` does this even when Tailscale is already down), so a later `tailscale start` does not leave the old device beside the new one. If the OAuth client is not allowed to delete devices, or no credentials are configured, the command exits non-zero and says so; the device ID stays in the cluster state, so running it again once the scope is granted removes it. `tailscale start` likewise removes a previously recorded device when the control node registers as a new one; if that removal fails it reports the old device (and the `devices:core` scope, when the OAuth client lacks it) so you can remove it by hand, records the new one, and still succeeds, so `up` carries on.

### Manual Control

```bash
easy-db-lab tailscale start
easy-db-lab tailscale status
easy-db-lab tailscale stop
```

Cluster commands cannot reach this cluster until 'easy-db-lab tailscale start'.

### Troubleshooting Tailscale

**"requested tags are invalid or not permitted"** - Add the tag to your ACL (Step 1).

**Can't reach private IPs** - Check subnet route is approved in [Tailscale admin](https://login.tailscale.com/admin/machines), or add `autoApprovers` to your ACL.

**"the local Tailscale client is not connected"** - `up` found your own machine off the tailnet. Run `tailscale up`, confirm `tailscale status` reports it connected, then run `easy-db-lab up` again.

**"the 'tailscale' command was not found"** - Install Tailscale from [tailscale.com/download](https://tailscale.com/download). If you use the macOS App Store build, its CLI is not on the PATH; see the [Tailscale CLI docs](https://tailscale.com/kb/1080/cli).

**"cannot reach it ... over the tailnet"** - The control node joined the tailnet, but this machine has no route to the cluster's private network. `up` waits about two minutes for the route to arrive before it reports this. Approve the subnet route for the control node in [Tailscale admin](https://login.tailscale.com/admin/machines).

**Using a custom tag:**
```bash
easy-db-lab tailscale start --tag tag:my-custom-tag
```

## SOCKS Proxy (Alternative)

If you don't want to set up Tailscale, the SOCKS proxy provides connectivity via an SSH tunnel through the control node.

```
┌─────────────────┐     SSH Tunnel      ┌──────────────┐
│  Your Machine   │ ──────────────────► │ Control Node │
│ 127.0.0.1:<port>│                     │  (control0)  │
└────────┬────────┘                     └──────┬───────┘
         │                                     │
    SOCKS5 Proxy                         Private VPC
         │                                     │
         ▼                                     ▼
   kubectl, curl                          VPC network
```

### Quick Start

```bash
source env.sh
kubectl get pods
curl http://control0:3100/ready
```

The `easy-db-lab` CLI is the only thing that starts or stops the tunnel. Any command that needs to
reach the cluster starts it, or reuses the one already running, before the command does its work.
The CLI reuses a running tunnel only after a connection through it reaches the control node. If the tunnel's SSH connection died while its local port stayed open, the CLI stops it, starts a new one, and records the new port.

When the CLI replaces a tunnel, it prints the old port, the new port and the reason: the tunnel went to another control node, it no longer reached the control node, its process had exited, or its PID now belongs to another process. If your browser uses the tunnel, change its SOCKS port to the new one. If the old tunnel does not stop, the CLI prints an error with its PID; end it with `kill -9 <pid>`. The CLI no longer records that tunnel, so it will not stop an `ssh` the tunnel leaves: the error also gives the `pkill` command that ends it.

The tunnel reconnects by itself. The CLI runs it through `<workspace>/bin/edl-socks-tunnel`, which easy-db-lab writes with the tool wrappers. That script runs `ssh -N -D 127.0.0.1:<port>` and, when `ssh` exits, starts it again on the same port after a two-second pause. The tunnel listens on 127.0.0.1 only, and every client of it uses 127.0.0.1, never `localhost`: if another process holds the port on one loopback address, a client of `localhost` could reach that process instead. The SSH keepalives make `ssh` exit about 90 seconds after its connection dies, for example after a laptop sleep or a network change. Because the port does not change, wrapped tools in an open shell work again as soon as the connection comes back, with no CLI command. `socks stop` and `down` stop the script and its `ssh`.

The script restarts `ssh` only for a tunnel the CLI has verified. The CLI records the port in `.socks5-proxy.env` only after a connection through the tunnel reaches the control node, and the script checks that record. A tunnel that never came up (its port was taken, its key was refused) ends at once, and the CLI reports the failure or retries on a new port. If another process takes the port while `ssh` waits to restart, the script ends after that one attempt. The wrapped tools then fail with the `socks start` message, which names `<workspace>/logs/socks5-proxy.log`, and the next CLI command starts a new tunnel. The log records each `ssh` start and why the script ended, with a UTC timestamp on every line.

The tunnel is shared by every command in the workspace, so Ctrl-C does not end it. Pressing Ctrl-C during an easy-db-lab command stops that command and leaves the tunnel running. Use `easy-db-lab socks stop` to stop it.

To start it for everything else (your shell, a browser), run:

```bash
easy-db-lab socks start
```

`socks start` prints the tunnel's local port. On a Tailscale cluster it starts nothing and says that
no tunnel is needed.

Each new tunnel listens on a random free local port, from 20000 to 29999; no port is preferred. The range is below the ports the OS gives outgoing connections (32768 and up on Linux, 49152 and up on macOS), so no outgoing connection can take the tunnel's port while `ssh` waits to restart. If 20 random ports in the range are all in use, the CLI fails with a message that names the range. Each workspace records its own port, so several clusters can run side by side, and two workspaces are unlikely to want the same port. If they do, the second one picks another port.

The CLI records the tunnel in the workspace's `.socks5-proxy.env`, as `EDL_SOCKS_PORT` (the port) and `EDL_SOCKS_PID` (the PID of the tunnel script), next to `EDL_TAILSCALE_ACTIVE`. The CLI is the only writer of this file; the wrappers and `env.sh` read it. Each write holds a lock on `.socks5-proxy.env.lock`, so two easy-db-lab commands in the same workspace never lose each other's values.

A wrapper checks only that some process still holds the recorded PID, because a shell script cannot read another process's arguments portably. The CLI checks more: it reuses a recorded tunnel only when the process with that PID is the workspace's tunnel script, with the recorded port and `sshConfig`. When another program now holds the PID, the next CLI command that needs the tunnel starts a new one.

### Tool Wrappers

easy-db-lab writes a small wrapper script for each of these tools into the workspace's `bin/`
directory, at `up` and before every kit command:

| Command | Description |
|---------|-------------|
| `kubectl` | Kubernetes CLI |
| `helm` | Kubernetes package manager |
| `cilium` | Cilium CLI |
| `k9s` | Kubernetes TUI |
| `curl` | HTTP client |
| `skopeo` | Container image tool |
| `ssh`, `scp`, `sftp` | SSH to the nodes, with the workspace `sshConfig` |
| `rsync` | File sync to the nodes, over SSH with the workspace `sshConfig` |

`source env.sh` puts `bin/` first on your `PATH`, and kit scripts get it first on theirs.

The `ssh`, `scp` and `sftp` wrappers add `-F <workspace>/sshConfig` before your arguments, and the `rsync` wrapper adds `-ave "ssh -F <workspace>/sshConfig"`, so `ssh db0` reaches the node by its alias. They reach the nodes over SSH itself, not the SOCKS tunnel, so they leave your proxy variables and `KUBECONFIG` unchanged. If the workspace has no `sshConfig` file, they name the missing file and exit 1.

Each call of the other wrapped tools reads the workspace's `.socks5-proxy.env`, which the CLI writes, and then:

- on a SOCKS cluster, routes that one call through the tunnel;
- on a Tailscale cluster, runs the tool unchanged, so it connects directly;
- with no tunnel recorded, or when the recorded tunnel script (`EDL_SOCKS_PID`) is no longer running, fails with exit code 1 and a message that says to run `easy-db-lab socks start`. It never uses the port of a tunnel that is gone, because another process may hold that port now.

The `kubectl`, `helm`, `cilium` and `k9s` wrappers always use the workspace `kubeconfig`. They replace any `KUBECONFIG` you exported, because a workspace's wrapper reaches only that workspace's cluster. A bare `<workspace>/bin/kubectl get ns` works with no setup: you do not need to source `env.sh` or set `KUBECONFIG`. If the workspace has no `kubeconfig` file, the wrapper names the missing file and exits 1. The `curl` and `skopeo` wrappers do not change `KUBECONFIG`.

The wrappers replace any proxy variables you set in your shell for that one call, so an exported
`NO_PROXY` cannot send cluster traffic around the tunnel. They do not change your shell, and tools
without a wrapper, `aws` included, connect directly. A wrapped tool sends all of its traffic through
the tunnel, public URLs included.

`command kubectl` in a sourced shell also runs the wrapper, because the wrapper is a real file on
your `PATH`, not a shell function. To run the real binary directly, call it by its full path, for
example `/usr/local/bin/kubectl`.

If you sourced an `env.sh` from an older version in an open shell, source the new one again: it
removes the old `kubectl`, `helm`, `cilium`, `curl`, `skopeo`, `k9s`, `ssh`, `scp`, `sftp` and `rsync` shell functions.

### Manual Proxy Usage

For other commands, use the `with-proxy` helper from `env.sh`. On a Tailscale cluster it runs the
command directly:

```bash
with-proxy wget http://10.0.1.50:8080/api
with-proxy http http://control0:3000/api/health
```

### Kit commands over SOCKS

Kit lifecycle commands work transparently on SOCKS-only clusters, with no extra flags or setup,
whether or not Tailscale is enabled.

**`kit install`, `<kit> start` / `stop` and other lifecycle phases.** Their shell steps, phase
scripts and hooks run `kubectl`, `helm` and similar tools on your machine to apply manifests, wait
on pods, and read pod state. Each of them runs with the workspace `bin/` first on `PATH` and the
absolute workspace kubeconfig in `KUBECONFIG`, so those tools go through the wrappers above. The CLI
verifies the tunnel, and restarts it on a new port if it died, before the first step runs. Indirect
calls (`timeout`, `xargs`, nested scripts) go through the wrappers too. See
[How local scripts reach the cluster](../development/kits.md#how-local-scripts-reach-the-cluster).

```bash
easy-db-lab postgres start
```

**`sql`.** The `sql` command opens a short-lived in-process loopback bridge that forwards the
JDBC connection through the existing tunnel to the database's private IP, then tears it down when
the query finishes. This works for raw-TCP drivers (PostgreSQL, MySQL) as well as HTTP-based ones
(Trino, ClickHouse):

```bash
easy-db-lab postgres sql "SELECT 1"
```

On Tailscale-enabled clusters the same commands connect directly to the private IP with no proxy,
so behavior is identical either way. In neither path are the JVM-global `socksProxyHost` /
`socksProxyPort` properties touched — routing is scoped per client.

### Browser Access

Run `easy-db-lab socks start` and note the port it prints. Then configure your browser's SOCKS5
proxy:

| Setting | Value |
|---------|-------|
| SOCKS Host | `127.0.0.1` |
| SOCKS Port | the port `socks start` printed (`socks5-status` shows it too) |
| SOCKS Version | 5 |

Then access cluster services:
- **Grafana**: `http://control0:3000`
- **Mimir**: `http://control0:9009/prometheus` (send `X-Scope-OrgID: <tenant>`)
- **Loki**: `http://control0:3100` (send `X-Scope-OrgID: <tenant>`)

### Proxy Management

```bash
easy-db-lab socks start   # Start the tunnel, or reuse the running one, and print its port
socks5-status             # Show the recorded port (after source env.sh)
easy-db-lab socks stop    # Stop the tunnel; the cluster keeps running
```

`easy-db-lab down` stops the tunnel too. After `socks stop`, wrapped tools fail with the
`socks start` message until a CLI command or `socks start` starts the tunnel again.

If the tunnel process does not end, `socks stop` prints its PID and exits non-zero.  The PID and port stay recorded.  Stop the process with `kill <pid>`, then run `easy-db-lab socks stop` again.  `down` goes on with the teardown in that case and prints the same PID.

If the tunnel script was killed from outside (for example with `kill -9`), its `ssh` can keep running on its own. `socks stop`, `down` and the next tunnel start find that `ssh` by the recorded port and `sshConfig`, and stop it.

### Host Key Verification

The `sshConfig` generated for your cluster sets `UserKnownHostsFile=/dev/null` alongside
`StrictHostKeyChecking=no`. `ssh` — and therefore the SOCKS tunnel, whose `edl-socks-tunnel`
script runs `ssh -N -D` against that config — never reads or writes your `~/.ssh/known_hosts` for
cluster nodes.

This matters because AWS recycles public IPs across ephemeral cluster lifetimes. Without this
setting, a recycled IP that previously belonged to a different cluster (with a different host
key) would make `ssh` hard-fail with `REMOTE HOST IDENTIFICATION HAS CHANGED` — `StrictHostKeyChecking=no`
only auto-adds *unknown* hosts, it doesn't override a *changed* key for a host already recorded.
Every cluster is short-lived and gets fresh host keys on every provision, so there is nothing to
verify against across runs.

If you connected to easy-db-lab clusters before this change, their host keys may still be in
your `~/.ssh/known_hosts`. They're no longer read by the tool, so you can prune them any time —
look for entries matching your cluster's `Hostname` lines in the generated `sshConfig`.

### Tunnel Failures

If the SOCKS tunnel can't be established, the command that needed it fails immediately with a
non-zero exit code rather than silently continuing against a proxy port nothing is listening on.
The error names the SOCKS proxy as the failing component and points at `socks5-proxy.log` in
your cluster workspace directory — that file holds the `ssh -v` transcript from the tunnel
attempt and is the fastest way to find the real cause (a host-key mismatch, a security group
blocking port 22, the control node not yet accepting SSH, and so on). The error quotes only the
lines of the failed attempt.

The log keeps the history of every tunnel in the workspace; nothing truncates it. Each launch adds
a header line with a UTC timestamp, the port and the control node, and the tunnel script adds a
timestamped line before each `ssh` run and each time `ssh` exits. Look for the last header to find
the current tunnel.

`easy-db-lab status` is the one exception: it still reports everything it can reach over SSH and
the AWS SDK even when the tunnel is down, marking only the section that requires the private
Kubernetes API (stress jobs) as unavailable. See the
[`status` command reference](../reference/commands.md#status) for details.

### Troubleshooting SOCKS Proxy

**"no SOCKS tunnel is recorded for the workspace" or "the recorded SOCKS tunnel (PID ...) is not running":**
```bash
easy-db-lab socks start    # Start the tunnel and record its port
```

**"Connection refused" errors:**
The tunnel's `ssh` cannot reach the control node right now. The tunnel restarts `ssh` by itself;
if that does not help, start a new tunnel:
```bash
socks5-status              # Shows the recorded port and whether anything listens on it
easy-db-lab socks start    # Starts a new tunnel and records its port
ssh control0 hostname      # Verify SSH works
```

**Proxy not working after network change:**
```bash
easy-db-lab socks stop
easy-db-lab socks start
```

**Commands timing out:**
1. Check cluster status: `easy-db-lab status`
2. Verify SSH works: `ssh control0 hostname`
3. Restart the tunnel: `easy-db-lab socks stop && easy-db-lab socks start`

**`easy-db-lab` command fails with a SOCKS proxy error:**
As of this change, `easy-db-lab` commands that need the tunnel (`up`, kit commands, Grafana
config updates, etc.) abort immediately if the tunnel can't be established, instead of silently
running against a dead proxy port. Check `socks5-proxy.log` in your cluster workspace directory
for the `ssh -v` transcript — it shows the actual reason the tunnel failed. See
[Host Key Verification](#host-key-verification) above for the most common cause on a
newly-provisioned cluster.

## SSH over SSM Session Manager

Everything easy-db-lab does on a node travels over SSH: provisioning, the SOCKS proxy, `ssh db0`,
and the `c0` aliases. By default SSH connects straight to each node's public IP on port 22. Some
networks don't allow that, typically a corporate egress proxy that only routes outbound traffic to
destinations registered with it in advance. A freshly provisioned instance can never be
pre-registered, so on those networks `up` times out waiting for SSH even though the instances are
healthy.

The `ssm` SSH transport tunnels every SSH connection through
[AWS Systems Manager Session Manager](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager.html).
The SSM agent on each node connects *out* to AWS over HTTPS, so your machine never needs to reach a
node on port 22. Nothing else about SSH changes: the keys, the generated `sshConfig`, and every
command stay the same.

### Requirements

- **On your machine:** the AWS CLI v2 and the Session Manager plugin.

  ```bash
  brew install awscli
  brew install --cask session-manager-plugin
  ```

  For other platforms, see the [AWS CLI](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html)
  and [Session Manager plugin](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-working-with-install-plugin.html)
  install guides. `up` checks for both before it creates any AWS resource.
- **IAM:** your user or role must be allowed to open sessions. The EC2 policy printed by
  `easy-db-lab show-iam-policies ec2` allows `ssm:StartSession` with the SSH and port-forwarding
  session documents, only to instances tagged `easy_cass_lab=1` (every cluster node and AMI build
  instance is), and allows `ssm:TerminateSession` and `ssm:ResumeSession` on your own sessions
  only (matched on the session's `aws:ssmmessages:session-id` tag, which works for IAM users and
  SSO or assumed roles alike), plus `ssmmessages:OpenDataChannel`.
- **On the cluster:** nothing. Every node's instance role carries a small inline Session Manager
  policy (`SessionManagerInstance`), which lets the SSM agent register and carry sessions and
  grants nothing else. `up`, and the IAM check that AMI builds run first, add it to roles created
  by older versions. The node image already runs the SSM agent.

The SSM sessions authenticate as your profile does: through its named AWS profile (including SSO)
when one is set, otherwise with its static keys. With static keys the AWS CLI is told to ignore
your `~/.aws/config`, so a `[default]` SSO or role setting there cannot replace them.

### Enabling it

Run `profile setup` and answer `ssm` at the SSH transport prompt:

```
SSH transport (direct, ssm)? [direct] ssm
```

`easy-db-lab profile show` reports the current transport.

```admonish note
Choose the transport before you run `up`. `up` writes the cluster's `sshConfig`, which the SOCKS
proxy and the `env.sh` helpers use, with the transport that was selected at the time.
```

### AMI builds

AMI builds (`profile setup`, `build-image`, `build-base`, `build-cassandra`) follow the same
setting. Under `ssm`, Packer reaches its temporary build instance through Session Manager instead of
its public IP.

Packer runs in a container, and the stock Packer image has no Session Manager plugin. The first
`ssm` AMI build therefore builds a derived image, `localhost/easy-db-lab/packer-ssm`, which adds the
plugin. Both the Packer base image and the plugin are pinned to fixed versions, and the plugin
download is checked against a known SHA-256. Later builds reuse the image. Building it needs your container engine to reach the Alpine package
mirror and AWS's plugin download at `s3.amazonaws.com`. AMI builds need nothing extra installed on
your machine itself.

### Troubleshooting SSM

**`ssh db0` fails with "Connection timed out during banner exchange":** the Session Manager
session opened but carried no data for 30 seconds (`ConnectTimeout` in the generated `sshConfig`).
Run the command again; a new session usually works.

Each `ProxyCommand` in `sshConfig` runs through `edl-ssm-proxy`, a small wrapper in your profile
directory. It ends the `aws` and `session-manager-plugin` processes when ssh exits or is killed, so
none are left behind.

**`up` stops with "these tools cannot run on this machine"**: each tool it lists says why.
"not found on PATH" comes with the install command; install it, then run `easy-db-lab up` again. A
tool that "exited" is installed but broken, and the line shows its own error output. A tool that
"did not finish" hung on `--version`.

**`TargetNotConnected` while `up` waits for SSH:** a freshly booted node's SSM agent takes a short
while to register with AWS. `up` keeps retrying, exactly as it does while sshd starts (a refused
connection under the `direct` transport), and each
"SSH still not up yet" line shows the error from the last attempt. If it never connects, check
that the node can reach the internet over HTTPS.

**`AccessDeniedException` mentioning `ssm:StartSession`:** your IAM identity is missing the SSM
permissions. Compare it against `easy-db-lab show-iam-policies ec2`.

**Checking SSM outside easy-db-lab:** take an instance ID from `easy-db-lab status` and open a
port-forwarding session to it. The policy allows only the SSH and port-forwarding documents, so a
plain shell session (no `--document-name`) is denied by design.

```bash
aws ssm start-session --target <instance-id> \
  --document-name AWS-StartPortForwardingSession \
  --parameters portNumber=22,localPortNumber=2222
```

It should print `Waiting for connections...`.

If that also fails from your network, the proxy is blocking Session Manager itself (its data
channel is a WebSocket to `ssmmessages.<region>.amazonaws.com` on port 443). Ask your network
administrators to allow that endpoint.

## Comparison

| Feature | Tailscale | SOCKS Proxy |
|---------|-----------|-------------|
| Setup time | ~10 min (one-time) | Instant |
| Persistence | Persistent | Per-session |
| Requires `source env.sh` | No | Yes |
| Browser access | Direct | Requires proxy config |
| Team sharing | Yes | No |
| External dependency | Tailscale account | None |
