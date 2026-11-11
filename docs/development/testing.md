# Testing Guidelines

This document outlines the testing standards and practices for the easy-db-lab project.

## Core Testing Principles

### 1. Use BaseKoinTest for Dependency Injection

All tests should extend `BaseKoinTest` to take advantage of automatic dependency injection setup and teardown.

```kotlin
class MyCommandTest : BaseKoinTest() {
    // Your test code here
}
```

BaseKoinTest provides:
- Automatic Koin lifecycle management
- Core modules that are always mocked (AWS, SSH, OutputHandler)
- Ability to add test-specific modules via `additionalTestModules()`

### 2. Use AssertJ for Assertions

Tests should use AssertJ assertions, not JUnit assertions. AssertJ provides more readable and powerful assertion methods.

```kotlin
// Good - AssertJ style
import org.assertj.core.api.Assertions.assertThat

assertThat(result).isNotNull()
assertThat(result.value).isEqualTo("expected")
assertThat(list).hasSize(3).contains("item1", "item2")

// Avoid - JUnit style
import org.junit.jupiter.api.Assertions.assertEquals

assertEquals("expected", result.value)
```

### 3. Create Custom Assertions for Non-Trivial Classes

When testing non-trivial classes, create custom AssertJ assertions to implement Domain-Driven Design in tests. This decouples business logic from implementation details and makes tests more maintainable during refactoring.

## Custom Assertions Pattern

Custom assertions provide a fluent, domain-specific language for testing that improves readability and maintainability.

### Example: Custom Assertion for a Domain Class

Here's a complete example showing how to create and use custom assertions:

```kotlin
// Domain class to be tested
data class CassandraNode(
    val nodeId: String,
    val datacenter: String,
    val rack: String,
    val status: NodeStatus,
    val tokens: Int
)

enum class NodeStatus {
    UP, DOWN, JOINING, LEAVING
}

// Custom assertion class
import org.assertj.core.api.AbstractAssert

class CassandraNodeAssert(actual: CassandraNode?) :
    AbstractAssert<CassandraNodeAssert, CassandraNode>(actual, CassandraNodeAssert::class.java) {

    companion object {
        fun assertThat(actual: CassandraNode?): CassandraNodeAssert {
            return CassandraNodeAssert(actual)
        }
    }

    fun hasNodeId(nodeId: String): CassandraNodeAssert {
        isNotNull
        if (actual.nodeId != nodeId) {
            failWithMessage("Expected node ID to be <%s> but was <%s>", nodeId, actual.nodeId)
        }
        return this
    }

    fun isInDatacenter(datacenter: String): CassandraNodeAssert {
        isNotNull
        if (actual.datacenter != datacenter) {
            failWithMessage("Expected datacenter to be <%s> but was <%s>", datacenter, actual.datacenter)
        }
        return this
    }

    fun hasStatus(status: NodeStatus): CassandraNodeAssert {
        isNotNull
        if (actual.status != status) {
            failWithMessage("Expected status to be <%s> but was <%s>", status, actual.status)
        }
        return this
    }

    fun isUp(): CassandraNodeAssert {
        return hasStatus(NodeStatus.UP)
    }

    fun isDown(): CassandraNodeAssert {
        return hasStatus(NodeStatus.DOWN)
    }

    fun hasTokenCount(tokens: Int): CassandraNodeAssert {
        isNotNull
        if (actual.tokens != tokens) {
            failWithMessage("Expected token count to be <%s> but was <%s>", tokens, actual.tokens)
        }
        return this
    }
}

// Usage in tests
import CassandraNodeAssert.Companion.assertThat

@Test
fun `test cassandra node configuration`() {
    val node = CassandraNode(
        nodeId = "node1",
        datacenter = "dc1",
        rack = "rack1",
        status = NodeStatus.UP,
        tokens = 256
    )

    // Fluent assertions with domain language
    assertThat(node)
        .hasNodeId("node1")
        .isInDatacenter("dc1")
        .isUp()
        .hasTokenCount(256)
}
```

### Project-Wide Assertions Helper

Create a central assertions class to provide access to all custom assertions:

```kotlin
// MyProjectAssertions.kt
object MyProjectAssertions {

    // Cassandra domain assertions
    fun assertThat(actual: CassandraNode?): CassandraNodeAssert {
        return CassandraNodeAssert(actual)
    }

    fun assertThat(actual: Host?): HostAssert {
        return HostAssert(actual)
    }

    fun assertThat(actual: TFState?): TFStateAssert {
        return TFStateAssert(actual)
    }

    // Add more domain assertions as needed
}
```

Then import statically in tests:

```kotlin
import com.rustyrazorblade.easydblab.assertions.MyProjectAssertions.assertThat

@Test
fun `test complex scenario`() {
    val node = createTestNode()
    val host = createTestHost()

    // All domain assertions available through single import
    assertThat(node).isUp()
    assertThat(host).hasPrivateIp("10.0.0.1")
}
```

## Benefits of Custom Assertions

1. **Domain-Driven Design**: Tests use business language, not implementation details
2. **Refactoring Safety**: Changes to class internals don't break test logic
3. **Readability**: Tests read like specifications
4. **Reusability**: Common assertions are centralized
5. **Maintainability**: Single place to update assertion logic
6. **Type Safety**: Compile-time checking of assertion methods

## When to Create Custom Assertions

Create custom assertions for:
- Domain entities (e.g., `Host`, `TFState`, `CassandraNode`)
- Complex value objects with multiple properties
- Classes that appear in multiple test scenarios
- Any class where you find yourself writing repetitive assertion code

## Testing Best Practices

1. **Test Names**: Use descriptive names with backticks
   ```kotlin
   @Test
   fun `should start cassandra node when status is DOWN`() { }
   ```

2. **Test Structure**: Follow Arrange-Act-Assert pattern
   ```kotlin
   @Test
   fun `test node startup`() {
       // Arrange
       val node = createTestNode(status = NodeStatus.DOWN)

       // Act
       val result = nodeManager.startNode(node)

       // Assert
       assertThat(result).isUp()
   }
   ```

3. **Mock External Dependencies**: Always mock AWS, SSH, and other external services
   ```kotlin
   class MyTest : BaseKoinTest() {
       override fun additionalTestModules() = listOf(
           module {
               single { mockRemoteOperationsService() }
           }
       )
   }
   ```

4. **Test Edge Cases**: Include tests for error conditions and boundary cases

5. **Keep Tests Focused**: Each test should verify one specific behavior

## Testing Interactive Commands with TestPrompter

Commands that require user input (like `profile setup`) can be tested deterministically using `TestPrompter`. This test utility replaces the real `Prompter` interface and returns predefined responses.

### Basic Usage

```kotlin
class MyCommandTest : BaseKoinTest() {
    private lateinit var testPrompter: TestPrompter

    override fun additionalTestModules() = listOf(
        module {
            single<Prompter> { testPrompter }
        }
    )

    @BeforeEach
    fun setup() {
        // Configure responses - keys can be exact matches or partial matches
        testPrompter = TestPrompter(
            mapOf(
                "email" to "test@example.com",
                "region" to "us-west-2",
                "AWS Access Key" to "AKIAIOSFODNN7EXAMPLE",
            )
        )
    }

    @Test
    fun `should collect user credentials`() {
        // Run command that prompts for input
        val command = SetupProfile()
        command.call()

        // Verify prompts were called
        assertThat(testPrompter.wasPromptedFor("email")).isTrue()
        assertThat(testPrompter.wasPromptedFor("region")).isTrue()
    }
}
```

### Response Matching

TestPrompter supports two matching strategies:

1. **Exact match**: The question text matches a key exactly
2. **Partial match**: The question text contains the key (case-insensitive)

```kotlin
val prompter = TestPrompter(
    mapOf(
        // Exact match - only matches "email" exactly
        "email" to "test@example.com",

        // Partial match - matches any question containing "AWS Profile"
        "AWS Profile" to "my-profile",
    )
)
```

### Sequential Responses for Retry Testing

For testing retry logic (e.g., credential validation failures), use `addSequentialResponses()`:

```kotlin
@Test
fun `should retry on invalid credentials`() {
    testPrompter = TestPrompter()

    // First call returns invalid credentials, second returns valid ones
    testPrompter.addSequentialResponses(
        "AWS Access Key",
        "invalid-key",      // First attempt
        "AKIAVALIDKEY123"   // Second attempt (after retry)
    )

    testPrompter.addSequentialResponses(
        "AWS Secret",
        "invalid-secret",
        "valid-secret-key"
    )

    val command = SetupProfile()
    command.call()

    // Verify the command handled retry correctly
    val callLog = testPrompter.getCallLog()
    val accessKeyCalls = callLog.filter { it.question.contains("Access Key") }
    assertThat(accessKeyCalls).hasSize(2)
}
```

### Verifying Prompt Behavior

TestPrompter records all prompt calls for verification:

```kotlin
@Test
fun `should not prompt for credentials when using AWS profile`() {
    testPrompter = TestPrompter(
        mapOf(
            "AWS Profile" to "my-profile",  // Non-empty = use profile auth
        )
    )

    val command = SetupProfile()
    command.call()

    // Verify credential prompts were skipped
    assertThat(testPrompter.wasPromptedFor("Access Key")).isFalse()
    assertThat(testPrompter.wasPromptedFor("Secret")).isFalse()

    // Check detailed call log
    val callLog = testPrompter.getCallLog()
    assertThat(callLog).anyMatch { it.question.contains("email") }
}
```

### TestPrompter API Reference

| Method | Description |
|--------|-------------|
| `prompt(question, default, secret)` | Returns configured response or default |
| `addSequentialResponses(key, vararg responses)` | Configure different responses for retry scenarios |
| `getCallLog()` | Returns list of all prompt calls with details |
| `wasPromptedFor(questionContains)` | Check if any prompt contained the given text |
| `clear()` | Reset call log and sequential state |

### PromptCall Data Class

Each recorded call contains:
- `question`: The prompt question text
- `default`: The default value offered
- `secret`: Whether input was masked (for passwords)
- `returnedValue`: The value that was returned

## Additional Resources

- [AssertJ Documentation](https://assertj.github.io/doc/)
- [AssertJ Custom Assertions Guide](https://assertj.github.io/doc/#assertj-core-custom-assertions)
- [Koin Testing Documentation](https://insert-koin.io/docs/reference/koin-test/testing)
