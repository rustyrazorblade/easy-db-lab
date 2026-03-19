# Test Scope Detection Logic

This document explains how the e2e-test skill automatically determines what to test based on code changes.

## Detection Algorithm

### 1. Check for Explicit Arguments

If user provides explicit flags:
- `--cassandra` - Test Cassandra functionality
- `--clickhouse` - Test ClickHouse functionality
- `--opensearch` - Test OpenSearch functionality
- `--spark` - Test Spark/EMR functionality
- `--all` - Test everything
- `--build` - Build AMI image first

Use provided flags and skip detection.

### 2. Analyze Git Changes

If no flags provided, compare current branch against `main`:

```bash
# Get list of changed files
git diff --name-only main...HEAD

# Analyze each file path to determine affected subsystems
```

### 3. Subsystem Detection Rules

#### Cassandra

Enable `--cassandra` if any changed file matches:
- `src/main/kotlin/**/cassandra/**/*`
- `src/main/kotlin/**/commands/Cassandra*.kt`
- `src/main/kotlin/**/services/CassandraService.kt`
- `packer/cassandra/**/*`
- `src/main/resources/**/cassandra/**/*`
- `docs/reference/cassandra*.md`

Examples:
- ✅ `src/main/kotlin/com/rustyrazorblade/easydblab/commands/CassandraStart.kt`
- ✅ `packer/cassandra/install/install_cassandra.sh`
- ✅ `src/main/resources/com/rustyrazorblade/easydblab/commands/cassandra-config.yaml`

#### ClickHouse

Enable `--clickhouse` if any changed file matches:
- `src/main/kotlin/**/clickhouse/**/*`
- `src/main/kotlin/**/commands/Clickhouse*.kt`
- `src/main/kotlin/**/configuration/clickhouse/**/*`
- `src/main/resources/**/clickhouse/**/*`
- `docs/reference/clickhouse*.md`

Examples:
- ✅ `src/main/kotlin/com/rustyrazorblade/easydblab/commands/ClickhouseStart.kt`
- ✅ `src/main/kotlin/com/rustyrazorblade/easydblab/configuration/clickhouse/ClickhouseManifestBuilder.kt`

#### OpenSearch

Enable `--opensearch` if any changed file matches:
- `src/main/kotlin/**/opensearch/**/*`
- `src/main/kotlin/**/services/aws/OpenSearchService*.kt`
- `src/main/kotlin/**/commands/Opensearch*.kt`
- `docs/reference/opensearch*.md`

Examples:
- ✅ `src/main/kotlin/com/rustyrazorblade/easydblab/services/aws/OpenSearchService.kt`
- ✅ `src/main/kotlin/com/rustyrazorblade/easydblab/commands/OpensearchStart.kt`

#### Spark

Enable `--spark` if any changed file matches:
- `spark/**/*`
- `src/main/kotlin/**/spark/**/*`
- `src/main/kotlin/**/commands/Spark*.kt`
- `src/main/kotlin/**/services/aws/EmrService*.kt`
- `docs/reference/spark*.md`

Examples:
- ✅ `spark/connector-writer/src/main/kotlin/com/rustyrazorblade/easydblab/spark/StandardConnectorWriter.kt`
- ✅ `src/main/kotlin/com/rustyrazorblade/easydblab/commands/SparkSubmit.kt`
- ✅ `src/main/kotlin/com/rustyrazorblade/easydblab/services/aws/EmrService.kt`

Note: Spark testing requires `--cassandra` as well (Spark jobs write to Cassandra).

### 4. Core Infrastructure Detection

If changes affect core infrastructure, default to `--cassandra` (minimal test):

**Core infrastructure includes:**
- `src/main/kotlin/**/configuration/**/*` (K8s manifest builders)
- `src/main/kotlin/**/kubernetes/**/*` (K8s client)
- `src/main/kotlin/**/providers/**/*` (AWS SDK wrappers)
- `src/main/kotlin/**/commands/Init*.kt` (cluster initialization)
- `src/main/kotlin/**/commands/Up.kt` (cluster startup)
- `packer/base/**/*` (base provisioning scripts)
- `src/main/kotlin/**/mcp/**/*` (MCP server)
- Observability stack:
  - `src/main/kotlin/**/configuration/victoriametrics/**/*`
  - `src/main/kotlin/**/configuration/victorialogs/**/*`
  - `src/main/kotlin/**/configuration/grafana/**/*`
  - `src/main/kotlin/**/configuration/tempo/**/*`
  - `src/main/kotlin/**/configuration/pyroscope/**/*`

**Rationale:** Core infrastructure changes affect all databases, so test at least one database (Cassandra is fastest).

### 5. Special Cases

#### Build System Changes

If changes affect:
- `build.gradle.kts`
- `settings.gradle.kts`
- `gradle/**/*`
- `.github/workflows/**/*`

**Action:** Recommend `--all` to ensure build system changes don't break any subsystem.

#### Packer Changes

If changes affect:
- `packer/**/*`

**Action:** Recommend `--build` flag to rebuild AMI, plus appropriate database flags.

#### Documentation Only

If changes ONLY affect:
- `docs/**/*.md` (excluding reference docs)
- `README.md`
- `*.md` files (non-technical)

**Action:** Skip tests - documentation changes don't require e2e validation.

#### Test Changes

If changes ONLY affect:
- `src/test/**/*`
- `bin/end-to-end-test`

**Action:** Run unit tests (`./gradlew test`) but e2e tests optional.

### 6. Multiple Subsystems

If changes affect multiple subsystems:

**2-3 subsystems:** Enable specific flags
```bash
# Example: Cassandra + ClickHouse changes
bin/end-to-end-test --cassandra --clickhouse
```

**4+ subsystems:** Recommend `--all`
```bash
bin/end-to-end-test --all
```

### 7. No Changes Detected

If on `main` branch or no changes found:

**Default action:** Run minimal test with `--cassandra`

**Rationale:** User explicitly invoked e2e test, so run something. Cassandra test is fastest and covers core functionality.

## Detection Examples

### Example 1: Cassandra Config Change

**Changed files:**
```
src/main/kotlin/com/rustyrazorblade/easydblab/commands/CassandraUpdateConfig.kt
```

**Detection:**
- Matches Cassandra pattern
- Enable: `--cassandra`

**Command:**
```bash
bin/end-to-end-test --cassandra
```

---

### Example 2: ClickHouse Manifest Builder

**Changed files:**
```
src/main/kotlin/com/rustyrazorblade/easydblab/configuration/clickhouse/ClickhouseManifestBuilder.kt
```

**Detection:**
- Matches ClickHouse pattern
- Enable: `--clickhouse`

**Command:**
```bash
bin/end-to-end-test --clickhouse
```

---

### Example 3: Spark Job + Cassandra Config

**Changed files:**
```
spark/connector-writer/src/main/kotlin/StandardConnectorWriter.kt
src/main/kotlin/com/rustyrazorblade/easydblab/commands/CassandraStart.kt
```

**Detection:**
- Matches Spark pattern
- Matches Cassandra pattern
- Enable: `--spark --cassandra` (Spark requires Cassandra)

**Command:**
```bash
bin/end-to-end-test --spark --cassandra
```

---

### Example 4: Core Infrastructure (K8s)

**Changed files:**
```
src/main/kotlin/com/rustyrazorblade/easydblab/kubernetes/KubernetesClient.kt
```

**Detection:**
- Matches core infrastructure pattern
- Could affect any database
- Default to minimal test: `--cassandra`

**Command:**
```bash
bin/end-to-end-test --cassandra
```

**Recommendation:** "Core infrastructure changed. Testing with Cassandra (fastest). Consider `--all` for comprehensive validation."

---

### Example 5: Observability Stack

**Changed files:**
```
src/main/kotlin/com/rustyrazorblade/easydblab/configuration/victoriametrics/VictoriaMetricsManifestBuilder.kt
```

**Detection:**
- Matches observability pattern (core infrastructure)
- Observability runs with every test
- Default to minimal: `--cassandra`

**Command:**
```bash
bin/end-to-end-test --cassandra
```

**Note:** Observability stack is deployed and tested regardless of flags.

---

### Example 6: Multiple Databases

**Changed files:**
```
src/main/kotlin/com/rustyrazorblade/easydblab/commands/CassandraStart.kt
src/main/kotlin/com/rustyrazorblade/easydblab/commands/ClickhouseStart.kt
src/main/kotlin/com/rustyrazorblade/easydblab/commands/OpensearchStart.kt
```

**Detection:**
- Matches 3+ subsystems
- Recommend: `--all`

**Command:**
```bash
bin/end-to-end-test --all
```

**Rationale:** Testing all subsystems is more efficient than multiple separate runs.

---

### Example 7: Packer Base Scripts

**Changed files:**
```
packer/base/install/install_common_tools.sh
```

**Detection:**
- Matches packer base pattern
- Affects all node provisioning
- Recommend: `--build --cassandra` (or `--build --all`)

**Command:**
```bash
bin/end-to-end-test --build --cassandra
```

**Note:** `--build` adds 30-45 minutes but is necessary to test packer changes.

---

### Example 8: Documentation Only

**Changed files:**
```
docs/user-guide/getting-started.md
README.md
```

**Detection:**
- Only documentation files
- No code changes

**Action:** Skip e2e tests

**Message:** "Only documentation files changed. E2E tests not required. Run unit tests if desired: `./gradlew test`"

---

## Implementation Guide

### Bash Detection Script

```bash
detect_test_scope() {
    local cassandra=false
    local clickhouse=false
    local opensearch=false
    local spark=false
    local build=false

    # Get changed files
    local changed_files=$(git diff --name-only main...HEAD 2>/dev/null)

    if [ -z "$changed_files" ]; then
        echo "No changes detected - defaulting to --cassandra"
        echo "--cassandra"
        return
    fi

    # Check each file
    while IFS= read -r file; do
        case "$file" in
            *cassandra*|*Cassandra*)
                cassandra=true
                ;;
            *clickhouse*|*Clickhouse*|*ClickHouse*)
                clickhouse=true
                ;;
            *opensearch*|*OpenSearch*)
                opensearch=true
                ;;
            *spark*|*Spark*|*emr*|*EMR*)
                spark=true
                ;;
            packer/*)
                build=true
                # Also check which database
                case "$file" in
                    packer/cassandra/*) cassandra=true ;;
                    packer/clickhouse/*) clickhouse=true ;;
                esac
                ;;
            src/main/kotlin/**/configuration/*|src/main/kotlin/**/kubernetes/*|src/main/kotlin/**/providers/*)
                # Core infrastructure - default to cassandra
                if [ "$cassandra" = false ] && [ "$clickhouse" = false ] && [ "$opensearch" = false ]; then
                    cassandra=true
                fi
                ;;
        esac
    done <<< "$changed_files"

    # Build flags
    local flags=""
    [ "$build" = true ] && flags="$flags --build"
    [ "$spark" = true ] && flags="$flags --spark"
    [ "$cassandra" = true ] && flags="$flags --cassandra"
    [ "$clickhouse" = true ] && flags="$flags --clickhouse"
    [ "$opensearch" = true ] && flags="$flags --opensearch"

    # If no flags set, default to cassandra
    if [ -z "$flags" ]; then
        flags="--cassandra"
    fi

    echo "$flags"
}
```

## Testing the Detection

To test the detection logic:

```bash
# Check what would be detected for current branch
git diff --name-only main...HEAD

# Manually verify against rules above
```

## Overriding Detection

Users can always override detection:

```bash
# Force specific scope
/e2e-test --clickhouse

# Force full test
/e2e-test --all

# Disable detection and use minimal
/e2e-test --cassandra
```

## Future Enhancements

1. **Machine learning** - Learn from past test results to improve detection
2. **Dependency analysis** - Parse imports to detect transitive dependencies
3. **Change impact analysis** - Use call graphs to determine affected code
4. **Test history** - Track which changes caused failures in the past
5. **Smart batching** - Combine multiple small changes into optimal test runs
