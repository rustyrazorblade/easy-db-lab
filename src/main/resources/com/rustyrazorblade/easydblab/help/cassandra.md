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
- `easy-db-lab cassandra list` — available versions (pre-baked in AMI + installable on demand).
- `easy-db-lab cassandra install <version>` — downloads tarball, installs on all db nodes. Examples: `5.0.2`, `5.0-beta1`, `trunk`.
- `easy-db-lab cassandra use <version>` — switches active version. Must already be installed.

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

Related: `stress-testing`, `provisioning`.
