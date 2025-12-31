# SOCKS Proxy Architecture

This document describes the internal SOCKS5 proxy implementation used by easy-db-lab for programmatic access to private cluster resources.

## Overview

easy-db-lab has **two separate proxy systems**:

| Proxy | Purpose | Implementation |
|-------|---------|----------------|
| Shell Proxy | User shell commands (`kubectl`, `curl`) | SSH CLI (`ssh -D`) via `env.sh` |
| JVM Proxy | Internal Kotlin/Java code | Apache MINA SSH library |

This document covers the **JVM Proxy** used internally by easy-db-lab.

## Why Two Proxies?

The shell proxy (started by `source env.sh`) works for command-line tools that respect `HTTPS_PROXY` environment variables. However, JVM code needs programmatic proxy configuration:

- Java's `HttpClient` requires a `ProxySelector` instance
- The Cassandra driver needs SOCKS5 configuration at the Netty level
- The Kubernetes fabric8 client needs proxy settings
- Operations should work without requiring users to run `source env.sh` first

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     easy-db-lab JVM                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌────────────────────┐    ┌────────────────────────┐           │
│  │ SocksProxyService  │    │ ProxiedHttpClientFactory│           │
│  │   (interface)      │    │                        │           │
│  └─────────┬──────────┘    └───────────┬────────────┘           │
│            │                           │                         │
│            ▼                           ▼                         │
│  ┌─────────────────────┐    ┌────────────────────────┐          │
│  │ MinaSocksProxyService│    │   SocksProxySelector   │          │
│  │ (Apache MINA impl)  │    │  (custom ProxySelector)│          │
│  └─────────┬───────────┘    └────────────────────────┘          │
│            │                                                     │
│            ▼                                                     │
│  ┌─────────────────────┐                                        │
│  │ SSHConnectionProvider│                                        │
│  │ (manages SSH sessions)│                                       │
│  └─────────┬────────────┘                                        │
│            │                                                     │
└────────────┼─────────────────────────────────────────────────────┘
             │
             ▼ SSH Dynamic Port Forwarding
   ┌──────────────────┐
   │   Control Node   │
   │   (control0)     │
   └──────────────────┘
```

## Key Classes

### SocksProxyService

**Location**: `com.rustyrazorblade.easydblab.proxy.SocksProxyService`

Interface defining proxy operations:

```kotlin
interface SocksProxyService {
    fun ensureRunning(gatewayHost: ClusterHost): SocksProxyState
    fun start(gatewayHost: ClusterHost): SocksProxyState
    fun stop()
    fun isRunning(): Boolean
    fun getState(): SocksProxyState?
    fun getLocalPort(): Int
}
```

### MinaSocksProxyService

**Location**: `com.rustyrazorblade.easydblab.proxy.MinaSocksProxyService`

Apache MINA-based implementation that:

1. Establishes an SSH connection to the gateway host
2. Starts dynamic port forwarding on a random available port
3. Maintains thread-safe state for concurrent access

Key implementation details:

- Uses `ReentrantLock` for thread safety
- Dynamically finds an available port via `ServerSocket(0)`
- Extracts the underlying `ClientSession` from the SSH client for port forwarding
- Supports idempotent `ensureRunning()` for reuse across operations

### ProxiedHttpClientFactory

**Location**: `com.rustyrazorblade.easydblab.proxy.ProxiedHttpClientFactory`

Creates `java.net.http.HttpClient` instances configured for SOCKS5 proxy:

```kotlin
class ProxiedHttpClientFactory(
    private val socksProxyService: SocksProxyService,
) : HttpClientFactory {

    override fun createClient(): HttpClient {
        val proxyPort = socksProxyService.getLocalPort()
        val proxySelector = SocksProxySelector(proxyPort)

        return HttpClient
            .newBuilder()
            .proxy(proxySelector)
            .connectTimeout(CONNECTION_TIMEOUT)
            .build()
    }
}
```

### SocksProxySelector

**Location**: `com.rustyrazorblade.easydblab.proxy.ProxiedHttpClientFactory` (private class)

Custom `ProxySelector` that returns a SOCKS5 proxy for all URIs:

```kotlin
private class SocksProxySelector(
    private val proxyPort: Int,
) : ProxySelector() {
    private val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("localhost", proxyPort))

    override fun select(uri: URI?): List<Proxy> = listOf(proxy)

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        // Handle connection failures if needed
    }
}
```

**Important**: Java's `ProxySelector.of()` creates HTTP proxies, not SOCKS5. This custom implementation is required for SSH dynamic port forwarding.

### SocksProxyNettyOptions

**Location**: `com.rustyrazorblade.easydblab.driver.SocksProxyNettyOptions`

Configures the Cassandra driver to use SOCKS5 proxy at the Netty level for CQL connections.

## Dependency Injection

The proxy components are registered in `ProxyModule`:

```kotlin
val proxyModule = module {
    // Singleton - maintains proxy state across requests
    single<SocksProxyService> { MinaSocksProxyService(get()) }

    // Factory for creating proxied HTTP clients
    single<HttpClientFactory> { ProxiedHttpClientFactory(get()) }
}
```

## Usage Patterns

### Querying Victoria Logs

```kotlin
class DefaultVictoriaLogsService(
    private val socksProxyService: SocksProxyService,
    private val httpClientFactory: HttpClientFactory,
) : VictoriaLogsService {

    override fun query(...): Result<List<String>> = runCatching {
        // Ensure proxy is running to control node
        socksProxyService.ensureRunning(controlHost)

        // Create HTTP client that routes through proxy
        val httpClient = httpClientFactory.createClient()

        // Make request to private IP
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://${controlHost.privateIp}:9428/..."))
            .build()

        httpClient.send(request, BodyHandlers.ofString())
    }
}
```

### Kubernetes API Access

The `K8sService` uses the proxy for fabric8 Kubernetes client connections to the private K3s API server.

### CQL Sessions

The `CqlSessionFactory` configures the Cassandra driver with SOCKS5 proxy settings via `SocksProxyNettyOptions`.

## Lifecycle

### CLI Mode

In CLI mode (single command execution):

1. Service starts proxy when needed
2. Operations complete
3. Proxy remains running for subsequent operations in same process

### Server/MCP Mode

In server mode (long-running process):

1. Proxy starts on first request requiring cluster access
2. Reused across multiple requests (connection count tracked)
3. Stopped on server shutdown

## Thread Safety

`MinaSocksProxyService` uses a `ReentrantLock` to protect:

- Proxy state changes
- Session management
- Port allocation

This ensures safe concurrent access when multiple threads need cluster resources.

## Error Handling

Common failure scenarios:

| Error | Cause | Resolution |
|-------|-------|------------|
| "HTTP/1.1 header parser received no bytes" | Using HTTP proxy instead of SOCKS5 | Ensure `SocksProxySelector` returns `Proxy.Type.SOCKS` |
| Connection timeout | Control node not accessible | Verify SSH connectivity to control0 |
| Port bind failure | Port already in use | Service automatically finds available port |

## Testing

When testing code that uses the proxy:

```kotlin
class MyServiceTest : BaseKoinTest() {
    // BaseKoinTest provides mocked SocksProxyService

    @Test
    fun testWithMockedProxy() {
        val mockProxyService = mock<SocksProxyService>()
        whenever(mockProxyService.getLocalPort()).thenReturn(1080)

        // Test your service with mocked proxy
    }
}
```

## Related Files

| File | Purpose |
|------|---------|
| `proxy/SocksProxyService.kt` | Interface definition |
| `proxy/MinaSocksProxyService.kt` | Apache MINA implementation |
| `proxy/ProxiedHttpClientFactory.kt` | HTTP client factory with SOCKS5 |
| `proxy/ProxyModule.kt` | Koin DI registration |
| `driver/SocksProxyNettyOptions.kt` | Cassandra driver proxy config |
| `driver/SocksProxyDriverContext.kt` | Driver context with proxy |
| `services/VictoriaLogsService.kt` | Example usage |
