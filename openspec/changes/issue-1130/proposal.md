## Why

A db node can run only the Cassandra versions that the Cassandra AMI bake installed.  A new release, an older patch release or a new branch build needs a new bake, and each version in the bake makes the bake longer.  The owner's goal: "I don't want the download to happen during the bake.  I want it to be on demand, so ANY version of cassandra can be installed without requiring a bake."  This is step 1 of #961 (remove the Cassandra AMI), which the multi-cloud epic #1062 needs before any GCP image exists.

## What Changes

- **No Cassandra download in the bake.**  The Cassandra AMI bake installs no Cassandra version and no per-release AxonOps agent.  It keeps the install scripts, the one-time node setup, the `axon-agent` package and its sudoers file, and all other software.  `/usr/local/cassandra` is empty on a new node.
- **`cassandra use <ver>` installs on demand.**  When a targeted node does not have the version, `use` installs it through the same path as `cassandra install`, and then activates it.  A version that is on the node is not downloaded again.
- **Four version sources, resolved by the CLI**, after a record in `state.json`:
  - Named builds in the profile: the entries in `<profile dir>/cassandra_versions/*.yaml`, each with a name and a URL, and optionally the JDKs, the default JDK, Python and `ant_flags`.  They are checked first, and their JDK and Python values take the place of the manifest and the table.  The bake no longer merges them.
  - ASF releases: any release on the ASF dist site, by full number (`4.1.7`) or by release line (`5.0` = the latest `5.0.x`).  No version file entry is necessary.  The tarball goes through the account's download cache.  The node tries `dlcdn.apache.org` first and `archive.apache.org` second.
  - cassandra-builds branch builds: every tarball on the `nightly` release of `rustyrazorblade/cassandra-builds` (today `5.0-HEAD`, `6.0-HEAD`, `trunk`, `5.0-rustyrazorblade-HEAD` and `6.0-rustyrazorblade-HEAD`).  No URL in the repo points at an easy-db-lab release.
  - The S3 build catalog (`cassandra build`), as today.
- **JDK choice by the CLI.**  The supported JDK list comes from `--java`, else the profile entry, else the build's manifest, else a release-line table in the CLI resources.  The table has a per-ref entry `5.0-rustyrazorblade` (11, 17, 21), checked by a test against the fork's `build.xml`, until the fork ships its manifest.  With no `--java`, the CLI picks the highest supported JDK.  `--java N` fails when N is not supported.  A version with no manifest and no release line fails, and the message says to pass `--java`.  **BREAKING:** ASF 5.0 now starts on JDK 17 (it was 11).
- **Python** is `--python`, else the profile entry's Python, else `Constants.Cassandra.DEFAULT_PYTHON_VERSION`.
- **The version record is in `state.json`.**  `ClusterState.versions` is folded into `nodes[alias]`: the active version, and an `installed` map of `InstalledVersion` records (version, JDK, supported JDKs, Python, source, URL).  The separate `versions` map is removed.  The CLI calls the node scripts with explicit arguments.  The node scripts do no lookups and do not use `yq`.  The node keeps only the install directories.  A replaced node (#1158) installs only its active version, with the JDK and Python from `state.json`.
- **`cassandra install --reinstall`** downloads and installs a version again on a node that has it, and updates its record.  It is the manual way to get a nightly that changed under the same name.  There is no automatic change check.
- **AxonOps agents install on demand.**  When AxonOps is on, `cassandra start` installs the agent that `edl_axonops_agent_for` selects for the release and the JDK, through the download cache.  The chosen JDK always wins.  When AxonOps ships no agent for the release and the JDK, `cassandra start` fails before Cassandra starts on any node, and names `--skip-axonops`.  **BREAKING:** today the start goes ahead with no agent and no error.
- **`cassandra list`** shows the profile builds (source `profile`), the ASF releases, the cassandra-builds builds and the S3 build catalog in one list, with the source of each entry.  It works without a cluster.  With a cluster, it also marks the versions installed on each node.  The ASF and cassandra-builds lists are cached in the profile directory.  `cassandra list --refresh` fetches them again.
- **Removed:** `packer/cassandra/cassandra_versions.yaml`, `/etc/cassandra_versions.yaml`, the merge of the packaged yaml with the profile files (`DeclaredVersions`, the bake step in `Packer.kt`), the `lazy` flag, the unused `axonops`, `jvm_options`, `java_build` and `jvm_config` fields, `set-java-version`, `ClusterState.versions`, and the workspace copy of the yaml and its backup entry.  The profile `cassandra_versions/` directory stays.
- Fix the stale comment in `packer/cassandra/cassandra.pkr.hcl` that says `s3_bucket` is unused.
- Docs: `docs/user-guide/installing-cassandra.md`, `docs/reference/commands.md`, `docs/user-guide/tutorial.md`, the packer docs and the `CLAUDE.md` files that name the yaml or the install scripts.

## Capabilities

### New Capabilities

None.  All behaviour is in existing capabilities.

### Modified Capabilities

- `cassandra`: on-demand install in `cassandra use`; the version sources, with the named builds of the profile; `cassandra install --reinstall`; the JDK and Python choice; the version record in `state.json`; `cassandra list` with sources and the profile cache; on-demand AxonOps agents and `cassandra start --skip-axonops`; the scripts take explicit arguments; the yaml, the `lazy` flag and the node version file are removed.
- `ami-building`: the Cassandra AMI holds no Cassandra version and no per-release AxonOps agent.
- `cassandra-local-builds`: a published build's manifest records its supported JDKs; an install records the build in `state.json`, not in the node's yaml; name resolution no longer starts from declared versions.
- `cassandra-branch-publishing`: a published tarball installs with `cassandra install --url`, not through the yaml.

## Impact

- Node scripts: `packer/cassandra/bin/install-cassandra-version`, `use-cassandra`, `set-java-version` (deleted), `packer/cassandra/install/install_cassandra.sh`, `install_axon.sh`, a new `install-axonops-agent`, `packer/cassandra/lib/edl-cassandra-agents.sh`, `packer/cassandra/cassandra.in.sh`, `packer/base/install/edl-cache-lib.sh`, `packer/cassandra/cassandra.pkr.hcl`, `packer/cassandra/patch-jvm-options.py`, and their `*.test.sh` files.
- Kotlin: `commands/cassandra/UseCassandra.kt`, `CassandraInstall.kt`, `ListVersions.kt`, `Start.kt`, `DeclaredVersions.kt` (deleted), `configuration/ClusterState.kt`, `CassandraVersion.kt`, `CassandraBuildManifest.kt`, `ClusterS3Path.kt`, `services/CassandraBuildCatalog.kt`, `CassandraBuildService.kt`, `ClusterBackupService.kt`, `commands/Up.kt`, `Clean.kt`, `containers/Packer.kt`, `server/ClusterStatusCollector.kt`, `events/Event.kt`, `Constants.kt`, and new services for version resolution, the source lists and the AxonOps agent.
- CI: `.github/cassandra-image/resolve-build-plan.test.sh` loses its yaml check; `.github/workflows/packer-test.yml` and the Gradle script-test tasks follow the script changes.
- External: reads `https://archive.apache.org/dist/cassandra/`, `https://dlcdn.apache.org/cassandra/`, and the GitHub release API of `rustyrazorblade/cassandra-builds`.  It reads the cassandra-builds manifest from rustyrazorblade/cassandra-builds#1 when that manifest exists.
- #1158 (per-node `up` with node replacement): a replaced db node gets its active version, JDK and Python from `state.json` through a service that this change adds, and installs only that version.  #1158 calls it; this change does not depend on #1158 code.
- Gates: `requires-aws` and `requires-ami-bake`.  A base and Cassandra AMI bake is necessary (`build-image`), because the cache helper is in the base AMI.
- Out of scope: removing the Cassandra AMI (#1131), removing the easy-db-lab build workflows and releases (#1132), moving the version list to cassandra-builds (rustyrazorblade/cassandra-builds#1), node replacement (#1158), and a GCP image or adapter (#1062).
