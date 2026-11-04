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

## helm and kubectl: `ClusterTooling`

`HelmService` and `KubectlService` are not SystemD services. They build helm and kubectl argument lists and run them through `ClusterTooling` (`ClusterTooling.kt`), which decides where the tool runs. They take no `host`.

- `SshClusterTooling` (the default Koin binding in `ServicesModule`) runs the tool on the control node with `KUBECONFIG=${Constants.K3s.REMOTE_KUBECONFIG}`, through `RemoteOperationsService`. It single-quotes only the arguments a service marks in `shellQuoted`, uploads stdin to a temporary file for a `-` argument and removes it afterwards, and maps a failure to `ClusterToolFailedException` with the exit status and stderr.
- `LocalClusterTooling` runs the tool as a local process through the `<workspace>/bin` wrapper in the kit process environment (`KitProcessEnvironment`), with stdin piped and no temporary file. helm's config, cache and data directories sit under `<workspace>/.helm/`. It needs helm and kubectl on the host. It is bound by a Koin override until #1040 selects the form per environment.
- A new helm or kubectl call for kits or commands goes into one of those two services as an argument list. Never call `ProcessBuilder` for helm or kubectl outside `LocalClusterTooling`, and never run them through `RemoteOperationsService` from a kit or command path.
- The AWS-only Cilium services (`CiliumService`, which runs `helm status cilium` and `cilium`, and `CiliumInspectionService`, which runs `kubectl get` on Cilium objects) are the exception: Cilium is installed on the AWS control node only, so they stay on `RemoteOperationsService` with the `KUBECONFIG=${Constants.K3s.REMOTE_KUBECONFIG}` prefix.

Test the services with `RecordingClusterTooling` (test sources), which records each call and asserts on the argument lists built; do not mock `RemoteOperationsService` for them. `SshClusterToolingTest` pins the SSH command strings with a mocked `RemoteOperationsService`, and `LocalClusterToolingIntegrationTest` runs the typed steps against a real K3s container through the local form.

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
5. **Document Custom Behavior**: Clearly document any overridden methods or custom logic
