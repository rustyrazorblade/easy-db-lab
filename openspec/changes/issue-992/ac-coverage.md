| Source | Requirement | Covering scenario(s) | Status |
|--------|-------------|----------------------|--------|
| AC | The built base AMI has JDK 25 and its debug symbols in `/usr/lib/jvm/`, next to 8, 11, 17, and 21 | `ami-building: Base AMI provides JDK 25` | ✅ Covered |
| AC | `set-java-version 25` then a Cassandra start runs Cassandra on JDK 25 | `cassandra: Select JDK 25 for a version`; `cassandra: Cassandra runs on JDK 25` | ✅ Covered |
| Scope | The node default JDK stays 11 | `ami-building: Node default JDK is unchanged` | ✅ Covered |
| Risk | `use-cassandra` rejects 25 with "Unknown java version" | `cassandra: Select JDK 25 for a version` | ✅ Covered |
| Risk | A JDK 25 node writes no `gc.log` | `cassandra: GC log on JDK 25`; `cassandra: No GC log option on JDK 8 or 11` | ✅ Covered |
| Risk | The version parse fails on `version "25"` with no minor part | `cassandra: Version string with no minor part` | ✅ Covered |
| Risk | A wrong jinfo name leaves the node on its old JDK without an error | `ami-building: Base AMI provides JDK 25` (the alternatives entry exists) | ✅ Covered |
| Risk | Released Cassandra builds do not start on JDK 24+ | — | ⚠️ Excluded: the owner checks the second criterion against the owner's own JDK 25 builds; upstream Cassandra is outside this repo |
