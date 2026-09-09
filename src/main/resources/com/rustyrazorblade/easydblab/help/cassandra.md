---
name: cassandra
description: Manage Cassandra lifecycle and versions on a running cluster
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

Config:
- `easy-db-lab cassandra write-config` — generates patch file. See `configs` topic.
- `easy-db-lab cassandra update-config [--restart]` — applies config, optionally restarts. See `configs` topic.

Load:
- See `stress-testing` topic for `cassandra stress` workloads.

Related: `configs`, `stress-testing`, `provisioning`.
