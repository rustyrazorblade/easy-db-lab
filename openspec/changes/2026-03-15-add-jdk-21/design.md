## Context

EC2 database nodes are built with a two-stage packer pipeline:

1. **Base image** (`packer/base/base.pkr.hcl`) — installs OS-level packages including all JDK versions. The current inline provisioner installs `openjdk-8-jdk openjdk-8-dbg openjdk-11-jdk openjdk-11-dbg openjdk-17-jdk openjdk-17-dbg` and sets JDK 11 as the default via `update-java-alternatives`.

2. **Cassandra image** (`packer/cassandra/cassandra.pkr.hcl`) — installs Cassandra versions defined in `cassandra_versions.yaml`. The `install_cassandra.sh` script hardcodes `update-java-alternatives -s java-1.11.0-openjdk-amd64` for the build phase. At runtime, the `use-cassandra` bin script reads the `java` field from `/etc/cassandra_versions.yaml` and switches the active JDK via `update-java-alternatives`.

The `use-cassandra` script currently handles `java` values `"8"`, `"11"`, and `"17"`. Any other value falls through to an error exit. The `cassandra_versions.yaml` `trunk` entry specifies `java: "17"` but trunk is built and CI-tested against JDK 21.

## Goals / Non-Goals

**Goals:**
- JDK 21 installed on all EC2 database nodes (base image)
- `use-cassandra` correctly switches to JDK 21 when `java: "21"` is specified
- `trunk` version runs on JDK 21 at runtime

**Non-Goals:**
- Changing the Cassandra build phase to use JDK 21 (the `install_cassandra.sh` build phase hardcodes JDK 11 for source builds; this is a separate concern)
- Adding JDK 21 support to ARM64 `update-java-alternatives` alternative names (the same `java-1.21.0-openjdk-$ARCH` pattern should work for both `amd64` and `arm64` on Ubuntu 24.04)
- Upgrading `5.0` or `4.x` versions to JDK 21 (they continue on their existing versions)

## Decisions

### 1. JDK 21 package names on Ubuntu 24.04

Ubuntu 24.04 (Noble) ships `openjdk-21-jdk` and `openjdk-21-dbg` in the default package repositories — no PPA required. The `update-java-alternatives` alternative name follows the same pattern as the others: `java-1.21.0-openjdk-amd64` / `java-1.21.0-openjdk-arm64`.

**Alternatives considered:**
- *Eclipse Temurin via Adoptium PPA* — adds an external dependency for something already available in Ubuntu's repos. Not worth the complexity.
- *SDKMAN on nodes* — overkill for server images; `update-java-alternatives` is already in use and works well.

### 2. `use-cassandra` branch for java 21

The existing code follows a simple if/elif chain. Adding `elif [ "$JAVA_VERSION" = "21" ]` with `sudo update-java-alternatives -s java-1.21.0-openjdk-$ARCH` is consistent with the pattern for 8, 11, and 17. No structural changes needed.

### 3. `cassandra_versions.yaml` — trunk only

Only `trunk` is changed to `java: "21"`. The `5.0` and `5.0-HEAD` entries stay on `java: "11"`:
- Cassandra 5.0's release documentation lists JDK 11 and 17 as supported (21 is compatible but not the default recommendation for 5.0 GA)
- Changing 5.0 to JDK 21 would be a user-visible behaviour change beyond the scope of this fix
- Users can override via `set-java-version 21` if they want to test 5.0 on JDK 21

## Risks / Trade-offs

- **[Image size]** Adding JDK 21 `jdk + dbg` packages adds ~300–400 MB to the base image. This is consistent with the existing multi-JDK strategy (8, 11, 17 already present) and acceptable for a lab tool.
- **[`update-java-alternatives` name]** If the alternative name for JDK 21 on Ubuntu 24.04 differs from the expected `java-1.21.0-openjdk-amd64` pattern, `use-cassandra` will fail. This can be validated with `testPackerBase` in CI before merging.
- **[ARM64 parity]** The base packer build runs for both `amd64` and `arm64`. Ubuntu 24.04 provides `openjdk-21-jdk` for both architectures, so no arch-specific handling is needed in the apt install step.

## Open Questions

None. This is a narrow, well-scoped change with no architectural decisions outstanding.
