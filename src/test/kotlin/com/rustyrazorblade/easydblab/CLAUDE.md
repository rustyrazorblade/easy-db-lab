# Test Infrastructure

## BaseKoinTest

All tests that need dependency injection should extend `BaseKoinTest`. It provides:

- Automatic Koin setup/teardown per test (`@BeforeEach` / `@AfterEach`)
- `tempDir: File` — JUnit `@TempDir` managed temporary directory
- `context: Context` — lazy-loaded test context

### Automatically Mocked Services

`BaseKoinTest.coreTestModules()` provides these mocks out of the box:

| Service | Mock Type |
|---------|-----------|
| `AWS` | Real class with mocked clients |
| `IamClient`, `S3Client`, `StsClient` | Mockito mocks |
| `AWSClientFactory`, `AMIValidator` | Mockito mocks |
| `User` | Test user (region: "us-west-2", email: "test@example.com") |
| `OutputHandler` | `BufferedOutputHandler` (captures output) |
| `SSHConnectionProvider` | Mock with `MockSSHClient` |
| `RemoteOperationsService` | Mock with no-op implementations |
| `Context`, `ContextFactory` | Test context with temp directories |
| `UserConfigProvider` | From test context |
| `CommandExecutor` | Pass-through implementation |

### Adding Test-Specific Mocks

Override `additionalTestModules()` to register mocks for services not in the core set:

```kotlin
class MyCommandTest : BaseKoinTest() {
    private lateinit var mockK8sService: K8sService
    private lateinit var mockClusterStateManager: ClusterStateManager

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<K8sService>().also { mockK8sService = it }
                }
                single {
                    mock<ClusterStateManager>().also { mockClusterStateManager = it }
                }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockK8sService = getKoin().get()
        mockClusterStateManager = getKoin().get()
        whenever(mockClusterStateManager.load()).thenReturn(testClusterState)
    }
}
```

**Important:** When a command uses `by inject()` for a service, you MUST provide a mock in `additionalTestModules()` or the test will fail with a Koin resolution error.

## Verifying Output

```kotlin
val outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler
command.execute()
val output = outputHandler.messages.joinToString("\n")
assertThat(output).contains("Success")
```

## TestPrompter

Simulates user input for interactive commands:

```kotlin
override fun additionalTestModules(): List<Module> =
    listOf(
        TestModules.testPrompterModule(
            mapOf(
                "cluster name" to "my-cluster",
                "region" to "us-west-2",
            ),
        ),
    )
```

Features: exact match, partial match (case-insensitive), default fallback, sequential responses, call logging.

```kotlin
val prompter = TestPrompter()
prompter.addSequentialResponses("retry", "first", "second", "third")
assertThat(prompter.wasPromptedFor("cluster name")).isTrue()
```

## Custom AssertJ Assertions

Located in `assertions/` package. Import the custom `assertThat` to use:

```kotlin
import com.rustyrazorblade.easydblab.assertions.assertThat

// AMI assertions
assertThat(ami).hasId("ami-123").hasName("easy-db-lab").isPublic()

// PruneResult assertions
assertThat(result).hasDeletedCount(3).hasKeptCount(2)
```

## Common Test Patterns

### ClusterState Setup
```kotlin
val testControlHost = ClusterHost(
    publicIp = "54.123.45.67", privateIp = "10.0.1.5",
    alias = "control0", availabilityZone = "us-west-2a", instanceId = "i-test123",
)
val testState = ClusterState(
    name = "test-cluster", versions = mutableMapOf(),
    hosts = mapOf(ServerType.Control to listOf(testControlHost)),
)
whenever(mockClusterStateManager.load()).thenReturn(testState)
```

### Exception Testing
```kotlin
assertThatThrownBy { command.execute() }
    .isInstanceOf(IllegalStateException::class.java)
    .hasMessageContaining("No control nodes found")
```

### Argument Capture
```kotlin
val captor = argumentCaptor<CreateRoleRequest>()
verify(mockIamClient).createRole(captor.capture())
assertThat(captor.firstValue.roleName()).isEqualTo("test-role")
```

## Directory Structure

```
src/test/kotlin/com/rustyrazorblade/easydblab/
├── BaseKoinTest.kt          # Core test base class
├── TestModules.kt           # Test module factory methods
├── TestPrompter.kt          # User input simulation
├── TestContextFactory.kt    # Test Context creation
├── assertions/              # Custom AssertJ assertions
├── commands/                # Command tests (mirrors main)
├── configuration/           # Configuration tests
├── kubernetes/              # K8s tests
├── mcp/                     # Server tests (MCP, REST, background services)
├── providers/aws/           # AWS provider tests
├── services/                # Service tests
└── ...
```

## K3s TestContainers Configuration

The `K8sServiceIntegrationTest` runs K3s inside Docker via TestContainers. This requires special container configuration to work in nested container environments (e.g., CI runners):

- **`withPrivilegedMode(true)`** — K3s needs full privileges to manage its own containers and networking.
- **`withCgroupnsMode("host")`** — The host uses cgroup v2. Without sharing the host cgroup namespace, K3s fails during "Node controller sync" because it can't properly manage cgroup hierarchies for its internal containers.
- **`withBinds(["/sys/fs/cgroup:/sys/fs/cgroup:rw"])`** — Explicitly mounts the cgroup filesystem read-write inside the K3s container. Required on Ubuntu 24.04 (cgroup v2 unified hierarchy) where K3s needs to write to cgroupfs to manage pod cgroups. Without this, K3s starts the Docker container but fails to write its kubeconfig (because cgroup initialization fails), causing all K8s API calls to fail with "server null".
- **`withEnv("K3S_SNAPSHOTTER", "native")`** — In nested container environments, the default overlayfs snapshotter can fail because the outer container's filesystem may already be an overlay. The native snapshotter avoids this by using simple file copies instead.
- **`withLogConsumer(Slf4jLogConsumer(...))`** — Forwards K3s container logs to SLF4J for debugging startup failures.
- **`withStartupTimeout(Duration.ofMinutes(3))`** — K3s can take longer than the default 60s to fully initialize in CI environments.

If K3s fails to start with `ContainerLaunchException` inside a nested container environment (e.g. CI), ensure the outer container is configured with `--privileged`, `--cgroupns=host`, and `/sys/fs/cgroup` mounted read-write.

## Test Data Quality

**Never assert specific content from real kit files in behaviour tests.** Kit YAML files evolve — commands get added, removed, or renamed. Tests that check `assertThat(output).contains("presto start")` or `assertThat(output).contains("clickhouse backup")` will break whenever a kit changes, even though the code under test is correct.

**Rule**: Tests that verify *rendering logic* (formatting, ordering, labels, annotations) must use synthetic data — a `KitConfig` constructed inline, a hand-crafted `Set<String>` of script names, a `List<Pair<String, String>>` of annotated commands. Only tests verifying that a specific kit's *metadata* (name, version, description, endpoints, args) is correctly parsed may read real kit files.

**Ordering assertions belong on the data, not the rendered output.** If a list should be sorted, assert on the `List<String>` of names directly (e.g. via `buildCommandList()`), not by searching for strings in a rendered block of text.

## Key Rules

- **Always** use AssertJ assertions (`assertThat`), not JUnit assertions
- **Always** extend `BaseKoinTest` for tests needing DI
- **Always** use `@TempDir` for temporary directories
- **Never mock `TemplateService`** — always use the real instance (`single { TemplateService(get(), get()) }`). It only reads classpath resources and does string substitution with no external side effects.
- **Never mock** any of the classes under `src/main/kotlin/com/rustyrazorblade/easydblab/configuration`
- Use `mockito-kotlin` for mocking (`mock<T>()`, `whenever()`, `verify()`)
- See [docs/development/testing.md](../../../../docs/development/testing.md) for the full testing guide
- Aim to mock as minimally as possible.  I want to hit real code in tests as much as possible.
- Prefer using LocalStack over mocking AWS services.  The more code that hits all the code paths the better.
- Test the K8 configurations with TestContainers, not mocking.
- **All K8s manifest builders MUST be tested in `K8sServiceIntegrationTest`** with K3s TestContainers. See `configuration/CLAUDE.md` for the full testing requirements (apply test, image pull test, no-resource-limits test).
