- This is a command line tool.  The user interacts by reading the output.  Do not suggest replacing print statements with logging, because it breaks the UX.
- Commands should use `outputHandler.handleMessage()` for user-facing output, not logging frameworks.
- Do not add logging frameworks to command classes unless there is a specific internal debugging need separate from user output.
- When logging is needed, use: `import io.github.oshai.kotlinlogging.KotlinLogging` and create a logger with `private val log = KotlinLogging.logger {}`

## Project Organization

### Architecture Overview

The project follows a layered architecture with clear separation of concerns:

```
Commands (PicoCLI) → Services → External Systems (K8s, AWS, Filesystem)
```

### Layer Responsibilities

**Commands (`commands/`)**: Lightweight PicoCLI execution units. Commands are thin wrappers that:
- Parse CLI arguments and options
- Delegate to service layers for actual work
- Handle user-facing output via `outputHandler`
- Should contain minimal business logic

**Services (`services/`, `providers/`)**: Business logic layer that:
- Interacts with Kubernetes clusters
- Calls cloud provider APIs (AWS EC2, S3, IAM, etc.)
- Manages filesystem operations
- Contains the actual implementation logic

### Dependency Injection

Use **Koin** for dependency injection throughout the codebase:
- Services are registered in Koin modules
- Commands receive dependencies via Koin injection
- Tests use `BaseKoinTest` which provides mocked dependencies

### Design Principles

Follow **SOLID principles**:
- **S**ingle Responsibility: Each class has one reason to change
- **O**pen/Closed: Open for extension, closed for modification
- **L**iskov Substitution: Subtypes must be substitutable for base types
- **I**nterface Segregation: Prefer small, focused interfaces
- **D**ependency Inversion: Depend on abstractions, not concretions

### Testing Approach

Practice **reasonable TDD**:
- Write tests for non-trivial code with meaningful behavior
- Skip trivial tests on simple configs, small wrappers, and data classes
- Review tests after writing to evaluate test quality
- Focus on testing behavior, not implementation details

**Quality tools workflow**:
```bash
# Find test coverage gaps
./gradlew koverHtmlReport
# Report at build/reports/kover/html/index.html

# Format code before committing
./gradlew ktlintFormat

# Find potential code issues
./gradlew detekt
```

## Development Setup

### Java Version Management (SDKMAN)

The devcontainer uses SDKMAN to manage Java versions:
- **Java 21** (Temurin) - Default for the main project
- **Java 11** (Temurin) - Required for building cassandra-analytics

SDKMAN is pre-configured in the devcontainer with both versions installed and Java 21 as the default.

**Why two versions?** The `bulk-writer` module depends on `cassandra-analytics` which requires JDK 11 to build. The `bin/build-cassandra-analytics` script automatically switches to JDK 11 for that build.

**Common commands:**
```bash
# Check current Java version
java -version

# List installed versions
sdk list java

# Temporarily use a different version (current shell only)
sdk use java 11.0.25-tem

# Switch default version permanently
sdk default java 21.0.5-tem
```

### Building Cassandra Analytics Dependencies

The `bulk-writer` module depends on cassandra-analytics SNAPSHOT artifacts that aren't published to Maven Central. Run `bin/dev test` and they will be built automatically if missing. To manually build or rebuild:

```bash
# Build cassandra-analytics (auto-skips if already built)
bin/dev build-analytics

# Force rebuild
bin/dev build-analytics --force
```

This clones the cassandra-analytics repo, builds with JDK 11, and publishes artifacts to the local Maven repository (`~/.m2/repository`).

### Pre-commit Hook Installation

Install the ktlint pre-commit hook to automatically check code style before commits:

```bash
./gradlew addKtlintCheckGitPreCommitHook
```

**Important**: Pre-commit hooks are stored in `.git/hooks/` which is local-only and not tracked by Git. Each developer must run this command individually to install the hook on their machine.

The hook automatically runs `ktlintCheck` on staged Kotlin files before each commit, preventing style violations from being committed.

### Configuration Cache

The project uses Gradle configuration cache for faster builds, enabled via `gradle.properties`:
- `org.gradle.configuration-cache=true` - Enables configuration caching
- `org.gradle.caching=true` - Enables build caching

**When to clear the cache**:
- After modifying `.editorconfig` or ktlint rules
- After changing Gradle plugins or build scripts
- When encountering unexpected build behavior

```bash
# Clear configuration cache
rm -rf .gradle/configuration-cache

# Or clean everything
./gradlew clean
```

**Why this matters**: If you modify `.editorconfig` (which configures ktlint rules), the configuration cache may prevent ktlint from seeing the new rules. This can cause local builds to pass while CI fails with style violations.

### Local Validation

Before pushing code, verify it passes all checks:

```bash
# Run all checks (matches CI)
./gradlew check

# Run only ktlint check (verify style compliance)
./gradlew ktlintCheck

# Auto-fix ktlint violations (when possible)
./gradlew ktlintFormat
```

**Note**: `ktlintFormat` auto-fixes many violations but can't fix all issues (e.g., line length). Always run `ktlintCheck` after formatting to catch remaining issues.

### Packer Script Testing

Test packer provisioning scripts locally using Docker (no AWS required):

```bash
# Test base provisioning scripts
./gradlew testPackerBase

# Test Cassandra provisioning scripts
./gradlew testPackerCassandra

# Run all packer tests
./gradlew testPacker

# Test a specific script
./gradlew testPackerScript -Pscript=cassandra/install/install_cassandra_easy_stress.sh
```

For more details, see [packer/README.md](packer/README.md) and [packer/TESTING.md](packer/TESTING.md).

## Development Rules

- All tests should pass before committing.
- Always add tests to new non-trivial code.
- If this document needs to be updated in order to provide more context for future work, do it.
- Do not use remote docker-compose commands, use docker compose, the subcommand version.
- Check if the codebase already has a way of accomplishing something before writing new code.  For example, there's already Docker logic.
- ABSOLUTE RULE: Never try to commit without explicit instruction to do so.
- activate kotlin and java for context7
- activate the serena MCP server
- ABSOLUTE RULE: NEVER attribute commit messages to Claude.
- ABSOLUTE RULE: When posting a plan to github, do not include 'Test plan' section.
- Do not use wildcard imports.
- When making changes, use the detekt plugin to determine if there are any code quality regressions.
- Always ensure files end with a newline
- Tests should extend BaseKoinTest to use Koin DI
- Use resilience4j for retry logic instead of custom retry loops
- Use AssertJ assertions, not JUnit assertions
- For serialization, use kotlinx.serialization, not Jackson.  Jackson usage in this codebase is deprecated.
- Constants and magic numbers should be stored in com.rustyrazorblade.easydblab.Constants
- When migrating code, it is not necessary to maintain backwards compatibility
- Fail fast is usually preferred
- Always use @TempDir for temporary directories in tests - JUnit handles lifecycle automatically
- **Never disable functionality as a solution.** If something isn't working, fix the root cause. Adding flags to skip features, making things optional, or suggesting users disable components is not an acceptable solution.
- **Configuration problems require configuration fixes.** If a service can't connect to a dependency, the fix is to provide the correct endpoint/credentials, not to make the dependency optional.
- Include testing when planning.  Integration tests use TestContainers.
- When planning, iterate with me.  Ask questions.  Don't automatically add features I didn't ask for.  Ask if I want them first.
- Include updates to the documentation as part of planning.
- CRITICAL: Tests must pass, in CI, on my local, and in the devcontainer.  It is UNACCEPTABLE to say that tests are only failing in devcontainers and to ignore them.

### Retry Logic with Resilience4j

The project uses resilience4j for all retry operations. Never implement custom retry loops.

**Always use `RetryUtil` factory methods** instead of creating manual configurations:

```kotlin
import com.rustyrazorblade.easydblab.providers.RetryUtil
import io.github.resilience4j.retry.Retry

// For operations that return a value (Supplier pattern)
val retryConfig = RetryUtil.createAwsRetryConfig<List<AMI>>()
val retry = Retry.of("ec2-list-amis", retryConfig)
val result = Retry.decorateSupplier(retry) {
    ec2Client.describeImages(request).images()
}.get()

// For operations with no return value (Runnable pattern)
val retryConfig = RetryUtil.createDockerRetryConfig<Unit>()
val retry = Retry.of("docker-start-container", retryConfig)
Retry.decorateRunnable(retry) {
    dockerClient.startContainer(containerId)
}.run()
```

**Available factory methods** (in `RetryUtil.kt`):

| Method | Attempts | Backoff | Use Case |
|--------|----------|---------|----------|
| `createIAMRetryConfig()` | 5 | Exponential 1s-16s | IAM operations, handles 404 eventual consistency |
| `createEC2InstanceRetryConfig<T>()` | 5 | Exponential 1s-16s | EC2 instance operations, handles "does not exist" eventual consistency |
| `createAwsRetryConfig<T>()` | 3 | Exponential 1s-4s | S3, EC2, EMR, and other AWS services |
| `createDockerRetryConfig<T>()` | 3 | Exponential 1s-4s | Container start/stop/remove |
| `createNetworkRetryConfig<T>()` | 3 | Exponential 1s-4s | Generic network operations |
| `createSshConnectionRetryConfig()` | 30 | Fixed 10s | SSH boot-up waiting (~5 min) |
| `createS3LogRetrievalRetryConfig<T>()` | 10 | Fixed 3s | S3 log retrieval, handles 404 for eventual consistency |

See `EC2Service.kt`, `EC2InstanceService.kt`, `S3ObjectStore.kt`, and `Up.kt` for production examples.

## Testing Guidelines

For comprehensive testing guidelines, including custom assertions and Domain-Driven Design patterns, see [docs/TESTING.md](docs/TESTING.md).

### Key Principles
- Extend `BaseKoinTest` for all tests (provides automatic DI and mocked services)
- Use AssertJ assertions, not JUnit assertions
- Create custom assertions for domain classes to enable Domain-Driven Design
- AWS, SSH, and OutputHandler are automatically mocked by BaseKoinTest

### Quick Example: Mocking AWS Services
When writing tests that interact with AWS services, always use mocked clients to prevent real API calls:

```kotlin
// Create mock clients
val mockIamClient = mock<IamClient>()
val mockClients = mock<Clients>()
whenever(mockClients.iam).thenReturn(mockIamClient)

// Setup mock responses
whenever(mockIamClient.createRole(any())).thenReturn(mockResponse)

// Test with mocked clients
val aws = AWS(mockClients)
```

The project uses `mockito-kotlin` for mocking. See `AWSTest.kt` for complete examples.

## Cluster State Management

### Overview
The codebase manages cluster state through the `ClusterState` class, which tracks host information, infrastructure resources, and configuration. State is persisted to a local `state.json` file and managed by `ClusterStateManager`.

### Key Components

#### 1. ClusterHost Data Class (`configuration/ClusterState.kt`)
Represents a single host in the cluster:
- `publicIp`: Public IP address
- `privateIp`: Private/internal IP address
- `alias`: Host alias (e.g., "db0", "stress0", "control0")
- `availabilityZone`: AWS availability zone
- `instanceId`: EC2 instance ID for lifecycle management

#### 2. ServerType Enum (`configuration/ServerType.kt`)
Defines three server types:
- `Cassandra`: Cassandra database nodes (alias prefix: "db")
- `Stress`: Stress testing nodes (alias prefix: "app")
- `Control`: Control/monitoring nodes (alias prefix: "control")

#### 3. ClusterState Class (`configuration/ClusterState.kt`)
Central state object containing:
- `hosts`: Map of ServerType to list of ClusterHost instances
- `clusterId`: Unique identifier for EC2 tag-based discovery
- `infrastructure`: VPC, subnet, security group IDs for cleanup
- `initConfig`: Configuration from `Init` command
- `emrCluster`: Optional EMR cluster state for Spark jobs
- `openSearchDomain`: Optional OpenSearch domain state

#### 4. ClusterStateManager (`configuration/ClusterStateManager.kt`)
Handles persistence of ClusterState:
- `load()`: Read state from `state.json`
- `save(state)`: Write state to `state.json`
- `updateHosts(hosts)`: Load, update hosts, and save atomically

#### 5. Commands (`commands/*`)

PicoCLI subcommands. Most run then exit. There are two exceptions:

- Repl: Starts a REPL to reduce typing
- Server: Starts an MCP server for AI Agents (run via `easy-db-lab server`).

### Common Patterns

#### Getting Host IPs
```kotlin
// Get the internal IP of the first Cassandra node
val cassandraHosts = clusterState.hosts[ServerType.Cassandra] ?: emptyList()
val firstCassandraIp = cassandraHosts.first().privateIp

// Get all Cassandra hosts
val allCassandraHosts = clusterState.hosts[ServerType.Cassandra] ?: emptyList()

// Iterate over control nodes
clusterState.hosts[ServerType.Control]?.forEach { host ->
    // host.publicIp - public IP
    // host.privateIp - internal IP
    // host.alias - hostname alias
    // host.instanceId - EC2 instance ID
}

// Get the first control host (convenience method)
val controlHost = clusterState.getControlHost()
```

#### Docker Compose Templating
The `docker-compose.yaml` file is:
1. Initially extracted from resources with placeholder values (e.g., `db0`)
2. Updated in `Up.kt::uploadDockerComposeToControlNodes()` to replace placeholders with actual IPs
3. Uploaded to control nodes with real IP addresses

This ensures services on control nodes can connect to Cassandra nodes using their internal IPs.


## Open Telemetry

Cassandra and control nodes are set up with OpenTelemetry.

Local OTel nodes are forwarding metrics to the control node.

## Documentation

User documentation is in `docs/` (MkDocs format).  When making user facing changes, make sure the docs for that feature are up to date.

If I refer to Kubernetes configs or k8 configs, I am referring to these: `src/main/resources/com/rustyrazorblade/easydblab/commands/k8s/` by default.
