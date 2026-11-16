---
name: querying
description: Run queries against the database
---
# Querying

Execute queries interactively or one-shot. Run from the workspace dir after the database starts.

Interactive:
- Cassandra: `easy-db-lab cassandra cql` — cqlsh on db0.
- Kits with SQL: `easy-db-lab <kit> sql` — SQL shell over JDBC. SOCKS-tunneled when endpoint is cluster-private.

One-shot:
- `easy-db-lab <kit> sql "SELECT ..."` — run query and print results.
- `easy-db-lab cassandra cql "SELECT ..."` — CQL equivalent.

Notes:
- Kit endpoints from `easy-db-lab kit info <name>` or `easy-db-lab status`.
- `sql` command auto-detects SOCKS tunnel requirement; no manual configuration needed.

Related: `connecting`, `kits`.
