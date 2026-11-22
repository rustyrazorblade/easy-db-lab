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

- `SshClusterTooling` (the Koin binding in `ServicesModule`, and today the only implementation) runs the tool on the control node with `KUBECONFIG=${Constants.K3s.REMOTE_KUBECONFIG}`, through `RemoteOperationsService`. Every argument reaches the remote shell as one word: `shellQuote()` joins an argument of shell-safe characters as it is and single-quotes any other one (a space, `;`, `$`, `^`, which an interpolated kit variable or a helm filter can hold). `runWithInput` takes the arguments as `ToolArg`s with exactly one `ToolArg.Input`, which reaches the tool as `-`, and streams the input over the same SSH exec channel as the command's stdin (`RemoteOperationsService.executeRemotelyWithInput`): one round trip and no temp file. A plain value that is `-` stays a value. It maps a failure to `ClusterToolFailedException` with the exit status, stderr, stdout and the control node it ran on. A form that runs the tools on the developer's machine, for a local cluster, is deferred to #1163.
- A new helm or kubectl call for kits or commands goes into one of those two services as an argument list. Never call `ProcessBuilder` for helm or kubectl, and never run them through `RemoteOperationsService` from a kit or command path.
- Cilium's helm and kubectl calls go through the seam too: `CiliumService` probes its release with `HelmService.releaseExists`, and `CiliumInspectionService` reads the Cilium objects with `ClusterTooling.run`. The one exception is the `cilium` CLI itself (`cilium install` / `cilium upgrade` in `CiliumService`), which stays on `RemoteOperationsService` with the `KUBECONFIG=${Constants.K3s.REMOTE_KUBECONFIG}` prefix.

Test the services with `RecordingClusterTooling` (test sources), which records each call and asserts on the argument lists built; do not mock `RemoteOperationsService` for them. `SshClusterToolingTest` and `ClusterToolingSshCommandsTest` check the remote commands with a mocked `RemoteOperationsService`, including that each argument reaches a real `/bin/sh` as exactly one word. `SshClusterToolingK3sIntegrationTest` (integration tier) runs the real kubectl of a K3s container through the seam, and `SSHClientStreamingIntegrationTest` checks stdin over a real sshd.

Kit steps that run a command on every node of a type (`platform-pvs-delete`, `platform-pvs`) run through `WorkloadStepExecutor.onEachHost`, which uses `HostOperationsService.collectFromHosts(parallel = true)`: every node runs, at most `Constants.Scale.HOST_CONCURRENCY` at once. The `label` step patches every node through one fabric8 client (`K8sManifestOperations.labelNodes`). Either throws one `NodeStepFailedException` naming the first `Constants.Scale.MAX_NAMED_IN_ERROR` failed nodes and counting the rest.

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
- `ReleaseLines` is the release-line JDK table (`cassandra/release-lines.yaml`), the last layer of the JDK choice.  Its `build_java` is the JDK a cassandra-builds nightly of the line is compiled on; a nightly with no manifest runs only on that JDK and newer, and `ReleaseLinesBuildPlanTest` checks it against `.github/cassandra-image/resolve-build-plan.sh`.
- `CassandraInstaller` plans each node's action (download, present, refused) with no change, runs `install-cassandra-version` in parallel, records each node's outcome and runs `use-cassandra`. A node that would download a URL whose credential the record redacted is refused with the `--reinstall --url` to run.
- `AxonOpsAgents` installs the agent for each node's release and JDK and writes the systemd drop-in: at `cassandra start`, and after `cassandra use` on a node whose last start loaded an agent.
- `CassandraNodeRestorer` puts a replaced node back on its active version (design D7); its caller applies the config. Nothing calls it yet: issue 1158 left the replacement path without it, and a follow-up wires it in.

## Per-Node Bring-Up (`bringup/`)

`up` brings every node up through its own pipeline (`NodeBringUp`), not one phase at a time across
the cluster. The user-facing behaviour is in `docs/user-guide/large-clusters.md`; the decisions are
in `openspec/changes/issue-1158/design.md` (D1-D12) until it is archived.

- **Ports**: `NodeSteps`, `ControlNodeSteps`, `ControlPlaneConnection`, `NodeTracker` and
  `HealthSampler` (`BringUpPorts.kt`), `InstanceTracker` and `InstanceFleet`. `NodeBringUp`, `NodePipeline`, `ControlPipeline` and
  `NodeReplacer` see only these. Files directly in `bringup/` import no `providers.aws.*` or
  `services.aws.*` and nothing K3s- or Cilium-specific.
- **Adapters**: the EC2 ones (`Ec2InstanceFleet`, `Ec2InstanceStateWatcher`, and `Ec2K3sBringUp`,
  which assembles the port set) are in `bringup/aws/`; the K3s and Cilium ones (`K3sNodeSteps`,
  `K3sControlSteps`, `Fabric8ControlPlaneConnector`, `Fabric8ControlPlaneReader`, `NodeWatcher`,
  `NodeCleanup`, `ControlPlaneSampler`) are in `bringup/k3s/`. A second provider
  adds its own adapter package; it never changes the ports.
- **Coordination**: one virtual thread per node, made by `PipelineThreads`; `NodeBringUp.run`
  shuts the executor down (never `close`: since JDK 25 that waits, with no bound, for a cancelled
  pipeline's thread) and then closes `PipelineThreads`, joining every pipeline thread, because a
  cancelled pipeline's thread may still save the state. The join is
  bounded by `BringUpTiming.pipelineStopGrace`; a thread still alive after it (each is named by its
  node's alias) is emitted as `Provision.PipelinesAbandoned` and left behind. Shared limits are in `Limiters.kt` (join slots,
  SSH sessions); the workspace files, the registry certificate and the open control plane
  (released by the gate) are latches in `BringUpRun`. Use `ReentrantLock`, not `synchronized`, so a virtual thread never pins.
- **Failure**: `StepRunner` retries only an `AttemptFailed`; a node step that still fails goes to
  `NodeReplacer` (budget in `ReplacementBudget`). Control steps are retried and never replaced:
  `ControlNodeFailedException`. `ClusterStateWriter` is the only writer of hosts to `state.json`
  during bring-up; it writes through `ClusterStateManager.update`, so a change another command saves meanwhile is kept. Each attempt after the first is marked in the node's output as
  `<step> attempt <N>` before it runs. When the pipeline is interrupted, `StepRunner` interrupts
  the attempt and waits once, up to `BringUpTiming.attemptStopGrace`, only if it is still running;
  one still running after that is emitted as `Provision.AttemptAbandoned` and left behind. When the
  instance ended, it interrupts the attempt and returns at once, so the replacement starts.
  A node that fails before its node cleanup, after a replacement or when launched in this `up`,
  deletes the alias's old Node and node password in `NodePipeline.failedBeforeJoin`, so a stale
  NotReady Node cannot fail the post-steps' rollout wait. It emits `NodeFailed` first, then waits
  for the gate to delete; a failed deletion is added to the reason in `NodesFailed`.
  `ControlPipeline` calls `ControlPlaneHealthMonitor.stop()` before closing its ticker, and `tick`
  drops a sample that ends after the stop or with its thread interrupted.
- **Output tail**: every node's script lines go to `output/NodeOutput` as `[alias] ...`, each line
  carrying the instance that printed it. `NodeBringUp` takes a `BringUpReporting` (the event bus and
  the `NodeOutput`); `BringUpRun` exposes both. `InstanceRun` calls `reset(alias, instanceId)` when
  an instance starts its steps, so a replacement's tail holds only its own lines, and a line from a
  stale instance is printed but not kept. `NodePipeline.failed` and `NodeBringUp.controlFailed` put
  the tail on `NodeFailed` and `ControlNodeFailed` as `outputTail`. `up --quiet` wraps only
  `NodeBringUp.run` in `NodeOutput.quietly`: quiet ends when the last pipeline ends, and the
  post-steps print as usual. The tail is kept in quiet mode too.
- **Tests**: `src/test/.../services/bringup/BringUpFakes.kt` and `FakeInstanceFleet` drive every
  pipeline without SSH or AWS (`NodeBringUpTest`, `NodeCleanupBringUpTest`, `ControlNodeFailureBringUpTest`); `UpTestFixture` wires the real `K3sBringUpSteps` over a fake
  `RemoteOperationsService` with fake tracker, fleet and control plane.

## Node Setup and OS Tuning

`NodeSetupService` sets up one node: `environment.sh`, the per-type files, the hostname, then
`setup_instance.sh`. Before the script runs it uploads the packaged OS tuning files
(`commands/node-tuning/`, listed in `Constants.NodeTuning.FILES`) into `node-tuning/` beside
it with one directory upload, staged by `NodeTuningFiles`. The script's `tune_node` installs
them, applies them and runs `edl-node-tuning verify`, whose `ERROR:` lines fail the node's setup
step. A new tuning file goes in that directory, in `Constants.NodeTuning.FILES` and in
`tune_node`; `NodeTuningFilesTest` and `setup-instance-tuning.test.sh` fail until all three
agree. The script is run as `sudo EDL_INSTANCE_TYPE=<type> bash setup_instance.sh` with the
node's instance type from `NodeSetupContext.instanceTypes` (the cluster state), so nothing on the
node asks the cloud provider. The values and why are in `docs/user-guide/node-tuning.md`; the decisions are in
`openspec/changes/issue-1199/design.md` until it is archived.

## Pod Network Sysctls

The per-namespace `net.*` keys of the tuning file also reach every non-hostNetwork pod
(spec `openspec/specs/pod-network-tuning/`, decisions in `openspec/changes/archive/2026-11-19-issue-1202/design.md`):

- `PodNetworkSysctls` reads the packaged `60-easydblab.conf` and returns the keys of
  `Constants.NodeTuning.POD_NETWORK_SYSCTLS` with the file's values. It is the only source of the
  numbers; `PodNetworkSysctlsTest` fails when a `net.*` key of the file is in neither
  `POD_NETWORK_SYSCTLS` nor `NOT_INJECTED_NETWORK_SYSCTLS`, or in both.
- `PodNetworkTuning` is the first of `UpPostSteps`: it applies `configuration/PodNetworkSysctlPolicyBuilder`
  through `K8sManifestOperations`, then polls `PodSysctlProbe` (a `dryRun=All` pod,
  `Fabric8PodSysctlProbe`) until the pod carries every value, up to
  `POD_SYSCTL_POLICY_READY_SECONDS`; each probe runs on a virtual thread given only the time left,
  so a probe that never answers ends the wait at the deadline (the last real answer is reported). A cut-off probe is interrupted, which makes fabric8 drop its request and close the client's connection, so it does not linger for the 60s request timeout (`PodSysctlProbeDeadlineIntegrationTest`). It emits `Platform.PodSysctlPolicyReady`, or
  `PodSysctlPolicyNotApplied` / `PodSysctlPolicyNotInEffect` / `PodSysctlProbeFailed` (a probe
  that threw, which the poll does not retry) and throws `CommandFailedException`; an interrupt,
  during a probe or in the wait between probes, propagates and emits nothing; an `InterruptedException` thrown on the probe's own thread is a probe failure. One deadline bounds the poll (`pollUntilDeadline`) and each probe. `Fabric8PodSysctlProbe.resultOf`
  calls a statusless client exception unreachable only when an `IOException` or a `TimeoutException` (not
  a Jackson parse failure) caused it; any other is a defect and throws.
- `bringup/pollUntil` cuts each wait to the time left, so it never sleeps past its deadline, and turns
  resilience4j's `CancellationException` for an interrupted wait into an `InterruptedException`.
- K3s only: `KubeletSysctlsDropIn` installs `25-kubelet-sysctls.yaml` (`kubelet-arg+:
  allowed-unsafe-sysctls=...`) before the start script on the server (`DefaultK3sService.startServer`)
  and every agent (`DefaultK3sAgentService.start`). `start-k3s-server.sh` writes
  `30-admission-policy.yaml`, the API server feature gate and runtime-config the beta API needs.
