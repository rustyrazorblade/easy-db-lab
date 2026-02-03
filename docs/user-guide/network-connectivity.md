# Network Connectivity

This guide covers how to connect to your easy-db-lab cluster from your local machine.

## Overview

easy-db-lab clusters run in a private AWS VPC. By default, the VPC uses `10.0.0.0/16`, but you can customize this:

```bash
easy-db-lab init --cidr 10.14.0.0/20 ...
```

There are two methods to access your cluster:

| Method | Best For |
|--------|----------|
| **Tailscale VPN** (Recommended) | Production use, team sharing, persistent access |
| **SOCKS Proxy** | Quick testing when you don't want to set up Tailscale |

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
   - **Scopes**: Select **Devices: Write**
   - **Tags**: Add `tag:easy-db-lab`
4. Click **Generate** and save the **Client ID** and **Client Secret**

#### Step 3: Configure easy-db-lab

```bash
easy-db-lab setup-profile
```

Enter your Tailscale OAuth credentials when prompted.

### Usage

Tailscale starts automatically with `easy-db-lab up`. Once connected:

```bash
# Direct access to private IPs
ssh ubuntu@10.0.1.50
curl http://10.0.1.50:9428/health
kubectl get pods

# Web UIs work directly in your browser
# http://10.0.1.50:3000 (Grafana)
```

### Manual Control

```bash
easy-db-lab tailscale start
easy-db-lab tailscale status
easy-db-lab tailscale stop
```

### Troubleshooting Tailscale

**"requested tags are invalid or not permitted"** - Add the tag to your ACL (Step 1).

**Can't reach private IPs** - Check subnet route is approved in [Tailscale admin](https://login.tailscale.com/admin/machines), or add `autoApprovers` to your ACL.

**Using a custom tag:**
```bash
easy-db-lab tailscale start --tag tag:my-custom-tag
```

## SOCKS Proxy (Alternative)

If you don't want to set up Tailscale, the SOCKS proxy provides connectivity via an SSH tunnel through the control node.

```
┌─────────────────┐     SSH Tunnel      ┌──────────────┐
│  Your Machine   │ ──────────────────► │ Control Node │
│  localhost:1080 │                     │  (control0)  │
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
curl http://control0:9428/health
```

The proxy starts automatically when you load the environment.

### Proxied Commands

These commands are automatically configured to use the proxy after `source env.sh`:

| Command | Description |
|---------|-------------|
| `kubectl` | Kubernetes CLI |
| `k9s` | Kubernetes TUI |
| `curl` | HTTP client |
| `skopeo` | Container image tool |

### Manual Proxy Usage

For other commands, use the `with-proxy` wrapper:

```bash
with-proxy wget http://10.0.1.50:8080/api
with-proxy http http://control0:3000/api/health
```

### Browser Access

Configure your browser's SOCKS5 proxy:

| Setting | Value |
|---------|-------|
| SOCKS Host | `localhost` |
| SOCKS Port | `1080` |
| SOCKS Version | 5 |

Then access cluster services:
- **Grafana**: `http://control0:3000`
- **Victoria Metrics**: `http://control0:8428`
- **Victoria Logs**: `http://control0:9428`

### Proxy Management

```bash
start-socks5          # Start proxy
start-socks5 1081     # Start on different port
socks5-status         # Check status
stop-socks5           # Stop proxy
```

### Troubleshooting SOCKS Proxy

**"Connection refused" errors:**
```bash
socks5-status              # Check if running
start-socks5               # Start if needed
ssh control0 hostname      # Verify SSH works
```

**Proxy not working after network change:**
```bash
stop-socks5
source env.sh
```

**Port already in use:**
```bash
lsof -i :1080         # Check what's using it
start-socks5 1081     # Use different port
```

**Commands timing out:**
1. Check cluster status: `easy-db-lab status`
2. Verify SSH works: `ssh control0 hostname`
3. Restart proxy: `stop-socks5 && start-socks5`

## Comparison

| Feature | Tailscale | SOCKS Proxy |
|---------|-----------|-------------|
| Setup time | ~10 min (one-time) | Instant |
| Persistence | Persistent | Per-session |
| Requires `source env.sh` | No | Yes |
| Browser access | Direct | Requires proxy config |
| Team sharing | Yes | No |
| External dependency | Tailscale account | None |
