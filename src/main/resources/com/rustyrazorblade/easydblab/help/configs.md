---
name: configs
description: Edit the database configuration and apply it across the cluster
---
# Configuration

Change db config on a running cluster via patch files. Commands target the `cassandra` db node group. Run from the workspace dir.

Steps:
1. `easy-db-lab cassandra write-config` — generate a patch file (override-only). Set tokens with `-t N`.
2. Edit the patch file; keep only overridden keys.
3. `easy-db-lab cassandra update-config` — push merged config to all db nodes. Flags: `--restart` (apply and restart), `--hosts db0,db1` (subset).
4. If you did not use `--restart`: `easy-db-lab cassandra restart`.

Other:
- `easy-db-lab cassandra download-config` — pull current on-node config back to the workspace.

Related: `provisioning`, `stress-testing`.
