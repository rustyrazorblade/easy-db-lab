## Overrides existing behavior

### account-compactor: One compactor service runs per account
**Currently:** At most one ECS Fargate service `easy-db-lab-compactor` runs per account, with 1 task, in the bucket's region and the `easy-db-lab-compactor` VPC.  It runs the Mimir, Loki and Tempo compactors and logs to the CloudWatch group `/easy-db-lab/compactor`.
**This change:** Removed.  The new requirements "Every cluster runs a standby compactor on its control node" and "Exactly one compactor per store holds the lease" replace it: every cluster runs an `account-compactor` pod, and a lease in the object store makes one of them active.  Logs go to the cluster's Loki.

### account-compactor: The compactor compacts and never deletes data by retention
**Currently:** The settings are checked on the ECS task definition and its config files.
**This change:** The same settings, checked on the compactor Deployment from the manifest builder.  The keep-every-sample scenario also says that no object is removed unless its data is in the merged output.

### account-compactor: Only the compactor's task role can delete observability objects
**Currently:** The task role `EasyDBLabCompactorTaskRole` is the only principal that may delete under `mimir/`, `loki/` and `tempo/`; an execution role exists, and the user policies manage both.
**This change:** Removed, no replacement role.  The in-cluster compactor uses the instance role; the lease limits deletes to the one active compactor.

### account-compactor: The compactor is controlled by hand
**Currently:** `observability compactor start`, `stop` and `status`; `status` shows the ECS service, task and log lines.
**This change:** Removed.  The new requirement "Compactor status reads the lease" keeps only `status`, which prints the lease.  `start` and `stop` no longer exist.

### account-compactor: The compactor's network stays out of cluster teardown
**Currently:** The `easy-db-lab-compactor` VPC is kept by `down --all` and is not counted as a cluster.
**This change:** Removed, no replacement.  `down --all` removes the old VPC with the other tagged VPCs.

### observability-store: No observability backend deletes data automatically
**Currently:** Tempo on a cluster disables compaction and retention for every tenant.  The account compactor compacts with retention off.
**This change:** Tempo on a cluster also sets `block_retention: 876000h`.  The account compactor bullet says it runs in its own pod on every cluster and works only while it holds the lease.

### observability-store: The cluster cannot delete observability objects
**Currently:** The instance role denies deletes under `mimir/`, `loki/`, `tempo/`, `grafana/` and `reports/`.  The bucket policy denies deletes under `mimir/`, `loki/` and `tempo/` for the instance role and both EMR roles.  Only the compactor's task role deletes under the compacted roots.
**This change:** The instance role denies deletes only under `grafana/` and `reports/`.  The bucket policy denies deletes under the compacted roots only for the EMR roles.  Only the lease-holding compactor deletes under the compacted roots.  The scenario "Only the compactor deletes while a cluster runs" is replaced by "The lease holder can remove merged sources", and "A backend delete is denied" is narrowed to `grafana/`.

### observability-store: Mimir reads the whole shared store
**Currently:** Scenario "Queries succeed while the compactor is stopped": the compactor service stopped for more than 1 hour.
**This change:** The same scenario, now "while no compactor holds the lease".  The requirement text notes that the account compactor runs in its own pod.

### cluster-lifecycle: Cluster Teardown
**Currently:** The scenario "Teardown of all clusters" keeps the `easy-db-lab-compactor` VPC.
**This change:** That clause is removed.  A new scenario, "down does nothing about the compactor", says that `down` only flushes, waits and refuses on a failed flush, and does not count clusters, stop a compactor or touch the lease.

### cluster-lifecycle: up makes sure the account compactor runs
**Currently:** `up` starts the Fargate service when it is not running, on every cluster.
**This change:** Removed.  `up` deploys the in-cluster compactor as part of the observability stack, as the `account-compactor` capability specifies.

### cluster-lifecycle: down stops the account compactor after the last cluster
**Currently:** After teardown, `down` counts the remaining clusters that name the bucket and stops the service when none is left.
**This change:** Removed, no replacement.

### networking: Cluster rule changes are reported once
**Currently:** The rule applies to the cluster, Packer and account-compactor security group paths.
**This change:** The account-compactor security group path is removed; the rule applies to the cluster and Packer paths.

## Conflicts with other in-flight changes

None found.  `openspec/changes/` holds no other open change.
