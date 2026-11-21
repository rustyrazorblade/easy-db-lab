## ADDED Requirements

### Requirement: One config merge for the VM path and the kit
`packer/cassandra/lib/edl-cassandra-config.sh` SHALL be POSIX sh. It SHALL define `edl_merge_cassandra_yaml <orig> <out> <patch>...`, which merges each patch, in order, onto the original `cassandra.yaml` with yq's `. *= load(...)` and writes the result to `<out>`. `patch-config` and the `cassandra-pods` kit's render step SHALL both use this function, and neither SHALL merge `cassandra.yaml` any other way. `patch-config` SHALL keep its output contract with `UpdateConfig`: the "Successfully patched" marker on success, and the same exit codes on each failure.

#### Scenario: Patches merge in order
- **WHEN** `edl_merge_cassandra_yaml orig.yaml out.yaml a.yaml b.yaml` runs and both patches set `concurrent_reads`
- **THEN** `out.yaml` has `b.yaml`'s value, and every key neither patch sets is as in `orig.yaml`

#### Scenario: patch-config contract unchanged
- **WHEN** `patch-config` patches a node's `cassandra.yaml` successfully
- **THEN** it prints the "Successfully patched" marker and exits 0, as before the change

#### Scenario: Runs under dash
- **WHEN** the library is sourced and called by `/bin/sh` (dash)
- **THEN** the merge succeeds

### Requirement: cassandra.in.sh takes env overrides
`cassandra.in.sh` SHALL read each of these from the environment, falling back to its current hardcoded default when the variable is unset:

- the agents library path (`ECL_AGENTS_LIB`);
- the build label (`EDL_CASSANDRA_BUILD`), which today comes from `readlink /usr/local/cassandra/current`;
- the OTLP endpoint, today `localhost:4318`;
- the log directory;
- extra OpenTelemetry resource attributes, which SHALL be appended to the ones the file sets, so a pod's `host.name` is kept.

On a VM with none of them set, the JVM options SHALL be unchanged. The file SHALL stay POSIX sh, because Cassandra's `bin/cassandra` sources it under `/bin/sh`.

#### Scenario: VM defaults unchanged
- **WHEN** `cassandra.in.sh` is sourced by `/bin/sh` on a VM with none of the override variables set
- **THEN** the JVM options are identical to those before the change

#### Scenario: Overrides applied under dash
- **WHEN** `cassandra.in.sh` is sourced by `/bin/sh` (dash) with the agents library path, build label, OTLP endpoint and log directory set
- **THEN** the JVM options use each given value

#### Scenario: Extra resource attributes keep host.name
- **WHEN** the extra resource attributes hold `host.name=node-a`
- **THEN** the agent's `otel.resource.attributes` holds `host.name=node-a` together with the attributes the file sets
