# Easy-DB-Lab Expert Skill

A comprehensive knowledge agent for easy-db-lab architecture, features, commands, and best practices.

## Purpose

This skill provides expert guidance on all aspects of easy-db-lab without executing commands or modifying code. It's a Q&A agent that draws from documentation, code, and best practices to answer your questions.

## When to Use

Use this skill when you need to:
- ✅ Understand how easy-db-lab works
- ✅ Learn about available features
- ✅ Get command usage help
- ✅ Understand architecture and design patterns
- ✅ Learn best practices
- ✅ Find where functionality is implemented

## When NOT to Use

Don't use this skill when you need to:
- ❌ Debug an active cluster (use `/debug-environment`)
- ❌ Run end-to-end tests (use `/e2e-test`)
- ❌ Execute commands or scripts
- ❌ Modify code or configuration

## Expertise Areas

### Architecture
- Project structure and organization
- Layered architecture (Commands → Services → Providers)
- Event-driven output system
- Dependency injection with Koin
- Module organization

### Databases
- Cassandra (systemd-based deployment)
- ClickHouse (K8s StatefulSet)
- OpenSearch (AWS managed service)
- Adding new database types

### Commands
- All CLI commands and their usage
- Command implementation patterns
- PicoCLI integration
- Output handling

### Configuration
- K8s manifest builders (Fabric8)
- Template system
- Environment customization
- Configuration layers

### Observability
- VictoriaMetrics (metrics storage)
- VictoriaLogs (log aggregation)
- Grafana (visualization)
- Tempo (traces)
- Pyroscope (profiling)
- Collector setup (OTel, Fluent Bit, etc.)

### AWS Integration
- EC2 instance management
- EMR for Spark
- OpenSearch domains
- S3 for storage and backups
- IAM policies

### Development
- How to add new commands
- Service patterns
- Testing guidelines (TDD, BaseKoinTest)
- Code quality tools (detekt, ktlint)

## Usage Examples

### Example 1: Architecture Question

```
/easy-db-lab-expert

How does the event bus work for user output?
```

**Response:**
Explains the event-driven output system, shows Event.kt structure, describes the 28 domain interfaces, demonstrates event emission patterns, and references specific files.

### Example 2: Feature Question

```
/easy-db-lab-expert

Does easy-db-lab support EBS volumes for Cassandra?
```

**Response:**
Explains EBS support via --ebs flag, shows how it's configured in InitCommand, compares EBS vs instance store performance, and provides usage example.

### Example 3: Command Usage

```
/easy-db-lab-expert

How do I query logs from VictoriaLogs?
```

**Response:**
Shows `logs query` command syntax, explains filtering options, demonstrates time range queries, shows output format, and references implementation.

### Example 4: Development Question

```
/easy-db-lab-expert

What's the pattern for adding a new database type?
```

**Response:**
Outlines steps: create ServerType enum entry, add commands package, implement service layer, create manifest builders (if K8s), add events, write tests. Provides references to existing database implementations.

### Example 5: Configuration Question

```
/easy-db-lab-expert

Where are Grafana dashboards defined?
```

**Response:**
Explains GrafanaDashboardManifestBuilder, shows dashboard JSON location, describes template substitution mechanism, demonstrates how to add new dashboards, references configuration/grafana/CLAUDE.md.

## Knowledge Sources

The skill draws from:

### Primary Documentation
- `/Users/jhaddad/dev/easy-db-lab/CLAUDE.md` - Main project documentation
- Subdirectory CLAUDE.md files - Component-specific patterns
- `docs/` - User-facing documentation
- `openspec/specs/` - Feature specifications

### Code
- `src/main/kotlin/` - All implementation
- `src/main/resources/` - Templates and configs
- `src/test/kotlin/` - Test examples
- `packer/` - Provisioning scripts
- `spark/` - Spark modules

### Scripts
- `bin/` - Utility scripts
- `bin/end-to-end-test` - E2E test reference

## Key Concepts

### General-Purpose Tool

Easy-db-lab is **NOT** Cassandra-specific - it's a general database testing platform supporting multiple database systems.

### Layered Architecture

```
┌─────────────────────────┐
│  Commands (PicoCLI)     │  CLI entry points
├─────────────────────────┤
│  Services               │  Business logic
├─────────────────────────┤
│  Providers              │  AWS, SSH, Docker wrappers
├─────────────────────────┤
│  External Systems       │  K8s, AWS APIs, filesystem
└─────────────────────────┘
```

### Event-Driven Output

All user output flows through EventBus:
- Domain-specific events (28 interfaces)
- Multiple listeners (Console, MCP, Redis)
- No direct print statements
- Structured, typed output

### Fabric8 Manifest Builders

K8s resources built in Kotlin:
- Type-safe (compile-time checks)
- No raw YAML strings
- Testable and refactorable
- Located in `configuration/` packages

## Related Skills

- **`/debug-environment`** - Active cluster debugging and diagnostics
- **`/e2e-test`** - Run end-to-end test suite
- **`/k8-expert`** - Kubernetes-specific expertise
- **`/e2e-test-expert`** - End-to-end testing expertise

## Invocation

```bash
/easy-db-lab-expert
```

Then ask your question in natural language.

## Tips

1. **Be specific:** "How does ClickHouse storage work?" vs "Tell me about storage"
2. **Ask for examples:** "Show me how to..." often gets code examples
3. **Reference components:** "Explain the VictoriaMetrics manifest builder"
4. **Ask about patterns:** "What's the pattern for..."
5. **Request comparisons:** "What's the difference between..."

## Limitations

This is a **knowledge agent**, not an executor:
- Cannot run commands
- Cannot modify files
- Cannot debug active systems
- Cannot execute tests

For execution tasks, use the appropriate executor skills (`/debug-environment`, `/e2e-test`).

---

**Maintained by:** easy-db-lab project
**Version:** 1.0.0
**Type:** Knowledge Agent (Q&A)
