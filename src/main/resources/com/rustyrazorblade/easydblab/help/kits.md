---
name: kits
description: Install and run a packaged workload such as ClickHouse, Presto, or TiDB
---
# Kits

A kit packages a workload's full lifecycle in `kit.yaml` (no K8s YAML). Built-in: ClickHouse, Presto, Trino, TiDB, sysbench. Run from the workspace dir after `up`.

Steps:
1. `easy-db-lab kit list` — available kits. `easy-db-lab kit info <name>` — args, endpoints, commands.
2. `easy-db-lab kit install <name> [args]` — args are per-kit (see `kit info` or `--help`). Writes files to the workspace and registers the kit's commands automatically.
3. `easy-db-lab <name> start` / `easy-db-lab <name> stop` — each script in the kit is a subcommand. A Grafana dashboard installs after a successful start.
4. `easy-db-lab kit uninstall <name>` — remove.

Bench kits (load against a running db kit): install with `--target <db-kit>`; installs to dir `<bench-kit>-<target>`, so several targets run at once.
```
easy-db-lab kit install sysbench --target tidb
easy-db-lab sysbench-tidb prepare
easy-db-lab sysbench-tidb start
easy-db-lab sysbench-tidb stop
```

Related: `provisioning`, `stress-testing`.
