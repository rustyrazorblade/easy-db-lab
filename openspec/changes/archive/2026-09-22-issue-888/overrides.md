# Overrides and conflicts

## Overrides existing behavior

### workload-runner: Installed workload dirs are discovered as top-level subcommands

**Currently:** "At startup, the CLI SHALL scan `context.workingDirectory` for subdirectories that
contain a `bin/` directory. Each such directory SHALL be registered as a top-level PicoCLI
subcommand whose sub-subcommands are the executable files found in `bin/`."

Its scenario "Dir without bin/ is not registered" asserts: WHEN `./clickhouse/` exists but has no
`bin/` subdirectory, THEN `easy-db-lab clickhouse` is not registered.

**This change:** the scan is for subdirectories containing a `kit.yaml` descriptor, and a `bin/`
directory explicitly does not qualify a directory. The scenario is replaced by its inverse —
a directory with an executable `bin/` script and no `kit.yaml` is not registered.

This is the substance of the change, and it is a **correction of an error, not a reversal of
intent**. The owner confirms the `bin/` rule was a careless mistake when the spec was written. Two
scenarios that asserted the defect are rewritten, and one new scenario covers a directory holding
neither marker.

### workload-runner: Discovered workloads appear in --help output

**Currently:** the scenario keys on `./clickhouse/bin/` existing.

**This change:** it keys on `./clickhouse/kit.yaml` existing, and a second scenario asserts a
`bin/`-only directory is absent from the listing. The requirement sentence itself is unchanged.

### kit-install-command: Kit descriptor filename

**Currently:** `kit.yaml` is named as the descriptor the loader looks for, across classpath,
profile directory, and ad-hoc `--from` sources. Nothing states that an installed kit directory must
carry one.

**This change:** adds the invariant — every kit directory installed in the workspace contains a
`kit.yaml`, no exceptions; the descriptor is the sole marker of an installed kit, and its presence
is a property of installation rather than of any particular template. Kits registered in code are
excluded, because they are never discovered from the filesystem.

It also adds enforcement: `kit install --from <dir>` rejects a template directory with no
`kit.yaml`. **This changes behavior that works today** — such an install currently succeeds, and the
resulting kit registers because its `bin/` satisfies the old predicate. After this change the
install fails with a message naming the missing file. That is deliberate: without it the invariant
would be stated and unenforced, and the install would silently produce a kit that registers nothing.

The three existing scenarios are unchanged; three are added.

## Conflicts with other in-flight changes

- `kit-node-type-requirement` also touches `kit-install-command` — **no conflict.** It adds a new
  requirement, "Kit node-type requirement field", about a `type:` field inside `kit.yaml`. This
  change modifies "Kit descriptor filename". Different requirements, and the two are complementary:
  one says the descriptor must exist, the other describes a field within it.

- `kit-subcommand-restructure` also touches `kit-install-command` — **no conflict.** It modifies
  "Dynamic install subcommands registered under `kit install`" and removes the `install --list` flag
  and `install` as a top-level command. That is about where the *install* subcommands live in the
  command tree. This change is about which *workspace directories* register as kit subcommands.
  Neither touches a requirement the other touches.

- No other open change touches `workload-runner`.
