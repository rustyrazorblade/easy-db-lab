## 1. Stop the global SOCKS hijack

- [x] 1.1 Add `Constants.Proxy.PORT_PROPERTY = "easydblab.socks5Port"` with KDoc explaining why it isn't `socksProxyHost`/`socksProxyPort`.
- [x] 1.2 In `ProcessSocksProxyService.applySystemProperties`, publish only `PORT_PROPERTY`; stop setting `socksProxyHost`/`socksProxyPort`. Update the class KDoc.

## 2. Make the tunnel-using clients explicit

- [x] 2.1 `KubernetesClientFactory`: read the port from `Constants.Proxy.PORT_PROPERTY` (logic otherwise unchanged); update KDoc.
- [x] 2.2 `ProxiedHttpClientFactory`: install an explicit SOCKS `ProxySelector` built from `PORT_PROPERTY` (NO_PROXY when absent); update KDoc. (Extracted as `Socks5ProxySelector`.)

## 3. Teardown + docs

- [x] 3.1 `Down.kt`: clear `PORT_PROPERTY` (drop the obsolete `socksProxyHost`/`socksProxyPort` clears).
- [x] 3.2 Update KDoc/comments referencing the old global properties (`K8sClientProvider`, `KubernetesModule`).
- [x] 3.3 Audit for any ad-hoc `OkHttpClient` construction outside the factory that relied on the global proxy; route through the factory or document why direct is correct. (Fixed: `GrafanaDashboardService`'s client now comes from the proxied factory — it calls the control node's private IP. Confirmed direct-is-correct: `TailscaleService` → external Tailscale API; `VictoriaStreamService.directClient` → already `NO_PROXY` for external URLs.)

## 4. Tests

- [x] 4.1 Test (in `ProcessSocksProxyServiceTest`, reuse path) that the service publishes `PORT_PROPERTY` and does NOT set the global `socksProxyHost`.
- [x] 4.2 Test `Socks5ProxySelector`: returns a SOCKS proxy on the published port when set, `NO_PROXY` when unset/invalid.
- [x] 4.3 Test `KubernetesClientFactory` sets `Config.httpsProxy` from `PORT_PROPERTY` and leaves it unset when the property is absent.
- [x] 4.4 Tailscale path covered by the "absent port" cases in 4.2 (selector → NO_PROXY) and 4.3 (factory → no httpsProxy).

## 5. Verification

- [ ] 5.1 (awaiting user) Rebuild and run `up` on the already-UP cluster; confirm provisioning (EC2/IAM) and the S3 state backup both succeed (no `100.64`/tunnel errors).
