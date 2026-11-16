## 1. Profile-based Tailscale detection

- [ ] 1.1 Add `fun isTailscaleEnabled(): Boolean = tailscaleClientId.isNotBlank() && tailscaleClientSecret.isNotBlank()` to `User` data class in `User.kt`
- [ ] 1.2 Add `fun isTailscaleEnabled(): Boolean = tailscaleActive` to `ClusterState` in `ClusterState.kt`

## 2. Init command changes

- [ ] 2.1 Add `@Option(names = ["--no-tailscale"], description = ["Disable Tailscale even if credentials are configured"]) var noTailscale = false` to `Init`
- [ ] 2.2 In `Init.prepareEnvironment()`, replace `tailscaleActive = detectLocalTailscale()` with `tailscaleActive = userConfig.isTailscaleEnabled() && !noTailscale`
- [ ] 2.3 Remove the `detectLocalTailscale()` and `parseTailscaleOutput()` top-level functions from `Init.kt`
- [ ] 2.4 Remove unused imports (`CompletableFuture`, `TimeUnit`) left over from the removed functions

## 3. Socks5ProxyStateFile data class

- [ ] 3.1 Create `Socks5ProxyStateFile` as a `@Serializable` data class in `proxy/Socks5ProxyStateFile.kt` with fields: `pid: Int`, `port: Int`, `controlHost: String`, `controlIP: String`, `clusterName: String`, `startTime: String`, `sshConfig: String`
- [ ] 3.2 In `Down.kt`, replace Jackson deserialization of the state file with `Json.decodeFromString<Socks5ProxyStateFile>(proxyStateFile.readText())`
- [ ] 3.3 Remove the inner `Socks5ProxyState` data class from `Down.kt` and its Jackson import

## 4. ProcessSocksProxyService

- [ ] 4.1 Create `ProcessSocksProxyService(private val context: Context) : SocksProxyService` in `proxy/ProcessSocksProxyService.kt`
- [ ] 4.2 Implement `ensureRunning(gatewayHost: ClusterHost): SocksProxyState`:
  - Read `File(context.workingDirectory, Constants.Vpc.SOCKS5_PROXY_STATE_FILE)` if it exists
  - Validate: PID alive via `ProcessHandle.of(pid.toLong()).isPresent`, `sshConfig` matches `File(context.workingDirectory, "sshConfig").absolutePath`, `controlIP` matches `gatewayHost.privateIp`
  - If valid: set system properties from loaded state, populate in-memory state, return `SocksProxyState`
  - If invalid or no file: fall through to start a new process
- [ ] 4.3 Implement port selection: try `Constants.Proxy.DEFAULT_SOCKS5_PORT` (1080) by attempting `ServerSocket(1080)`; on `BindException`, fall back to `ServerSocket(0).use { it.localPort }`
- [ ] 4.4 Launch process: `ProcessBuilder("nohup", "ssh", "-v", "-N", "-D", "$port", "-F", sshConfigPath, "control0")` with `redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))`, `redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null")))`, and `redirectError(ProcessBuilder.Redirect.appendTo(File(context.workingDirectory, "socks5-proxy.log")))`
- [ ] 4.5 After launch: verify port accepts TCP connections (up to 10 retries × 500ms, connect timeout 1s); log warning but proceed if verification times out
- [ ] 4.6 Write `Socks5ProxyStateFile` to `.socks5-proxy-state` in the working directory using `Json.encodeToString(state)`
- [ ] 4.7 Call `System.setProperty("socksProxyHost", "127.0.0.1")` and `System.setProperty("socksProxyPort", "$port")`
- [ ] 4.8 Store `SocksProxyState` and PID in thread-safe fields (`@Volatile` or `ReentrantLock`)
- [ ] 4.9 Implement `start(gatewayHost): SocksProxyState` — delegates to `ensureRunning()`
- [ ] 4.10 Implement `isRunning(): Boolean` — check in-memory PID or load from state file; verify `ProcessHandle.of(pid.toLong()).isPresent`
- [ ] 4.11 Implement `getState(): SocksProxyState?` — return in-memory state
- [ ] 4.12 Implement `getLocalPort(): Int` — return port from in-memory state; if 0, read from state file

## 5. SocksProxyService interface cleanup

- [ ] 5.1 Remove `fun stop()` from `SocksProxyService` interface
- [ ] 5.2 Remove `connectionCount: AtomicInteger` from `SocksProxyState` data class and its `AtomicInteger` import
- [ ] 5.3 Update KDoc on the interface — remove CLI mode / server mode lifecycle comments, describe persistent OS process model

## 6. ProxyModule

- [ ] 6.1 In `ProxyModule.kt`, replace `MinaSocksProxyService(get())` with `ProcessSocksProxyService(get())`
- [ ] 6.2 Remove `SSHConnectionProvider` injection (no longer needed by the proxy service)

## 7. ProxiedHttpClientFactory

- [ ] 7.1 Remove `socksProxyService: SocksProxyService` from `ProxiedHttpClientFactory` constructor
- [ ] 7.2 Remove `clusterStateManager: ClusterStateManager` from constructor
- [ ] 7.3 Replace the `if (tailscaleActive) { ... } else { ... }` branches in `createClient()` with a single plain `OkHttpClient` builder (no `.proxy()` call — OkHttp uses `ProxySelector.getDefault()` which reads system properties)
- [ ] 7.4 Update `ProxyModule.kt` Koin wiring to match the simplified `ProxiedHttpClientFactory` constructor

## 8. ProxiedKubernetesClientFactory and K8sClientProvider

- [ ] 8.1 In `ProxiedKubernetesClientFactory`, remove `proxyHost`, `proxyPort`, and `tailscaleActive` constructor parameters
- [ ] 8.2 In `ProxiedKubernetesClientFactory.createClient()`, read proxy port via `System.getProperty("socksProxyPort")?.toIntOrNull()`: if non-null, set `config.httpsProxy = "socks5://127.0.0.1:$port"`
- [ ] 8.3 In `K8sClientProvider`, remove `socksProxyService: SocksProxyService` from constructor; remove `tailscaleActive` local variable and `ensureRunning()` call (callers, e.g. `K3sService`, call `ensureRunning()` upstream)
- [ ] 8.4 In `K8sClientProvider.createClient()`, construct `ProxiedKubernetesClientFactory()` with no parameters
- [ ] 8.5 Simplify `KubernetesModule.kt` factory lambda — remove `SocksProxyService` and `ClusterStateManager` injection; construct `ProxiedKubernetesClientFactory()` directly
- [ ] 8.6 Update `ServicesModule.kt` `singleOf(::K8sClientProvider)` — Koin auto-injects remaining params

## 9. K3sService

- [ ] 9.1 In `DefaultK3sService.kubernetesService()`, replace `clusterStateManager.load().tailscaleActive` with `clusterStateManager.load().isTailscaleEnabled()`
- [ ] 9.2 Remove `proxyPort` variable and all proxy port passing to `ProxiedKubernetesClientFactory`
- [ ] 9.3 Construct `ProxiedKubernetesClientFactory()` with no arguments

## 10. VictoriaLogsService

- [ ] 10.1 In `DefaultVictoriaLogsService.query()`, gate `socksProxyService.ensureRunning(controlHost)` behind `if (!clusterState.isTailscaleEnabled())`
- [ ] 10.2 Remove `val proxyPort = socksProxyService.getLocalPort()` and its log line

## 11. VictoriaStreamService

- [ ] 11.1 Add `clusterStateManager: ClusterStateManager` to `DefaultVictoriaStreamService` constructor
- [ ] 11.2 Gate `socksProxyService.ensureRunning(controlHost)` in `streamMetrics()` and `streamLogs()` behind `if (!clusterStateManager.load().isTailscaleEnabled())`
- [ ] 11.3 Add `.proxy(Proxy.NO_PROXY)` to `directClient` builder so it bypasses the system proxy when connecting to external target URLs
- [ ] 11.4 `ServicesModule.kt` `factoryOf(::DefaultVictoriaStreamService)` auto-injects the new `ClusterStateManager` — no manual wiring change needed

## 12. VictoriaMetricsQueryService

- [ ] 12.1 Add `clusterStateManager: ClusterStateManager` to `DefaultVictoriaMetricsQueryService` constructor
- [ ] 12.2 Gate `socksProxyService.ensureRunning(controlHost)` behind `if (!clusterStateManager.load().isTailscaleEnabled())`
- [ ] 12.3 `ServicesModule.kt` `singleOf(::DefaultVictoriaMetricsQueryService)` auto-injects the new `ClusterStateManager` — no manual wiring change needed

## 13. CqlSessionService

- [ ] 13.1 Remove `proxyStarted: Boolean` field from `DefaultCqlSessionService`
- [ ] 13.2 In `close()`, remove `if (proxyStarted) { socksProxyService.stop(); proxyStarted = false }` block
- [ ] 13.3 In `getOrCreateSession()`, replace `clusterState.tailscaleActive` check with `clusterState.isTailscaleEnabled()`
- [ ] 13.4 Remove `proxyStarted = true` assignment

## 14. Remove MinaSocksProxyService

- [ ] 14.1 Delete `MinaSocksProxyService.kt`

## 15. Tests

- [ ] 15.1 Add `ProcessSocksProxyServiceTest`: given valid state file (matching PID, IPs, sshConfig), `ensureRunning()` does not launch a new process and returns state with correct port — use `@TempDir`, write a valid state file with `ProcessHandle.current().pid().toInt()` as the PID
- [ ] 15.2 Add `ProcessSocksProxyServiceTest`: given no state file, `ensureRunning()` writes `.socks5-proxy-state` to the working directory
- [ ] 15.3 Add `ProcessSocksProxyServiceTest`: given state file with dead PID (use `-1`), `ensureRunning()` writes a new state file
- [ ] 15.4 Add `ProcessSocksProxyServiceTest`: after successful `ensureRunning()`, `System.getProperty("socksProxyHost")` is `"127.0.0.1"` and `System.getProperty("socksProxyPort")` is non-null
- [ ] 15.5 Add `ProcessSocksProxyServiceTest`: when port 1080 is already bound, `ensureRunning()` selects a different available port (pre-bind a `ServerSocket` on 1080 in the test, assert port != 1080)
- [ ] 15.6 Add `UserTest`: `isTailscaleEnabled()` returns `true` when both `tailscaleClientId` and `tailscaleClientSecret` are non-blank; `false` when either is blank
- [ ] 15.7 Add `ClusterStateTest` (or extend existing): `isTailscaleEnabled()` returns `tailscaleActive` value
- [ ] 15.8 Add `InitTest`: with `--no-tailscale` flag, `tailscaleActive` is `false` even when `user.isTailscaleEnabled()` returns `true`; without flag, `tailscaleActive` matches `user.isTailscaleEnabled()`
- [ ] 15.9 Update `CqlSessionServiceTest`: remove the two tests that `verify(mockSocksProxy).stop()` and `verify(mockSocksProxy, never()).stop()`; replace `tailscaleActive` state setups with `isTailscaleEnabled()`-compatible state
- [ ] 15.10 Update `VictoriaStreamServiceTest`: add `ClusterStateManager` mock; add test verifying `ensureRunning()` is not called when `isTailscaleEnabled()` returns `true`
- [ ] 15.11 Update `ProxiedHttpClientFactoryTest`: simplify to verify factory creates a non-null `OkHttpClient`; remove SOCKS proxy and tailscale-specific assertions
- [ ] 15.12 Update `K3sServiceTest`: update mock for `clusterStateManager.load()` to use a state where `isTailscaleEnabled()` returns the expected value
