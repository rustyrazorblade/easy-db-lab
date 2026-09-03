## Why

The SOCKS5 proxy is applied as **global JVM system properties** (`socksProxyHost`/`socksProxyPort`), so `java.net` routes *every* socket the process opens through the tunnel — including AWS SDK calls. This is wrong: AWS public endpoints (S3, EC2, IAM, STS) are reachable directly and must not go through the cluster tunnel. It breaks two real workflows on corporate networks:

- **State backup/restore to S3 fails.** The S3 connection is yanked off the normal OS path (where the corporate proxy, e.g. Zscaler, would carry it) and forced through the SSH tunnel to the control node, which can't reach the corporate-synthetic `100.64.x` address → timeout. Confirmed: `aws s3 ls`/`head-bucket` on the same machine, with no tunnel, reaches the bucket fine — the tunnel hijack is the sole cause.
- **`up` on an already-UP cluster fails.** The proxy starts before provisioning, so EC2/IAM control-plane calls get force-routed through the tunnel and time out / are refused.

## What Changes

- Stop setting the standard `socksProxyHost`/`socksProxyPort` JVM properties (which make `java.net` tunnel everything).
- Publish the active proxy port under a private property (`easydblab.socks5Port`) that only easy-db-lab's own clients read.
- The two clients that genuinely need the tunnel configure the SOCKS proxy **explicitly**: fabric8 K8s (already does, via `Config.httpsProxy`) and the OkHttp factory (new: explicit SOCKS `ProxySelector` from the port).
- AWS SDK and all other traffic go **direct** by default.
- The proxy continues to start automatically on an UP cluster (REQ-NET-005 is unchanged in that respect); it just stops capturing non-cluster traffic.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `networking`: refine the SOCKS proxy requirement (REQ-NET-005) so the proxy routes only cluster-internal traffic, never public-internet/AWS traffic.

## Impact

- **Code:** `proxy/ProcessSocksProxyService.kt` (publish private port property, stop setting global socks props), `proxy/ProxiedHttpClientFactory.kt` (explicit SOCKS `ProxySelector`), `kubernetes/KubernetesClientFactory.kt` (read new property), `commands/Down.kt` (clear new property), `Constants.kt` (new property name), plus KDoc in `K8sClientProvider`/`KubernetesModule`.
- **Behavior:** AWS SDK calls always direct; cluster-internal (K8s API, VictoriaMetrics/Logs) still tunneled.
- **No breaking changes** — clusters are ephemeral; the shell `env.sh` wrappers (which use `SOCKS5_PROXY_PORT` from the state file) are unaffected.
