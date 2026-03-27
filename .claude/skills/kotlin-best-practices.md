---
description: Kotlin best practices for easy-db-lab. Applied automatically when writing .kt files.
---

When writing or modifying Kotlin code, enforce these best practices:

## Type Safety

**Avoid nullable types (`?`).**
Nullable types propagate null-checks everywhere and cause `NullPointerException` at runtime. Design APIs to never return null. Use empty collections, sentinel objects, or `sealed` result types instead.

```kotlin
// Bad
fun findNode(name: String): Node? = ...

// Good
fun findNode(name: String): Node = nodes[name] ?: throw NodeNotFoundException(name)
// or return a sealed result:
sealed interface NodeResult { data class Found(val node: Node) : NodeResult; data object NotFound : NodeResult }
```

**Avoid `!!` (non-null assertion).** It crashes at runtime. If you're tempted to write `!!`, redesign the API so the value can't be null, or handle the absent case explicitly.

**Use proper types — let the compiler do the work.**
- Use `UUID` not `String` for identifiers
- Use `Duration` not `Long` for time values
- Use `Path` not `String` for filesystem paths
- Wrap primitives in value classes when the domain concept is distinct: `@JvmInline value class ClusterId(val value: String)`

## Immutability

**Prefer `val` over `var`.** If a value doesn't change, declare it immutable. Mutability should be the exception, not the default.

**Prefer read-only collections** (`List`, `Map`, `Set`) over mutable variants. Only use `MutableList`/`MutableMap`/`MutableSet` where mutation is required, and keep the mutable reference private.

```kotlin
// Bad
var nodes: MutableList<Node> = mutableListOf()

// Good
private val _nodes: MutableList<Node> = mutableListOf()
val nodes: List<Node> get() = _nodes
```

## Design Patterns

**Use `sealed class`/`sealed interface` for sum types.** Exhaustive `when` expressions catch missing cases at compile time.

```kotlin
sealed interface ClusterState {
    data object Starting : ClusterState
    data class Running(val nodeCount: Int) : ClusterState
    data class Failed(val reason: String) : ClusterState
}
```

**Use `data class` for value objects.** Gets `equals()`, `hashCode()`, `copy()`, and `toString()` for free.

**Use `require()`, `check()`, `error()` for preconditions** instead of throwing exceptions manually:
```kotlin
require(count > 0) { "count must be positive, was $count" }
check(isInitialized) { "must call initialize() before use" }
error("unreachable: unknown state $state")
```

**Prefer `when` expressions over `if-else` chains.** Exhaustiveness is enforced by the compiler for sealed types.

## Code Organization

**No wildcard imports.** Import each symbol explicitly.

**Prefer top-level functions over `companion object` utilities.** `companion object` is for factory methods tied to a class. Pure utility functions belong at the top level.

```kotlin
// Bad
class NodeUtils {
    companion object {
        fun formatName(name: String) = name.lowercase()
    }
}

// Good
fun formatNodeName(name: String) = name.lowercase()
```

**Use `by lazy` for expensive computed properties** that are only needed sometimes:
```kotlin
val heavyResource: HeavyResource by lazy { HeavyResource.create() }
```

**Use named arguments at call sites** when a function has multiple parameters of the same type, or when the meaning isn't obvious:
```kotlin
// Bad
createCluster("my-cluster", 3, true, false)

// Good
createCluster(name = "my-cluster", nodeCount = 3, withMonitoring = true, dryRun = false)
```

## Project-Specific Rules

- **Serialization:** Use `kotlinx.serialization`, not Jackson. Jackson is deprecated in this codebase.
- **Retry logic:** Use `resilience4j`, not custom retry loops. See `providers/CLAUDE.md` for `RetryUtil` factory methods.
- **Events:** Use domain-specific typed events via `eventBus.emit(Event.Domain.Type(...))`. Never use `Event.Message` or `Event.Error` in production code.
- **Logging:** Use `io.github.oshai.kotlinlogging.KotlinLogging` with `private val log = KotlinLogging.logger {}`.
- **K8s manifests:** Use Fabric8 builders, never build YAML strings. Never use raw YAML strings anywhere in Kotlin code.
