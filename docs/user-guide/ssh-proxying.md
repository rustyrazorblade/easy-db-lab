# SSH Proxying

easy-db-lab uses a SOCKS5 proxy to provide secure access to private cluster resources. This proxy allows you to access services running on private IPs (10.0.x.x) from your local machine.

## How It Works

When you run `source env.sh`, a SOCKS5 proxy is started via SSH dynamic port forwarding to the control node. This creates a secure tunnel that routes traffic to the private cluster network.

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
   k9s, browsers                        Grafana, Victoria Logs
```

## Starting the Proxy

The proxy starts automatically when you load the environment:

```bash
source env.sh
```

You can also manage it manually:

```bash
# Start the proxy
start-socks5

# Check status
socks5-status

# Stop the proxy
stop-socks5
```

## Using the Proxy

### Automatic Proxy Commands

These commands are automatically configured to use the proxy after `source env.sh`:

| Command | Description |
|---------|-------------|
| `kubectl` | Kubernetes CLI |
| `k9s` | Kubernetes TUI |
| `curl` | HTTP client |
| `skopeo` | Container image tool |

Example:

```bash
source env.sh
kubectl get pods
curl http://control0:9428/health
```

### Manual Proxy Usage

For other commands, use the `with-proxy` wrapper:

```bash
with-proxy <command>

# Examples
with-proxy wget http://10.0.1.50:8080/api
with-proxy http http://control0:3000/api/health
```

### Browser Access

Configure your browser to use the SOCKS5 proxy for accessing cluster web UIs:

| Setting | Value |
|---------|-------|
| SOCKS Host | `localhost` |
| SOCKS Port | `1080` |
| SOCKS Version | 5 |

Then access cluster services directly:

- **Grafana**: `http://control0:3000`
- **Victoria Metrics**: `http://control0:8428`
- **Victoria Logs**: `http://control0:9428`

## Proxy Status

Check if the proxy is running:

```bash
socks5-status
```

Example output:

```
Checking SOCKS5 proxy status...
Active SSH SOCKS5 proxy:
  - PID: 12345
  - Port: 1080
  - Control Host: control0
```

## Using a Different Port

If port 1080 is already in use:

```bash
# Start on a different port
start-socks5 1081

# Commands will automatically use the new port
kubectl get pods
```

## Troubleshooting

### "Connection refused" errors

1. Check if the proxy is running:
   ```bash
   socks5-status
   ```

2. Start the proxy if needed:
   ```bash
   start-socks5
   ```

3. Verify the control node is accessible:
   ```bash
   ssh control0 hostname
   ```

### Proxy not working after network change

If your network connection changed (WiFi switch, VPN, etc.):

```bash
# Stop stale proxy
stop-socks5

# Reload environment
source env.sh
```

### Port already in use

```bash
# Check what's using the port
lsof -i :1080

# Use a different port
start-socks5 1081
```

### Commands timing out

The proxy requires an active SSH connection. If commands time out:

1. Check cluster status: `easy-db-lab status`
2. Verify SSH works: `ssh control0 hostname`
3. Restart the proxy: `stop-socks5 && start-socks5`

## Security

- All traffic is encrypted via SSH
- The proxy only listens on localhost (not exposed externally)
- Authentication uses your SSH key (no passwords stored)
- The tunnel is established to the control node, which has access to the private VPC
