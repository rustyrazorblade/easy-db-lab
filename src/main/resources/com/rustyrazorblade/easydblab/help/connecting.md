---
name: connecting
description: Access cluster nodes and database endpoints
---
# Connecting

Get endpoints and open shells on running cluster nodes. Run from the workspace dir after `up`.

Steps:
1. `easy-db-lab status` — prints URLs (Grafana, Victoria backends) and node counts. Control node IP listed first.
2. `easy-db-lab hosts` — lists node aliases (db0, db1, app0, ...). Use these in other commands.
3. SSH access: `easy-db-lab exec run <host-alias> -- <command>` — run one-shot commands on a host. Interactive shell: `ssh -F sshConfig <host-alias>`.

Database access:
- Cassandra: `easy-db-lab cassandra cql` — opens cqlsh on db0.
- Other databases: `easy-db-lab <kit> sql` after kit start. SQL-over-JDBC; SOCKS-tunneled when the endpoint is cluster-private.

Notes:
- `sshConfig` written during `up`, lists every alias. Stays valid until `down`.

Related: `querying`, `observability`, `provisioning`.
