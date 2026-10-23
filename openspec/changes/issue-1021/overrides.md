# Overrides for issue-1021

## Overrides existing behavior

### kit-capabilities: sql capability type (MODIFIED)

**Currently:** "The `sql` capability SHALL accept an optional `user` (JDBC username, defaulting to empty string) and an optional `driver-class` (fully-qualified JDBC driver class to force-load before connecting, required for drivers that do not auto-register via ServiceLoader)." There is no password. The `sql` command always connects with no password.

**This change:** The capability also accepts an optional `password` (default empty). The `sql` command sends it as the JDBC `password` property only when it is not blank. No output of the command, success or failure, contains it. Two scenarios are added: "Password is sent when set" and "Blank password is not sent". All existing scenarios are kept.

### kit-cross-targeting: TARGET_* env vars injected at start time (MODIFIED)

**Currently:** `jdbc` endpoints inject `TARGET_JDBC_URL`, `TARGET_JDBC_USER`, `TARGET_JDBC_DRIVER`. `postgresql` endpoints inject `TARGET_PG_HOST`, `TARGET_PG_PORT`, `TARGET_PG_USER`, `TARGET_PG_DATABASE`. `mysql` endpoints inject `TARGET_MYSQL_HOST`, `TARGET_MYSQL_PORT`, `TARGET_MYSQL_USER`, `TARGET_MYSQL_DATABASE`. No password variable exists.

**This change:** Each of the three types also injects `TARGET_JDBC_PASSWORD`, `TARGET_PG_PASSWORD`, or `TARGET_MYSQL_PASSWORD` from the target's `sql` capability `password`, empty when there is none. The framework never prints it. The three existing endpoint scenarios list the new empty variable, and a scenario "Password from the target sql capability" is added. A new ADDED requirement, "sysbench authenticates with the target password", covers sysbench.

### workload-status-command: Status command shows connection endpoints (MODIFIED)

**Currently:** "The status command SHALL display connection endpoints declared in `install.yaml` with private IPs resolved from cluster state." Scenarios cover `http`, `jdbc`, `native`/`cql`, multiple nodes, and no endpoints. The installed descriptor is copied with only a port patch, so a `__VAR__` placeholder would show as is.

**This change:** The status command displays endpoints from the installed `kit.yaml`, which is now rendered at install time, so it shows installed values such as the database name. It never prints a capability password. Two scenarios are added: "MySQL and PostgreSQL endpoints displayed" and "Installed database name displayed". All existing scenarios are kept.

### kit-uninstall-command: Running uninstall executes the kit uninstall phase then removes the local directory (MODIFIED)

**Currently:** "Running `easy-db-lab uninstall <kit>` SHALL execute the kit's uninstall phase. On success, the local kit directory SHALL be deleted." The spec says nothing about a running kit. (The code already runs `stop` first through `KitRunnerCommand.stopsBeforeUninstall`; the spec did not record it.)

**This change:** When the kit is in `runningKits` and has a `stop` phase, uninstall runs `stop` first. A failed `stop` blocks the uninstall phase and exits non-zero. Two scenarios are added: "Running kit is stopped first" and "Failed stop blocks uninstall". The existing scenarios are kept.

### ADDED requirements that change existing behavior

These are ADDED requirements, not MODIFIED ones, but they change what an existing path does:

- kit-install-command, "Installed kit.yaml is rendered at install time": **Currently** the installed `kit.yaml` is the source file with only `port:` lines patched (`BaseInstallCommand.patchKitYaml`). **This change** also runs `__VAR__` substitution on it. A `kit.yaml` with no placeholder installs unchanged.
- kit-install-command, "kit info renders placeholders with arg defaults": **Currently** `kit info` shows the packaged `kit.yaml` as is. **This change** renders placeholders with arg defaults first.
- kit-install-command, "Install arg pattern validation": **Currently** an install arg accepts any value. **This change** adds an optional `pattern`. An arg without one behaves as today.
- k8s-container-log-collection, "Pod logs are stored on the data mount": **Currently** the runtime K3s scripts leave `/var/log/pods` on the root volume. **This change** links it to `/mnt/db1/container-logs` when `/mnt/db1` is mounted.
- instance-storage-validation, "Database data and logs never on the root volume": **Currently** no requirement states where data and logs live. **This change** states it, with TiDB and SingleStore scenarios.

## Conflicts with other in-flight changes

None found.

Checked `issue-1000`, `issue-1018`, `issue-966`, `issue-970`, `issue-971`, `issue-992`, `ssm-packer-builds`, and `ssm-ssh-transport`. None of them has a delta for `kit-capabilities`, `kit-cross-targeting`, `kit-install-command`, `workload-status-command`, `kit-uninstall-command`, `k8s-container-log-collection`, `instance-storage-validation`, or `singlestore-kit`.

One related, non-conflicting item: `issue-971` (`specs/server/spec.md`) makes the cluster-wide `status` command and the server status list each running kit's endpoints from its installed `kit.yaml`. This change renders that file at install time, so `issue-971` reads resolved values. Neither change alters the other's requirements.
