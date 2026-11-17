# SystemD Service Management

This directory contains service classes for managing SystemD-based services via SSH.

## Architecture

All SystemD services follow a common pattern using the **Template Method** design pattern:

- `SystemDServiceManager` - Interface defining the common service lifecycle operations
- `AbstractSystemDServiceManager` - Abstract base class providing shared implementation
- Concrete service classes (e.g., `CassandraService`, `SidecarService`) - Service-specific implementations

## Creating a New SystemD Service

To add a new SystemD service, follow this pattern:

### 1. Define the Service Interface

```kotlin
/**
 * Service for managing my-service lifecycle operations.
 */
interface MyService : SystemDServiceManager {
    // Add any service-specific methods here (optional)
    // All standard methods (start, stop, restart, isRunning, getStatus)
    // are inherited from SystemDServiceManager
}
```

### 2. Implement the Service

```kotlin
/**
 * Default implementation of MyService.
 *
 * This implementation extends AbstractSystemDServiceManager to leverage common
 * systemd service management functionality.
 */
class DefaultMyService(
    remoteOps: RemoteOperationsService,
    outputHandler: OutputHandler,
) : AbstractSystemDServiceManager("my-service", remoteOps, outputHandler),
    MyService {

    override val log: KLogger = KotlinLogging.logger {}

    // Optionally override methods for custom behavior:
    // override fun restart(host: Host): Result<Unit> = runCatching {
    //     // Custom restart logic here
    // }
}
```

### 3. Register with Dependency Injection

Add to `ServicesModule.kt`:

```kotlin
single<MyService> { DefaultMyService(get(), get()) }
```

### 4. Write Tests

Create `MyServiceTest.kt` following the pattern in existing service tests:

```kotlin
class MyServiceTest : BaseKoinTest() {
    private lateinit var mockRemoteOps: RemoteOperationsService
    private lateinit var myService: MyService

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<RemoteOperationsService> { mockRemoteOps }
                factory<MyService> { DefaultMyService(get(), get()) }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockRemoteOps = mock()
        myService = getKoin().get()
    }

    @Test
    fun `start should execute systemctl start command successfully`() {
        // Given
        val expectedCommand = "sudo systemctl start my-service"
        val successResponse = Response(text = "", stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenReturn(successResponse)

        // When
        val result = myService.start(testHost)

        // Then
        assertThat(result.isSuccess).isTrue()
        verify(mockRemoteOps).executeRemotely(eq(testHost), eq(expectedCommand), any(), any())
    }

    // Add tests for stop, restart, isRunning, getStatus
    // Add tests for failure scenarios
}
```

## Standard Operations

All services inherit these operations from `SystemDServiceManager`:

### start(host: Host): Result&lt;Unit&gt;
Starts the service on the specified host using `sudo systemctl start <service-name>`.

### stop(host: Host): Result&lt;Unit&gt;
Stops the service on the specified host using `sudo systemctl stop <service-name>`.

### restart(host: Host): Result&lt;Unit&gt;
Restarts the service on the specified host using `sudo systemctl restart <service-name>`.

This method is marked `open` and can be overridden for custom restart logic (e.g., CassandraService uses a custom restart script).

### isRunning(host: Host): Result&lt;Boolean&gt;
Checks if the service is active using `sudo systemctl is-active <service-name> || true`. `is-active` exits non-zero for a service that is not active, and a non-zero exit fails the remote command, so the printed state decides; an SSH failure still fails.

Returns `true` if the service is active, `false` otherwise.

### getStatus(host: Host): Result&lt;String&gt;
Gets the full systemd status output using `sudo systemctl status <service-name>`.

## Customization Points

### Override Methods
You can override any of the standard operations for service-specific behavior:

```kotlin
override fun restart(host: Host): Result<Unit> = runCatching {
    remoteOps.executeRemotely(host, "/custom/restart-script")
    log.info { "Custom restart completed on ${host.alias}" }
}
```

### Add Service-Specific Methods
Add methods unique to your service:

```kotlin
interface MyService : SystemDServiceManager {
    fun reload(host: Host): Result<Unit>
}

class DefaultMyService(...) : AbstractSystemDServiceManager(...), MyService {
    override fun reload(host: Host): Result<Unit> = runCatching {
        remoteOps.executeRemotely(host, "sudo systemctl reload $serviceName")
    }
}
```

## Examples

### Service with Custom Behavior
See `CassandraService` - overrides `restart()` to use a custom script and adds `waitForUpNormal()` method.

## Error Handling

All methods return `Result<T>` types for explicit error handling:

```kotlin
myService.start(host).onSuccess {
    log.info { "Service started successfully on ${host.alias}" }
}.onFailure { exception ->
    log.warn(exception) { "Failed to start service on ${host.alias}" }
}
```

## Best Practices

1. **Use Result Types**: Never throw exceptions directly - wrap operations in `runCatching {}`
2. **Log Appropriately**: Use `log.info` for successful operations, `log.debug` for status checks
3. **Test Thoroughly**: Test both success and failure scenarios
4. **Name the failures you handle**: A `catch` names the exception types its body can throw; never `catch (e: Exception)` and never `@Suppress("TooGenericExceptionCaught")`. A boundary that must turn any failure into a report or fallback uses `runCatchingException { }` (in `Throwables.kt`), which captures an `Exception` and still rethrows a JVM `Error`.
5. **Document Custom Behavior**: Clearly document any overridden methods or custom logic

## Cassandra Versions (`cassandraversions/`)

The on-demand Cassandra install (#1130). The CLI resolves everything; the node scripts take it as arguments.

- `CassandraVersionResolver` turns a name and the CLI options into one `InstallSpec`, reading the sources in order: the state record, a profile build, `--url`, the ASF releases, the cassandra-builds `nightly` release, the build catalog. `VersionName` is the one rule for a valid name, checked here and in `ProfileBuildSource`, and again by the node scripts.
- `VersionSources` is the port for every source but the state (`DefaultVersionSources`: `ProfileBuildSource`, `PublicHttp` for the public hosts, `VersionSourceCache` for the ASF and cassandra-builds lists, the profile's build catalog). Tests use a fixed fake.
- `ReleaseLines` is the release-line JDK table (`cassandra/release-lines.yaml`), the last layer of the JDK choice.
- `CassandraInstaller` plans each node's action (download, present, refused) with no change, runs `install-cassandra-version` in parallel, records each node's outcome and runs `use-cassandra`. A node that would download a URL whose credential the record redacted is refused with the `--reinstall --url` to run.
- `AxonOpsAgents` installs the agent for each node's release and JDK and writes the systemd drop-in: at `cassandra start`, and after `cassandra use` on a node whose last start loaded an agent.
- `CassandraNodeRestorer` puts a replaced node back on its active version (design D7); its caller applies the config.
