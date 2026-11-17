---
name: cassandra
description: Manage Cassandra lifecycle, versions, and configuration on a running cluster
---
# Cassandra

Start, stop, restart Cassandra; install and switch versions. Run from workspace dir after `up`.

Lifecycle:
1. `easy-db-lab cassandra start` — starts Cassandra on all db nodes. Waits for each to reach `UN` (up-normal).
2. `easy-db-lab cassandra stop` — stops Cassandra on all db nodes.
3. `easy-db-lab cassandra restart` — restart with `UN` wait. Use after config changes.

Version management:
- The AMI has no Cassandra version installed; versions install on demand.
- `easy-db-lab cassandra list` — versions you can install (profile builds, ASF, cassandra-builds nightlies, `cassandra build` builds) and, in a workspace, the ones on each node. `--refresh` refetches the ASF and cassandra-builds lists. Works with no cluster.
- `easy-db-lab cassandra use <version>` — installs the version where it is missing, then switches to it. Examples: `5.0` (latest 5.0 release, JDK 17), `4.1.7`, `5.0-HEAD`, `trunk`. `--java N` picks another supported JDK.
- `easy-db-lab cassandra install <version>` — installs without switching. `--reinstall` (Cassandra stopped) downloads a nightly again.
- Named builds: yaml files in `<profile>/cassandra_versions/` with `version` and `url` (and optional `branch`, `java_supported`, `java`, `python`, `ant_flags`).
- `easy-db-lab cassandra start --skip-axonops` — start without the AxonOps agent when AxonOps ships none for the release and JDK.
- To build a local source tree and run it on a cluster, see the `cassandra-builds` topic.

Config (patch-file workflow on a running cluster):
1. `easy-db-lab cassandra write-config` — generate an override-only patch file. Set tokens with `-t N`.
   - Caveat: always writes `cassandra.patch.yaml` and overwrites any existing one — re-running discards hand edits. Copy it aside first to keep them.
2. Edit the patch file; keep only overridden keys.
3. `easy-db-lab cassandra update-config` — push the merged config to all db nodes. `update-config` takes the patch filename as its argument (default `cassandra.patch.yaml`). Flags: `--restart`/`-r` (apply and restart), `--hosts db0,db1` (comma-separated subset).
4. Without `--restart`: `easy-db-lab cassandra restart`.

Update one node only:
- Keep the per-node overrides in their own file, then target that node by name:
  ```
  cp cassandra.patch.yaml db1.patch.yaml   # edit db1.patch.yaml for the one node
  easy-db-lab cassandra update-config db1.patch.yaml --hosts db1
  ```

Pull current config:
- `easy-db-lab cassandra download-config` — download JVM and other conf files to the workspace.
  - Caveat: pulls from the first Cassandra node only, and won't overwrite an existing local copy (skips if already downloaded). Excludes `cassandra.yaml`.

Load:
- See `stress-testing` topic for `cassandra stress` workloads.

Related: `stress-testing`, `provisioning`, `cassandra-builds`.
