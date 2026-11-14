# Cassandra

## Purpose

Manages Apache Cassandra deployment, version selection, configuration, and cluster operations.
## Requirements
### Requirement: Multi-Version Support

The system MUST support multiple Cassandra versions (3.0 through trunk) on the same AMI.

#### Scenario: Select a Cassandra version

- **GIVEN** a running cluster
- **WHEN** the user selects a Cassandra version
- **THEN** that version is activated with appropriate Java and Python runtime versions.

#### Scenario: Start cluster on selected version

- **GIVEN** a selected version
- **WHEN** the user starts the cluster
- **THEN** all nodes run the selected Cassandra version and join the ring.

### Requirement: Configuration Management

The system MUST allow configuration of Cassandra via YAML patch files.

#### Scenario: Push a YAML patch

- **GIVEN** a local YAML patch file with configuration overrides
- **WHEN** the user pushes configuration
- **THEN** the patch is applied to cassandra.yaml on all targeted nodes.

#### Scenario: Restart on config push

- **GIVEN** updated configuration
- **WHEN** the user requests a restart alongside the config push
- **THEN** nodes are restarted with the new configuration.

### Requirement: Cluster Lifecycle

The system MUST support starting, stopping, and restarting Cassandra across cluster nodes. When starting, the system SHALL also deploy the Cassandra sidecar as a K3s DaemonSet after all Cassandra nodes are up. A stop of some db nodes SHALL NOT affect the db nodes that still run the database: the sidecar DaemonSet, the running-workload record and the kit post-stop hooks change only when no db node still runs the database.

#### Scenario: Sequential start with delay

- **GIVEN** a configured Cassandra version
- **WHEN** the user starts the cluster
- **THEN** nodes start sequentially with a configurable delay between them.

#### Scenario: Graceful stop

- **GIVEN** a running Cassandra cluster
- **WHEN** the user stops it
- **THEN** all nodes are stopped gracefully.

#### Scenario: Sidecar DaemonSet applied after start

- **WHEN** the user runs `cassandra start`
- **THEN** after all Cassandra nodes are up, the sidecar DaemonSet is applied to K3s.

#### Scenario: A stop of some db nodes keeps the sidecar

- **GIVEN** a running cluster with the db nodes db0 and db1
- **WHEN** the user runs `cassandra stop --hosts db0`
- **THEN** the database stops on db0 only
- **AND** the sidecar DaemonSet stays in place, the database stays recorded as a running workload, and the kit post-stop hooks do not run
- **AND** the output names the db nodes that still run the database and keep the sidecar.

#### Scenario: The last db node to stop removes the sidecar

- **GIVEN** a cluster where db0 is stopped and db1 still runs the database
- **WHEN** the user runs `cassandra stop --hosts db1`
- **THEN** no db node still runs the database
- **AND** the sidecar DaemonSet is removed, the database is no longer recorded as a running workload, and the kit post-stop hooks run.

### Requirement: Config Directory Per Version

`cassandra download-config` MUST download one local config directory for each distinct version
among the targeted hosts, from the first targeted host that runs it, and MUST honour `--hosts`
(a value that matches no node is an error). It MUST NOT leave an empty directory behind when the
download fails. `cassandra update-config` MUST check, before it changes any targeted host, that
the local config directory of every targeted host's version exists, and MUST name
`cassandra download-config --hosts <host>` for each one that does not.

#### Scenario: `use` on one host of a mixed-version cluster

- **GIVEN** `db0` and `db1` run `5.0` and `db2` is switched to `4.1`
- **WHEN** the user runs `cassandra use --hosts=db2 4.1`
- **THEN** the workspace gets a `4.1/` directory, `db2` is patched and updated from it, and the
  command exits 0.

#### Scenario: `update-config` without the version's directory

- **GIVEN** `db2` runs a version whose directory is not in the workspace
- **WHEN** the user runs `cassandra update-config --hosts=db2`
- **THEN** no host is patched, the error names the version and `download-config --hosts db2`, and
  the command exits non-zero.

### Requirement: Use Side Effects

`cassandra use` MUST tell the user, before it starts, which hosts it will re-apply
`cassandra.patch.yaml` to as its last step (replacing per-host settings applied earlier with
`update-config`), and that it switches the JDK (to `--java`, or the JDK the version declares). It
MUST pass its `--hosts` filter to the config download as well as the update, MUST fail when no
host matches the filter, and MUST exit non-zero, naming the failed step, when the config download
or update fails after the version switch.

#### Scenario: `use` reports a failed config update

- **GIVEN** the version switch succeeded on the targeted hosts
- **WHEN** the config update fails
- **THEN** the command reports that the version is active but the configuration was not updated,
  names `update-config --hosts` for the targeted hosts, and exits non-zero.

### Requirement: Mixed-Version Clusters

The system MUST support running different Cassandra versions on different nodes for upgrade testing.

#### Scenario: Per-host version selection

- **GIVEN** a running cluster
- **WHEN** the user selects different versions for different hosts
- **THEN** each host runs its assigned version independently.

### Requirement: CQL Access

The system MUST provide CQL query execution against the cluster via the Java driver, routed through the network access layer (SOCKS proxy or Tailscale).

#### Scenario: Execute a CQL query

- **GIVEN** a running Cassandra cluster
- **WHEN** the user executes a CQL query
- **THEN** the query is routed through the available network path and results are displayed.

#### Scenario: CQL session reuse

- **GIVEN** the REPL or server is running
- **WHEN** multiple CQL queries are executed
- **THEN** the CQL session is reused across queries.

### Requirement: Nodetool Access

The system MUST provide nodetool execution on cluster nodes.

#### Scenario: Invoke nodetool

- **GIVEN** a running cluster
- **WHEN** the user invokes nodetool
- **THEN** the specified nodetool command is executed on the targeted node.

### Requirement: Runtime Version Installation

The system MUST support installing an additional Cassandra version onto an already-provisioned,
running cluster without rebuilding the AMI, via `cassandra install <version>`.

The system MUST support both existing install mechanisms for a version: downloading a tarball from
a `url:` (including an official release resolved from a version prefix when no `url:` is set), and
cloning a git `url:`+`branch:` and building it with `ant`. The same mechanism MUST be used whether
the version is installed at AMI bake time or via `cassandra install` at runtime — one
implementation, not two kept in sync by hand.

The system MUST resolve a version's install parameters (`java`, `python`, `url`/`branch`,
`ant_flags`) from a declared `cassandra_versions.yaml` entry (including profile-local extras) by
default, and MUST allow those parameters to be supplied directly via CLI options instead, without
requiring any yaml declaration.

The system MUST support targeting all Cassandra nodes or a `--hosts`-filtered subset, matching the
targeting mechanism `cassandra use` already provides.

The system MUST write the resolved version's install parameters into the targeted node's
`/etc/cassandra_versions.yaml` before installing, so that `cassandra use` for that version
succeeds afterward without further changes.

The system MUST treat installing an already-installed version as a safe no-op — no re-download,
no re-build, no error.

The system MUST report success or failure per targeted host, and MUST fail the overall command
when any targeted host fails to install, without leaving other hosts partially configured beyond
what each host's own install actually completed.

A `cassandra_versions.yaml` entry MAY declare `lazy: true`. Such an entry MUST be skipped by the
AMI bake-time install process (no download/clone/build time spent at bake), while still being
written into the resulting AMI's `/etc/cassandra_versions.yaml` so `cassandra install` can resolve
it later without the operator re-specifying its fields.

The system MUST fetch a version that has a tarball `url:` (or a tarball given with `cassandra install --url`) from its origin on every install, at AMI bake time and at runtime.  It MUST NOT read that tarball from the S3 download cache, and it MUST NOT write it to the cache.  The contents of such a URL can change under the same name (for example, a nightly build), so a cached copy can be an old build.  When the URL is `s3://...`, the system MUST copy it directly from S3.  When the download fails, the system MUST fail with its existing download error and MUST NOT fall back to a cached copy.  An official release resolved from a version prefix does not change upstream, so the system MAY fetch it through the S3 download cache.

#### Scenario: Install from a declared git branch entry

- **GIVEN** a `cassandra_versions.yaml` entry for a version not yet present on the targeted node(s),
  with a git `url:` and `branch:`
- **WHEN** the user runs `cassandra install <version>`
- **THEN** the branch is cloned and built with `ant` on each targeted node
- **AND** the built artifact lands at `/usr/local/cassandra/<version>`
- **AND** the command reports success per targeted host.

#### Scenario: Install from a declared tarball entry

- **GIVEN** a `cassandra_versions.yaml` entry for a version not yet present, with a tarball `url:`
- **WHEN** the user runs `cassandra install <version>`
- **THEN** the tarball is downloaded and extracted to `/usr/local/cassandra/<version>` on each
  targeted node.

#### Scenario: Install an official release with no `url:` declared

- **GIVEN** a `cassandra_versions.yaml` entry for a version prefix (e.g. `5.0`) with no `url:` set
- **WHEN** the user runs `cassandra install <version>`
- **THEN** the latest matching official release tarball is resolved, downloaded, and extracted to
  `/usr/local/cassandra/<version>` on each targeted node.

#### Scenario: Install via CLI flags with no yaml declaration

- **GIVEN** a version with no entry anywhere in `cassandra_versions.yaml` or the profile's extras
  directory
- **WHEN** the user runs `cassandra install <version> --url <tarball-or-git-url> [--branch <b>]
  --java <j>`
- **THEN** the version installs using the supplied parameters exactly as if they had come from a
  declared entry
- **AND** `--python` defaults to `3.11.9` when not supplied.

#### Scenario: Targeting a host subset

- **GIVEN** a cluster with multiple Cassandra nodes
- **WHEN** the user runs `cassandra install <version> --hosts <subset>`
- **THEN** only the targeted nodes have `/etc/cassandra_versions.yaml` updated and the version
  installed
- **AND** untargeted nodes are unaffected.

#### Scenario: Installed version becomes usable

- **GIVEN** a version successfully installed via `cassandra install`
- **WHEN** the user runs `cassandra use <version>` on the same node afterward
- **THEN** it succeeds unchanged, using the `java`/`python` fields written by install.

#### Scenario: Re-running install for an already-installed version is a no-op

- **GIVEN** a version already installed on a node, declared there with the same parameters being
  requested
- **WHEN** the user runs `cassandra install <version>` again against that node
- **THEN** the command does not error, does not re-download or re-build, and reports the version as
  already present.

#### Scenario: Re-running install with parameters the installed build contradicts

- **GIVEN** a version already installed on a node, declared there with `java: 11`
- **WHEN** the user runs `cassandra install <version> --java 17` against that node
- **THEN** nothing on that node is changed — neither the on-disk install nor its declaration
- **AND** the host is reported with the fields that disagree (`java: declared 11, requested 17`)
- **AND** the command exits non-zero.

Rationale: the bits on disk were built against the declared JDK, so recording them as a Java 17
build would make every later `cassandra use` select the wrong JDK. Changing the JDK an existing
build runs under is `cassandra use --java`'s job.

#### Scenario: Repairing an installed-but-undeclared version

- **GIVEN** a version present on disk at `/usr/local/cassandra/<version>` but absent from that
  node's `/etc/cassandra_versions.yaml`
- **WHEN** the user runs `cassandra install <version>` against that node
- **THEN** the resolved entry is added to the node's version list so `cassandra use` can find its
  `java`/`python`
- **AND** the on-disk install is not re-downloaded or re-built
- **AND** the command reports the version as already present and does not error.

#### Scenario: A credential in `--url` is not persisted or echoed

- **GIVEN** a `--url` whose userinfo carries a token (e.g. `https://<token>@github.com/owner/repo.git`)
- **WHEN** `cassandra install <version> --url <that-url> --branch <branch>` runs
- **THEN** the entry written to the node's `/etc/cassandra_versions.yaml` contains no `url`/`branch`
  field at all
- **AND** the token does not appear in any log line, error message, or emitted event, whether the
  install succeeds or fails.

#### Scenario: Install failure is reported per host, loudly

- **GIVEN** a version whose install fails on one targeted node (bad branch, unreachable/404 tarball
  URL, or ant build failure) while succeeding on another
- **WHEN** `cassandra install <version>` completes
- **THEN** the command exits non-zero
- **AND** the failure is reported against the specific host it occurred on, naming the version and
  the failure reason
- **AND** the successful host's install is left in place — no rollback of a host that already
  succeeded.

#### Scenario: Lazy entries are skipped at bake time but installable afterward

- **GIVEN** a `cassandra_versions.yaml` entry with `lazy: true`
- **WHEN** a new AMI is baked
- **THEN** the bake-time install process does not download, clone, or build that version
- **AND** the entry still appears in the resulting AMI's `/etc/cassandra_versions.yaml`
- **AND** `cassandra install <that-version>` succeeds against a cluster running that AMI without
  the operator re-specifying `url`/`branch`/`java`/`ant_flags`.

#### Scenario: `cassandra list` distinguishes declared-but-uninstalled versions

- **GIVEN** a `cassandra_versions.yaml` entry with `lazy: true` that has not been installed on any
  node
- **WHEN** the user runs `cassandra list`
- **THEN** that version is shown, marked distinguishably from versions actually installed on the
  nodes.

#### Scenario: `cassandra list` asks every node

- **GIVEN** a cluster in which `db0` and `db1` have version `5.0` and `db2` also has `5.0.9-custom`
- **WHEN** the user runs `cassandra list`
- **THEN** `5.0` is listed as installed, and `5.0.9-custom` is listed as installed on `db2` only,
  naming the nodes it is missing from
- **AND** the output never presents one node's directory listing as the cluster's.

#### Scenario: `cassandra list` names a node it cannot list

- **GIVEN** a node whose versions cannot be listed
- **WHEN** the user runs `cassandra list`
- **THEN** the output names that node and the error, lists what the other nodes reported, and the
  command exits non-zero.

#### Scenario: A stale cache object for a URL version is ignored

- **GIVEN** a version with a tarball `url:` whose origin now serves a newer nightly build
- **AND** the S3 download cache holds an older tarball for that version
- **WHEN** the version is installed at AMI bake time or with `cassandra install`
- **THEN** the tarball is downloaded from the URL and the newer nightly build is installed
- **AND** the S3 download cache is not read and not written for that tarball
- **AND** the install log shows one `direct download (not cached): <url>` line for that tarball.

#### Scenario: A URL version is not written to a cold cache

- **GIVEN** a version with a tarball `url:` and an S3 download cache that holds no object for it
- **WHEN** the version is installed
- **THEN** the tarball is downloaded from the URL
- **AND** no object for that tarball is written to the S3 download cache.

#### Scenario: A failed URL download does not fall back to the cache

- **GIVEN** a version with a tarball `url:` whose download fails
- **AND** the S3 download cache holds a tarball for that version
- **WHEN** the version is installed
- **THEN** the install fails with the existing "Failed to download version" error
- **AND** the cached tarball is not read.

#### Scenario: Released versions still use the cache

- **GIVEN** a version prefix (e.g. `5.0`) with no `url:` set and an S3 download cache configured
- **WHEN** the version is installed
- **THEN** the latest matching official release tarball is fetched through the S3 download cache, under a key named for the full release number
- **AND** the install log shows a `cache hit:` or `cache miss:` line for that release.

#### Scenario: An s3 URL is copied directly from S3

- **GIVEN** a version with a tarball `url:` of the form `s3://...`
- **WHEN** the version is installed
- **THEN** the tarball is copied directly from that S3 location
- **AND** the S3 download cache is not read and not written for that tarball
- **AND** the install log shows one `direct download (not cached): <url>` line for that tarball.

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

## Success Criteria

- Users can switch Cassandra versions in under 1 minute without reprovisioning infrastructure.
