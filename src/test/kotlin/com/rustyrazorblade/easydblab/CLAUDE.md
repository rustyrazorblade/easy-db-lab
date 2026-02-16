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
├── mcp/                     # MCP server tests
├── providers/aws/           # AWS provider tests
├── services/                # Service tests
└── ...
```

## Key Rules

- **Always** use AssertJ assertions (`assertThat`), not JUnit assertions
- **Always** extend `BaseKoinTest` for tests needing DI
- **Always** use `@TempDir` for temporary directories
- Use `mockito-kotlin` for mocking (`mock<T>()`, `whenever()`, `verify()`)
- See [docs/development/testing.md](../../../../docs/development/testing.md) for the full testing guide
