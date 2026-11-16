## Context

The base AMI (Ubuntu 26.04) installs JDKs with `packer/base/install/install_jdks.sh`.  On a node, `cassandra use <v> --java <j>` runs `set-java-version <j> <v>`, which only records `java` in `/etc/cassandra_versions.yaml`, and then `sudo use-cassandra <v>`, which does the actual JDK switch with `update-java-alternatives`.  `use-cassandra` has a fixed if/elif chain for 8, 11, 17, and 21.

`cassandra.in.sh` is sourced by Cassandra's `bin/cassandra` under `/bin/sh` (dash), so it must stay POSIX sh.  It parses `java -version` into `ECL_JAVA_VERSION` and turns on the GC log only when that value is exactly 17 or 21.

## Decisions

1. **Add JDK 25 packages to `install_jdks.sh`**, the same way as the other JDKs.  Ubuntu 26.04 ships `openjdk-25-jdk` and `openjdk-25-dbg`.
2. **Add a `25` branch to `use-cassandra`** using `java-1.25.0-openjdk-$ARCH`, the same naming pattern as 21.  Without it the second acceptance criterion cannot be met.
3. **GC log for JDK 17 and higher** (`[ "$ECL_JAVA_VERSION" -ge 17 ]`).  `-ge` is POSIX.  JDK 8 parses to `1`, so it stays excluded.  Owner decision at the design stop.
4. **Parse a version with no minor part.**  Change the `sed` expression to accept either `.` or `"` after the major number, so `version "25"` and `version "25.0.1"` both yield `25`.  This was found by the design critic; the project rule is to fix a bug found during the change in the change.
5. **Help text and comments list 25.**  Owner decision at the design stop.

## Alternatives Considered

- **Scope only `install_jdks.sh` (the issue as filed).**  Rejected: `use-cassandra` rejects 25, so the node cannot switch to JDK 25 and the second acceptance criterion fails.
- **Leave the GC log condition as 17 or 21.**  Rejected by the owner: JDK 25 runs would have no GC log in Loki.
- **Leave the help text and comments as they are.**  Rejected by the owner.
- **Restate the second acceptance criterion as "the node's active JDK becomes 25"**, because no released Cassandra in `cassandra_versions.yaml` starts on JDK 25 (the launcher's supported list stops at 21, and the security manager fails on JDK 24+).  The design critic raised this as a blocker.  The owner kept the criterion as written: it is checked against the owner's own Cassandra builds, which require JDK 25.  This was an explicit owner decision, not the advisors' recommendation.

## Risks

- **jinfo name.**  `update-java-alternatives -s java-1.25.0-openjdk-<arch>` needs `/usr/lib/jvm/.java-1.25.0-openjdk-<arch>.jinfo`.  `use-cassandra` ignores a failed switch, and the unit test stubs `update-java-alternatives`, so a wrong name would leave the node on its old JDK without an error.  Check the jinfo file on the built AMI.
- **Rebuild order.**  Base AMI first, then the Cassandra AMI, with `./gradlew installDist` before baking.
- **AxonOps.**  The agent selector returns nothing for JDK 25 and prints its existing NOTE.  This is expected and out of scope.
