## ADDED Requirements

### Requirement: JDK 25 Selection

The system SHALL let an operator run a Cassandra version on JDK 25.  `use-cassandra` SHALL switch the node to JDK 25 when the version's `java` field is `25`.

#### Scenario: Select JDK 25 for a version

- **GIVEN** a node with a Cassandra version installed
- **WHEN** the operator runs `cassandra use <version> --java 25`
- **THEN** `use-cassandra` runs `update-java-alternatives -s java-1.25.0-openjdk-<arch>`
- **AND** it does not exit with "Unknown java version"

#### Scenario: Cassandra runs on JDK 25

- **GIVEN** a Cassandra build that supports JDK 25
- **WHEN** the operator runs `set-java-version 25` for that version, activates it, and starts Cassandra
- **THEN** Cassandra runs on JDK 25

### Requirement: GC Log on JDK 17 and Higher

The Cassandra launch environment SHALL write the JVM GC log to `gc.log` for every JDK at 17 or higher, and SHALL read the JDK major version from both `version "N"` and `version "N.x.y"` forms.

#### Scenario: GC log on JDK 25

- **GIVEN** a node whose active JDK is 25
- **WHEN** Cassandra starts
- **THEN** the JVM options include `-Xlog:gc` writing to `gc.log` in the Cassandra log directory

#### Scenario: No GC log option on JDK 8 or 11

- **GIVEN** a node whose active JDK is 8 or 11
- **WHEN** Cassandra starts
- **THEN** the JVM options do not include the `-Xlog:gc` option

#### Scenario: Version string with no minor part

- **GIVEN** `java -version` prints `openjdk version "25" 2025-09-16`
- **WHEN** `cassandra.in.sh` parses the Java version
- **THEN** `ECL_JAVA_VERSION` is `25`
