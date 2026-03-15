## Why

EC2 database nodes currently ship with OpenJDK 8, 11, and 17. Cassandra trunk (the development branch of Cassandra 5.x+) is built and tested against JDK 21. Running trunk on JDK 17 misses JDK 21 runtime characteristics — GC tuning, virtual threads, and JVM flag behavior can differ enough to invalidate test results.

Beyond trunk, Cassandra 5.0 is officially compatible with JDK 17 and 21. Users who want to test JDK 21 performance or behavior on Cassandra 5.0 have no way to do so today: the `use-cassandra` script does not recognise `java: "21"` and will exit with "Unknown java version 21".

The fix is straightforward: install JDK 21 in the base packer image, teach `use-cassandra` to switch to it, and update `cassandra_versions.yaml` to declare the correct JDK for trunk.

## What Changes

- **Install JDK 21** in the base packer image (`packer/base/base.pkr.hcl`). Add `openjdk-21-jdk` and `openjdk-21-dbg` to the apt install step alongside the existing 8/11/17 packages.
- **Support `java: "21"` in `use-cassandra`** (`packer/cassandra/bin/use-cassandra`). Add an `elif` branch for `JAVA_VERSION = "21"` that calls `update-java-alternatives -s java-1.21.0-openjdk-$ARCH`, matching the existing pattern for other versions.
- **Update `cassandra_versions.yaml`** to set `trunk` to `java: "21"`. The trunk build already runs on JDK 21; this aligns the runtime to match. Cassandra `5.0` remains on JDK 11 (its minimum supported version) unless explicitly requested.

## Capabilities

### Modified Capabilities

- `db-node-provisioning` (implicitly): EC2 database nodes now ship with JDK 8, 11, 17, and 21. The version-switching mechanism covers all four.

## Impact

- **`packer/base/base.pkr.hcl`**: Add `openjdk-21-jdk openjdk-21-dbg` to the apt install inline provisioner.
- **`packer/cassandra/bin/use-cassandra`**: Add `elif [ "$JAVA_VERSION" = "21" ]` branch.
- **`packer/cassandra/cassandra_versions.yaml`**: Change trunk entry from `java: "17"` to `java: "21"`.
- **Docs**: Update any documentation that lists supported JDK versions on cluster nodes.
- **Tests**: Packer script tests can verify the JDK 21 install step (Docker-based `testPackerBase`).
- **No Kotlin changes required**: This is a packer / provisioning-only change.
