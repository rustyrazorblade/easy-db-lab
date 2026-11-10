## Why

The base AMI installs JDK 8, 11, 17, and 21, but not JDK 25.  The owner's own Cassandra builds require JDK 25, so a test that runs them is blocked until the AMI has JDK 25 and a node can switch to it.

## What Changes

- `packer/base/install/install_jdks.sh`: add `openjdk-25-jdk` and `openjdk-25-dbg` to `PACKAGES`, and list 25 in the header comment.
- `packer/cassandra/bin/use-cassandra`: add a branch for `java: "25"` that runs `update-java-alternatives -s java-1.25.0-openjdk-$ARCH`.  Today the script exits with "Unknown java version" for 25, so `cassandra use <v> --java 25` writes 25 to `/etc/cassandra_versions.yaml` and then fails.
- `packer/cassandra/bin/use-cassandra.test.sh`: add a case that a `java: "25"` entry selects `java-1.25.0-openjdk`.
- `packer/cassandra/cassandra.in.sh`:
  - Turn on the GC log (`-Xlog:gc` to `gc.log`) for every JDK at 17 or higher, not only for exactly 17 or 21.  Without this, a JDK 25 node writes no `gc.log`, and the `source="cassandra-gc"` stream in Loki is empty for it.
  - Fix the Java version parse so that it also reads a version string with no minor part (`version "25"`).  Today the `sed` expression needs a `.` after the major number, and on a bare `"25"` it leaves the whole `java -version` line in `ECL_JAVA_VERSION`.
  - Update the comment "Every db node runs 17 or 21".
- Help text and comments list JDK 25: `UseCassandra.kt` (today it says "8, 11 or 17 accepted", which is already wrong), `CassandraInstall.kt`, `CassandraBuild.kt`, and the JDK comment in `packer/base/base.pkr.hcl`.

## Scope

**In:** the items above.

**Out:**
- Changes to `cassandra_versions.yaml` or to the default JDK of any Cassandra version.
- Changes to the node default JDK (it stays 11).
- Node agent work (the OpenTelemetry Java agent, AxonOps, profiling tools).
- Running the easy-db-lab CLI or `detekt` on JDK 25.
- `packer/Dockerfile` and `packer/TESTING.md`, which already leave out JDK 21.

## Capabilities

- `ami-building`: ADDED requirement for the set of JDKs the base AMI provides.
- `cassandra`: ADDED requirements for selecting JDK 25 on a node and for the GC log on JDK 17 and higher.

## Impact

Both images need a rebuild: the base AMI first, then the Cassandra AMI, with `./gradlew installDist` before baking.  The `use-cassandra` and `cassandra.in.sh` edits reach a node only through the Cassandra image.
