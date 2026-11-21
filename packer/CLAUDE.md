# Packer: AMI builds and provisioning scripts

## Building AMIs

**`build-image` / `build-base` / `build-cassandra` bake from `build/install/easy-db-lab/packer/`, not from the working tree.** `Context.appHome` points at the Gradle `installDist` output, so a change under `packer/` does not reach an AMI until `./gradlew installDist` has run — committing it is not enough, and nothing in the build output says which copy it used. Always run `./gradlew installDist` before baking, exactly as you would after changing Kotlin source.

The images are stacked: `cassandra = base + the db-node setup` (no Cassandra version is baked; the CLI installs versions on demand), and `cassandra.pkr.hcl` selects the most recent `available` base AMI, so a base AMI that a parallel bake is still registering is skipped. A change under `packer/base/` therefore needs **both** images rebuilt (`build-image`), not just the Cassandra one.

Under the `ssm` SSH transport, Packer reaches the builder with `ssh_interface = "session_manager"` and runs in a derived image (`containers/PackerImage.kt`), built locally from the packaged `containers/packer-ssm.Dockerfile` resource and tagged by its content hash. Editing that Dockerfile is enough to get a rebuild on the next `ssm` AMI build. The Dockerfile pins the Packer base image by digest and the Session Manager plugin by version and per-architecture SHA-256; upgrading either means changing the pin and its checksum together.

Both templates take `ssh_keep_alive_interval` and `ssh_read_write_timeout`, and `Packer.buildCommand` passes them for every transport (`Constants.Packer.SSH_KEEP_ALIVE_INTERVAL` / `SSH_READ_WRITE_TIMEOUT`, 10s and 2m). The timeout is the only thing that bounds a stream that went silent: the SDK's keepalive alone never fails a command. Every attempt runs with `PACKER_LOG_PATH` set to a fresh owner-only directory under `<profile>/packer-logs/` (`containers/PackerLogs.kt`; the log holds the SSM session token, never publish it). `PackerFailure.fromLog` classifies a failure by the last `Build '...' errored` line, and `PackerBuildAttempts` (a resilience4j retry on the result) reruns a `SessionDropped` build from a new builder, up to `MAX_BUILD_ATTEMPTS`. Every instance in the packer VPC is terminated in between (`Packer.handleBuildFailure` returns a `BuilderCleanup`); a cleanup that could not list or terminate them is `BuilderCleanup.Unresolved` and stops the retry, so a second builder never runs beside one that may still be up. Do not use the shell provisioner's `max_retries` instead: the scripts are not idempotent.

## Packer Script Testing

Test packer provisioning scripts locally using Docker (no AWS required):

```bash
# Test base provisioning scripts
./gradlew testPackerBase

# Test Cassandra provisioning scripts
./gradlew testPackerCassandra

# Run all packer tests
./gradlew testPacker

# Test a specific script
./gradlew testPackerScript -Pscript=cassandra/install/install_cassandra_easy_stress.sh
```

Shell scripts whose logic (argument handling, already-installed short-circuits, JDK selection)
matters more than their side effects also have plain shell unit tests that stub `sudo`/`curl`/`git`
and need no Docker:

```bash
./gradlew testCassandraScripts
./gradlew testBaseJdkInstall   # base AMI JDK install (install_jdks.sh)
./gradlew testBaseChronyPoll   # base AMI chrony poll (configure_chrony_poll.sh)
./gradlew testBasePrimaryEniNetwork   # base AMI primary ENI boot script (edl-primary-eni-network)
```

`base/install/edl-primary-eni-network` is a **boot-time** script, not a bake step:
`configure_cilium_eni_networkd.sh` installs it to `/usr/local/sbin/` and enables
`edl-primary-eni-network.service`, which runs it on every boot before `systemd-networkd`. It writes
the primary ENI's `05-cilium-eni-primary.network` to **`/run/systemd/network/`**, matched by the IMDS
primary MAC, never to `/etc` — a file in `/etc` would be baked into the cassandra AMI with the bake
instance's MAC. It stays POSIX sh. Its test, `edl-primary-eni-network.test.sh`, needs no Docker: the
script reads its input file and both network dirs from `EDL_INSTANCE_DATA`, `EDL_RUN_NETWORK_DIR`
and `EDL_ETC_NETWORK_DIR`. Packer uploads it to `/tmp` with a `file` provisioner; `test-base` in
`docker-compose.yml` points `EDL_PRIMARY_ENI_SCRIPT_SOURCE` at the mounted copy instead.

Scripts in `packer/cassandra/bin/` are **not bake-time-only** — the AMI puts them on the node's
`PATH` and the CLI invokes them over SSH against a running cluster (`cassandra install`,
`cassandra use`). Their flags and output are a contract with Kotlin callers; see
[`commands/CLAUDE.md`](../src/main/kotlin/com/rustyrazorblade/easydblab/commands/CLAUDE.md).

`packer/cassandra/lib/edl-cassandra-agents.sh` (installed to `/usr/local/lib/`) picks the AxonOps
agent from the release's own jar name. Metrics come from the OpenTelemetry Java agent, which is one
jar for every release and needs no such selection. The library is sourced by `cassandra.in.sh`, which
Cassandra's `bin/cassandra` runs under **`/bin/sh` (dash), not bash** — so it must stay POSIX sh.
A bashism there does not degrade to "no metrics"; dash fails to parse the file and Cassandra will
not start. `edl-cassandra-agents.test.sh` exercises the functions through a real `/bin/sh` for
exactly this reason.

For more details, see [packer/README.md](README.md) and [packer/TESTING.md](TESTING.md).
