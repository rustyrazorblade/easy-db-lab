## Why

The SOCKS proxy (SSH dynamic port forwarding) is used for all cluster traffic — CQL, HTTP (Victoria metrics/logs), and K8s API calls — even when Tailscale is active. When Tailscale is running locally, the cluster's private IPs are directly reachable, making the SSH tunnel unnecessary overhead.

## What Changes

- Tailscale presence is detected locally (via `tailscale status --json`) during `init` and stored in `state.json` as `tailscaleActive`
- When `tailscaleActive` is `true`, all three proxy consumers (CQL driver, OkHttp, Fabric8 K8s client) connect directly to private IPs with no SOCKS proxy
- The SOCKS proxy is never started when Tailscale is active

## Capabilities

### New Capabilities

- `tailscale-direct-connect`: Detect local Tailscale at init time and bypass the SOCKS proxy for all cluster connections when active

### Modified Capabilities

- `networking`: Connection routing behavior changes — direct path added alongside existing SOCKS proxy path

## Impact

- `ClusterState` / `state.json`: new `tailscaleActive` boolean field
- `Init` command: runs local Tailscale detection during state setup
- `CqlSessionService` + `CqlSessionFactory`: conditional proxy bypass for CQL
- `ProxiedHttpClientFactory`: conditional proxy bypass for Victoria metrics/logs HTTP
- `ProxiedKubernetesClientFactory` + its Koin module: conditional proxy bypass for K8s API
