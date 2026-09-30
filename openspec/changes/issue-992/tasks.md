## 1. Base AMI

- [ ] 1.1 Add `openjdk-25-jdk openjdk-25-dbg` to `PACKAGES` in `packer/base/install/install_jdks.sh`, and list 25 in its header comment.
- [ ] 1.2 Update the JDK comment in `packer/base/base.pkr.hcl` ("8/11/17/21" to "8/11/17/21/25").

## 2. JDK switch on the node

- [ ] 2.1 Add a failing case to `packer/cassandra/bin/use-cassandra.test.sh`: a `java: "25"` entry selects `java-1.25.0-openjdk`.
- [ ] 2.2 Add the `25` branch to `packer/cassandra/bin/use-cassandra`.

## 3. Cassandra launch environment (`packer/cassandra/cassandra.in.sh`, POSIX sh)

- [ ] 3.1 Change the Java version `sed` expression so that `version "25"` and `version "25.0.1"` both yield `25`, and `version "1.8.0_x"` still yields `1`.
- [ ] 3.2 Change the GC log condition to `[ "$ECL_JAVA_VERSION" -ge 17 ]`.
- [ ] 3.3 Update the comment "Every db node runs 17 or 21".
- [ ] 3.4 Confirm the file still parses under `/bin/sh` (dash), for example with `./gradlew testCassandraAgentSelection`.

## 4. Help text

- [ ] 4.1 `UseCassandra.kt`: the `--java` help lists 8, 11, 17, 21, 25.
- [ ] 4.2 `CassandraInstall.kt` and `CassandraBuild.kt`: the `--java` examples list 25.

## 5. Verify

- [ ] 5.1 `./gradlew testCassandraScripts` passes.
- [ ] 5.2 Docs: update any user doc that lists the installed JDKs.
