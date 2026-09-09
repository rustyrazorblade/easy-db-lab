---
name: provisioning
description: Create a cluster, launch its AWS infrastructure, and tear it down
---
# Provisioning

Clusters are ephemeral: create, use, destroy.

Prerequisite (once): `easy-db-lab profile setup`.

Steps:
1. Make a workspace dir and `cd` into it. Run all commands for the cluster from there; the tool writes `state.json`, `env.sh`, `sshConfig`, `kubeconfig` to the current dir.
2. `easy-db-lab init <name>` — writes local config only, no AWS resources. Default: 3 db nodes. Size with `--db.count N --app.count N`.
3. `easy-db-lab up` — creates VPC, EC2 instances, K3s, observability. Fails fast; on failure it stops and leaves launched instances running (no rollback). Combine: `init <name> --up`.
4. `easy-db-lab status` / `easy-db-lab hosts` — verify; `hosts` lists node aliases.
5. `easy-db-lab down --auto-approve` — destroy everything. Run from the same workspace dir.

Notes:
- Db nodes need a data disk: use an NVMe instance type (`d` suffix, e.g. `i4i.xlarge`) or attach EBS with `--ebs.type`; otherwise `up` fails.

Related: `cassandra`, `kits`, `stress-testing`.
