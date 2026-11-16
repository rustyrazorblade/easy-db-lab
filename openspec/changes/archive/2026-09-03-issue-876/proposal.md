## Why

Testing an experimental Cassandra build (a git branch, or a tarball from the existing
`build-cassandra-ref.yml` Action, #729) currently requires adding it to `cassandra_versions.yaml`
and baking an entirely new AMI. `install_cassandra_version()`
(`packer/cassandra/install/install_cassandra.sh:138-301`) already knows how to install a single
version — download tarball, or git-clone+ant-build — but it is only ever invoked from a loop over
every entry in `/etc/cassandra_versions.yaml`, gated by `INSTALL_CASSANDRA=1` and run exactly once,
at packer bake time. There is no way to install one additional version onto a node that is already
running.

`cassandra use <version>` (`UseCassandra.kt`) only switches which pre-baked version is active on a
node — it never installs anything, and today it does so unconditionally (`use-cassandra:6`'s
`ln -vfns` has no existence check), leaving a dangling symlink and a confusing later failure when
pointed at a version that was never installed. (GitHub issue #876.)

## What Changes

- **One script, used identically at bake time and at runtime — both existing install modes
  preserved unchanged.** Extract `install_cassandra_version()`'s full logic (official-release
  download-by-prefix, arbitrary tarball `url:`, and git `url:`+`branch:` clone-and-`ant`-build — all
  three, nothing removed) into a new standalone, flag-driven script,
  `packer/cassandra/bin/install-cassandra-version`, baked onto the AMI the same way
  `use-cassandra`/`set-java-version` already are. It takes `<version>` plus `--url`, `--branch`,
  `--java`, `--ant-flags` as needed for whichever mode applies, downloads/builds to
  `/usr/local/cassandra/<version>`, and applies the same post-install configuration every version
  needs (conf backup, `cassandra.in.sh` append, chown, cqlsh removal for 2.x/3.x, JDK selection from
  the `--java` flag rather than the node's current default for the build path).
  **`install_cassandra.sh`'s bake-time loop is rewritten to call this same script, with the same
  flags, instead of running its own inline copy of the logic** — one code path, not two kept in sync
  by hand. This is a refactor of *how* the existing logic is invoked, not a reduction of what it
  can do.
- **New `cassandra install <version>` subcommand** (`CassandraInstall.kt`, following
  `UseCassandra.kt`'s shape): installs one version onto an already-provisioned, running cluster —
  all Cassandra nodes, or a `--hosts`-filtered subset — by invoking the same
  `install-cassandra-version` script remotely, with the same flags the bake-time loop uses. It
  works generically with whatever mode a resolved version's entry declares (tarball or git branch)
  — no special-casing to allow one mode and block the other. In practice, expected day-to-day usage
  is tarball-only (installing pre-built artifacts, e.g. from `build-cassandra-ref.yml`, #729), but
  that is a usage pattern, not a code-level restriction.
- **Version resolution, two ways:**
  - By default, resolves the version's `java`/`python`/`url`/`branch`/`ant_flags` from a declared
    `cassandra_versions.yaml` entry — the same merged candidate set
    (`CassandraVersion.loadFromMainAndExtras`, main file + profile `cassandra_versions/` extras
    directory) `packer/cassandra/cassandra.pkr.hcl`'s non-release build already resolves for AMI
    bakes. Declaring a version here (optionally `lazy: true`) is what makes it discoverable via
    `cassandra list` before it's ever installed.
  - `--url`, `--branch`, `--java`, `--python`, `--ant-flags` CLI options let an operator install a
    version with **no yaml edit at all** — a true zero-file-edit path for a one-off test. A
    CLI-flag install is not yaml-declared, so it will not appear in `cassandra list` as a
    lazy-declared version; it exists once actually installed and reported per host.
  - `--java`/`--python` are validated non-blank before any host is touched; `--python` defaults to
    `3.11.9` (matching every existing declared entry) when not supplied, since `use-cassandra`
    hard-exits without it and it is missing from the issue's own stated schema-fields list — this
    default closes that gap.
- **Push the resolved entry into `/etc/cassandra_versions.yaml` on each targeted node** before
  installing — download the node's current file, parse via the existing typed `CassandraVersion`
  loader, skip (idempotent no-op, "already present") if the version is already there, otherwise
  append and re-upload the whole file via the existing typed `CassandraVersion.write()`. This is
  the first push-up path for a file that today is baked in once and only ever read down
  (`Up.kt:631-647`).
- **`lazy: true` field on a `cassandra_versions.yaml` entry**: ships to every node's
  `/etc/cassandra_versions.yaml` at bake time exactly as today (the whole file is copied
  unconditionally — no change needed there), but the bake-time install loop skips actually running
  `install_cassandra_version` for it, costing nothing at AMI-build time.
- **`cassandra list` extended** to show lazy-declared-but-not-yet-installed versions (resolved via
  the same `loadFromMainAndExtras` candidate set), distinguishable from versions actually installed
  on the queried node.
- **`cassandra use <version>` fails loudly** when the version is not installed on the targeted
  node, instead of silently succeeding with a dangling symlink — fixes the missing existence check
  in `use-cassandra`.
- **Fix `HostOperationsService.withHosts(parallel = true)`** to stop silently swallowing exceptions
  raised inside a per-host action thread. Today an uncaught exception in a parallel host action
  just kills that thread; `.join()` doesn't rethrow, so the command reports success regardless.
  `cassandra install` needs real per-host failure reporting (an AC of this issue), and this gap
  affects every existing `parallel = true` caller (`UseCassandra`, `SetupInstance`, `ExecStop`,
  `ExecList`, `ExecRun`) — fixed once, at the shared helper, rather than worked around locally.
- Update `openspec/specs/cassandra/spec.md` (`REQ-CA-001`, and a new "Runtime Version Installation"
  requirement) and relevant `docs/`.

## Capabilities

### Modified Capabilities

- `cassandra`: `REQ-CA-001` no longer requires multi-version support to live exclusively "on the
  same AMI" — a version can also be installed onto a running node at runtime. Adds a new "Runtime
  Version Installation" requirement, and a new scenario under `REQ-CA-001` covering `cassandra use`
  against an uninstalled version.

### New Capabilities

<!-- None. This extends the existing `cassandra` capability; no new top-level capability. -->

## Impact

- **New files:** `packer/cassandra/bin/install-cassandra-version`,
  `src/main/kotlin/.../commands/cassandra/CassandraInstall.kt`.
- **Modified files:** `packer/cassandra/install/install_cassandra.sh` (bake-time loop rewritten to
  call the new script — same logic, same both install modes — instead of its own inline copy;
  `lazy: true` skip),
  `packer/cassandra/bin/use-cassandra` (existence check before the symlink swap),
  `packer/cassandra/cassandra_versions.yaml` (`lazy` field support),
  `configuration/CassandraVersion.kt` (`lazy: Boolean = false` field), `ListVersions.kt`,
  `HostOperationsService.kt` (parallel exception propagation), `Cassandra.kt` (subcommand
  registration).
- **Out of scope (decided at grooming, do not relitigate):** no CI orchestration (no
  triggering/polling `build-cassandra-ref.yml`); no persistence of a runtime-installed version
  across cluster teardown/recreate; no wiring into AMI rebuild or containerized cluster mode; no
  changes to `build-cassandra-ref.yml` itself (#765, unrelated); no `--force`/reinstall support.
- **Related, already shipped:** #729 (`build-cassandra-ref.yml` — this issue's `--url`/tarball mode
  consumes its output). #763/#775/#821/#824 (hand-wired specific nightly versions into
  `cassandra_versions.yaml`) — this issue generalizes that into a reusable command.
