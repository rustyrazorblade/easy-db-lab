# Network Connectivity

This guide covers the different ways to connect to your easy-db-lab cluster from your local machine.

## Overview

easy-db-lab clusters run in a private AWS VPC. By default, cluster nodes have private IPs (10.0.x.x) that aren't directly accessible from the internet. There are two methods to access your cluster:

| Method | Best For | Setup Complexity |
|--------|----------|------------------|
| **SOCKS Proxy** | Quick access, development | Low (automatic) |
| **Tailscale VPN** | Persistent access, multiple tools, team sharing | Medium (one-time setup) |

## SOCKS Proxy (Default)

The SOCKS proxy is the default connectivity method. It creates an SSH tunnel through the control node.

```
┌─────────────────┐     SSH Tunnel      ┌──────────────┐
│  Your Machine   │ ──────────────────► │ Control Node │
│  localhost:1080 │                     │  (control0)  │
└────────┬────────┘                     └──────┬───────┘
         │                                     │
    SOCKS5 Proxy                         Private VPC
         │                                     │
         ▼                                     ▼
   kubectl, curl                        10.0.x.x network
```

### Quick Start

```bash
# Load environment (starts proxy automatically)
source env.sh

# Use kubectl, curl, etc. - they're pre-configured
kubectl get pods
curl http://control0:9428/health
```

### When to Use SOCKS Proxy

- Quick development and testing
- Single-user access
- When you don't want to set up additional services

For detailed SOCKS proxy usage, see [SSH Proxying](ssh-proxying.md).

## Tailscale VPN

Tailscale provides a persistent VPN connection to your cluster. Once connected, you can access cluster resources as if you were on the same network.

```
┌─────────────────┐                     ┌──────────────┐
│  Your Machine   │ ◄── Tailscale ───► │ Control Node │
│  (Tailscale IP) │      VPN Mesh      │  (Tailscale) │
└────────┬────────┘                     └──────┬───────┘
         │                                     │
    Direct Access                        Subnet Router
         │                                     │
         ▼                                     ▼
   Any application                      10.0.x.x network
```

### When to Use Tailscale

- Persistent connectivity across sessions
- Access from multiple applications without proxy configuration
- Sharing cluster access with team members
- Using tools that don't support SOCKS proxies

### Setup

Tailscale requires a one-time setup in your Tailscale account before using it with easy-db-lab.

#### Step 1: Configure Tailscale ACL

1. Go to [Tailscale ACL Editor](https://login.tailscale.com/admin/acls)
2. Add the tag to your ACL policy. Find the `tagOwners` section (or create one) and add:

```json
{
  "tagOwners": {
    "tag:easy-db-lab": ["autogroup:admin"]
  }
}
```

If you have existing tags, just add the new one:

```json
{
  "tagOwners": {
    "tag:existing-tag": ["autogroup:admin"],
    "tag:easy-db-lab": ["autogroup:admin"]
  }
}
```

#### Step 2: Create OAuth Client

1. Go to [Tailscale OAuth Settings](https://login.tailscale.com/admin/settings/oauth)
2. Click **Generate OAuth Client**
3. Set the following:
   - **Description**: easy-db-lab (or any name you prefer)
   - **Scopes**: Select **Devices: Write**
   - **Tags**: Add `tag:easy-db-lab`
4. Click **Generate**
5. Copy the **Client ID** and **Client Secret** (you won't see the secret again)

#### Step 3: Configure easy-db-lab

Run the setup profile command and enter your Tailscale credentials:

```bash
easy-db-lab setup-profile --update
```

Or provide them during initial setup:

```bash
easy-db-lab setup-profile
```

When prompted for Tailscale OAuth Client ID and Secret, enter the values from Step 2.

### Usage

#### Automatic Start (Recommended)

If you've configured Tailscale credentials in your profile, Tailscale starts automatically when you run `easy-db-lab up`.

#### Manual Start

```bash
# Start Tailscale on the control node
easy-db-lab tailscale start

# Check status
easy-db-lab tailscale status

# Stop Tailscale
easy-db-lab tailscale stop
```

#### Approve Subnet Route

After starting Tailscale for the first time, you need to approve the subnet route. You can do this manually or configure auto-approval.

##### Manual Approval

1. Go to [Tailscale Machines](https://login.tailscale.com/admin/machines)
2. Find the machine named after your control node (e.g., `control0`)
3. Click on it and find **Subnet routes**
4. Approve the route (e.g., `10.0.0.0/16`)

##### Auto-Approval (Recommended)

To avoid manual approval each time, add an `autoApprovers` section to your [Tailscale ACL](https://login.tailscale.com/admin/acls):

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

This auto-approves any `10.x.x.x` subnet route advertised by devices with the `tag:easy-db-lab` tag. After adding this, subnet routes are approved automatically when Tailscale starts.

Once approved (manually or automatically), you can access all cluster nodes directly using their private IPs.

### Using Tailscale Connection

After Tailscale is connected and the subnet route is approved:

```bash
# SSH directly to nodes using private IPs
ssh ubuntu@10.0.1.50

# Access services without proxy configuration
curl http://10.0.1.50:9428/health

# kubectl works without SOCKS proxy
kubectl get pods

# Access web UIs directly in your browser
# http://10.0.1.50:3000 (Grafana)
```

### Tailscale Commands Reference

| Command | Description |
|---------|-------------|
| `easy-db-lab tailscale start` | Start Tailscale on control node |
| `easy-db-lab tailscale stop` | Stop Tailscale on control node |
| `easy-db-lab tailscale status` | Show Tailscale connection status |

### Troubleshooting Tailscale

#### "requested tags are invalid or not permitted"

The tag must be configured in your Tailscale ACL. See [Step 1: Configure Tailscale ACL](#step-1-configure-tailscale-acl).

#### "tailnet-owned auth key must have tags set"

Tags are required for OAuth-based authentication. Ensure you've added `tag:easy-db-lab` to your ACL policy.

#### Can't reach private IPs after connecting

1. Check that the subnet route is approved in Tailscale admin (or configure [auto-approval](#auto-approval-recommended))
2. Verify Tailscale status: `easy-db-lab tailscale status`
3. On your local machine, check Tailscale is running: `tailscale status`
4. Restart Tailscale if you just added auto-approval: `easy-db-lab tailscale stop && easy-db-lab tailscale start`

#### Using a custom tag

If you want to use a different tag:

```bash
easy-db-lab tailscale start --tag tag:my-custom-tag
```

Make sure the custom tag is configured in your ACL and added to your OAuth client.

## Comparison

| Feature | SOCKS Proxy | Tailscale |
|---------|-------------|-----------|
| Setup time | Instant | ~10 minutes (one-time) |
| Persistence | Per-session | Persistent |
| Multiple apps | Requires proxy config | Works natively |
| Team sharing | Not supported | Supported |
| Requires `source env.sh` | Yes | No |
| Works offline | No | No |
| External dependencies | None | Tailscale account |

## Recommendation

- **Start with SOCKS Proxy** - It works out of the box with no setup
- **Switch to Tailscale** when you:
  - Want persistent access without running `source env.sh`
  - Need to use tools that don't support SOCKS proxies
  - Want to share cluster access with team members
  - Find yourself frequently reconnecting the SOCKS proxy
