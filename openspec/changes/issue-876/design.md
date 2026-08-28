## Context

Today, testing an experimental Cassandra build means either adding an entry to
`cassandra_versions.yaml` and baking a whole new AMI (tens of minutes, touches every future
cluster), or manually SSHing in and improvising. `install_cassandra_version()`
(`packer/cassandra/install/install_cassandra.sh:138-301`) already contains all the logic to install
one version — it's just trapped inside a bake-time-only loop.

Two things discovered while reading the actual code (not just the groomed issue text) materially
changed the shape of this design:

1. **The issue's own "two drifted `cassandra_versions.yaml` files" concern is stale.** Only one
   file exists today: `packer/cassandra/cassandra_versions.yaml`. The repo-root copy the issue
   references no longer exists. Nothing to reconcile.
2. **A typed load/merge/write mechanism for this exact file already exists and is already used at
   AMI-bake time**, just not exposed to a live cluster. `Packer.kt:225-231` calls
   `CassandraVersion.loadFromMainAndExtras(mainFile, context.cassandraVersionsExtra)` for
   non-release builds — merging the committed `cassandra_versions.yaml` with any `*.yaml` files an
   operator drops into their profile's `cassandra_versions/` extras directory, deduping by
   `version` and erroring on a genuine collision (`DuplicateVersionException`). `CassandraVersion`
   (Jackson + YAML, already carries `url`/`branch`/`antFlags`/`java`/`python`) has a tested
   `write()`. Reusing this exact mechanism for `cassandra install`'s version resolution means "what
   can be baked into an AMI" and "what's installable live" are always the same candidate set — no
   new config surface to maintain in parallel.

A parallel Cassandra-domain consult surfaced several mechanics that shape the extraction:

- `use-cassandra` (`packer/cassandra/bin/use-cassandra:20-28`) hard-exits if `java`/`python` are
  empty for the target version — and the node's `/etc/cassandra_versions.yaml` is frozen at bake
  time. A version installed at runtime that doesn't push its `java`/`python` fields into that file
  breaks a later `cassandra use` confusingly, not loudly.
- The git-branch install path (`url:`+`branch:`) is dead code today — zero entries in the current
  `cassandra_versions.yaml` use it. **This change does not remove it** — the owner was explicit
  that both install modes must keep working; nothing about extracting the function into a
  standalone script should artificially restrict which mode `cassandra install` can drive. In
  practice, day-to-day usage of the new command is expected to be tarball-only (installing
  pre-built artifacts, e.g. from `build-cassandra-ref.yml`, #729), but that's a usage pattern, not
  a code-level restriction — this design does not special-case one mode to block the other. It
  does mean the git-branch path gets its first real production exercise through whichever caller
  (bake time or `cassandra install`) first uses it against a real entry; budget time to debug it.
- JDK selection for the build path must come from the target version's own `java:` field per
  invocation, not the node's current default JDK alternative, and must not call
  `update-java-alternatives` as a side effect (that would disturb whatever version is currently
  active on the node).
- The bake-time script does several things beyond fetch+build that any install must reproduce or
  the node ends up silently degraded: appending `/tmp/cassandra.in.sh` into the version's
  `bin/cassandra.in.sh` (Pyroscope/MAAC/GC-logging/log-dir config), `cp -R conf conf.orig`,
  `chown -R cassandra:cassandra`, and cqlsh removal for 2.x/3.x. It must *not* reproduce `rm -rf
  ~/.m2` — that's already a global, once-per-bake cleanup that runs *after* the version loop in
  `install_cassandra.sh` today, not inside `install_cassandra_version()` itself; the extracted
  script preserves that separation, so a runtime install never touches `~/.m2` and bake time keeps
  cleaning it up exactly once, same as today.

Separately, reading `HostOperationsService.withHosts` for the per-host reporting this issue's
acceptance criteria require surfaced a latent gap unrelated to Cassandra specifically: its
`parallel = true` path fires each host's action on its own thread and just `.join()`s them — an
uncaught exception inside that thread dies silently; `.join()` never rethrows, so the command
reports success regardless of what happened on any individual host. Every existing `parallel =
true` caller (`UseCassandra`, `SetupInstance`, `ExecStop`, `ExecList`, `ExecRun`) has this gap
today, but none of them currently need per-host failure reporting the way `cassandra install`'s
ACs explicitly do. Fixed at the shared helper rather than worked around locally in
`CassandraInstall`, per owner decision (fold into this change rather than a separate issue).

## Goals / Non-Goals

**Goals:**

- Install one additional Cassandra version onto an already-running cluster without an AMI rebuild.
- Extract the existing install logic — both modes, unchanged in capability — into one place used
  identically by the bake-time loop and the new runtime command, rather than duplicating it.
- Make `/etc/cassandra_versions.yaml` push-up type-safe, reusing the existing `CassandraVersion`
  load/write machinery rather than building YAML mutations from raw Kotlin strings.
- Make `cassandra use` against an uninstalled version fail clearly instead of silently leaving a
  dangling symlink.
- Make per-host failure reporting for parallel host operations actually work, for `cassandra
  install` and every other `parallel = true` caller.

**Non-Goals** (decided at grooming, do not relitigate in this change):

- No CI orchestration — `cassandra install` never triggers or polls `build-cassandra-ref.yml`.
- No persistence of a runtime-installed version across cluster teardown/recreate.
- No wiring into AMI rebuild or containerized cluster mode.
- No `--force`/reinstall-in-place support — re-running install for an existing version is a no-op.
  (A parallel domain consult noted this collides somewhat with iterative branch testing — pushing a
  new commit to the same branch has no way to be picked up short of a new version name. Flagged for
  awareness; the exclusion itself stands as already decided.)

## Decisions

### D1: Version-source resolution — declared entry, with CLI-flag override

Two ways to name a version's install parameters:

1. **Declared** (default): `cassandra install <version>` resolves `java`/`python`/`url`/`branch`/
   `ant_flags` via `CassandraVersion.loadFromMainAndExtras`, the same call `Packer.kt` already
   makes for non-release AMI builds. An operator declares the version once (optionally
   `lazy: true`, in the committed `cassandra_versions.yaml` or a personal
   `<profileDir>/cassandra_versions/*.yaml` extras file) and it becomes discoverable via
   `cassandra list` before it's ever installed anywhere.
2. **CLI-flag override**: `--url`, `--branch`, `--java`, `--python`, `--ant-flags` let an operator
   install without touching any yaml file — true zero-file-edit, useful for a genuinely one-off
   test. **Owner decision:** support both, CLI flags take precedence when supplied. A CLI-flag
   install does not appear in `cassandra list`'s lazy-declared set (nothing was declared), but shows
   up post-install like any other installed version.

`--python` was missing from the issue's own stated schema-fields list, but `use-cassandra`
hard-requires it (see Context) and every existing declared entry sets it. Default: `3.11.9`
(matching every current entry) when not supplied via CLI flag or a declared entry.

### D2: Push-up mechanism — typed download/merge/write, not `yq -i`

Alternative considered: a scoped `yq -i` mutation on the Kotlin side, mirroring
`set-java-version:27-31`'s existing precedent for in-place remote yaml edits.

**Chosen:** download the node's current `/etc/cassandra_versions.yaml`, parse via the existing
`CassandraVersion.loadFromFile`, and — when `<version>` is not already listed — append the resolved
entry and `CassandraVersion.write()` the whole list back, uploading it to overwrite the file. This
reuses existing, tested code and avoids constructing a `yq` expression from arbitrary (potentially
special-character-bearing) git URLs, branch names, and ant flags — which is exactly the raw-string
YAML construction this repo's conventions rule out in favor of typed data classes.

**Corrected during implementation review:** this design originally made "already present in the
node's `cassandra_versions.yaml`" the idempotency check. That is the wrong signal.
`cassandra.pkr.hcl` copies the *whole* committed `cassandra_versions.yaml` into every AMI's
`/etc/cassandra_versions.yaml`, `lazy: true` entries included — so a lazy version is always already
listed on a node that has never installed it, and the check made `cassandra install` a silent no-op
for exactly the versions this change exists to install. The idempotency check is instead whether
the version is on disk (`test -d /usr/local/cassandra/<version>`, matching the guard
`install-cassandra-version` already applies to itself); the push-up is purely input to the install.

**Refined during implementation review (three further corrections):**

1. **The declaration is never rolled back on a failed install.** An earlier revision of this design
   rolled the list back so the attempt could be retried. That is backwards. *Declared but not
   installed* is the harmless state — it is precisely what a `lazy: true` entry looks like, and the
   next attempt overwrites it anyway. *Installed but not declared* is the unrepairable one: every
   retry short-circuits on the on-disk check before it can re-declare, leaving `cassandra use`
   permanently unable to find the version's java/python.
2. **A mismatched declaration is reported, not overwritten.** When the version is already on disk
   *and* the node's own declaration disagrees with the flags just resolved (`java`, `python`,
   `ant_flags`), nothing is changed and the host is reported via
   `Event.Cassandra.VersionDeclarationMismatch`, which exits the command non-zero. Rewriting the
   declaration would make it describe a build that was never performed — the bits on disk were
   compiled against the JDK the node declares — and a later `cassandra use` would then select the
   wrong JDK. Changing the JDK an existing build *runs* under is `cassandra use --java`'s job, not
   install's. Where the version is on disk but undeclared entirely, there is no declaration to
   contradict and the missing entry is simply repaired.
3. **`url`/`branch` are stripped before the entry is written to the node.** `use-cassandra` reads
   only `java`/`python`, so those fields have no remote consumer — and a git URL routinely carries a
   token, which persisting would park in `/etc/cassandra_versions.yaml` indefinitely.

### D3: Script extraction — standalone flag-driven script, both modes preserved

`install_cassandra.sh`'s current structure has a hard boundary (its own header comment) between
pure function definitions and the effectful bake-time driver below it — the target function already
sits on the wrong side of that line for safe standalone sourcing. Extract
`install_cassandra_version` (plus its `download_cassandra_version` dependency and the
S3-cache-with-fallback sourcing) **unchanged in capability** into
`packer/cassandra/bin/install-cassandra-version`: baked onto the AMI like `use-cassandra`, taking
`<version>` plus `--url`/`--branch`/`--java`/`--ant-flags` as needed, safe to invoke directly over
SSH (from `cassandra install`) or from the bake-time loop. Bake-only global setup (user/directory
creation, installing cqlsh via `uv`, the initial pinned-JDK default) stays in `install_cassandra.sh`
and is not re-run at install time — re-running `useradd`, for one, would simply error.

`install_cassandra.sh`'s bake-time loop is rewritten to call this script per version (resolving
each version's fields from `/etc/cassandra_versions.yaml` via `yq`, same as it does today, just to
build the script's flags instead of driving inline logic), in the same
parallel-background-job shape it uses now — one code path, not two kept in sync by hand.

The extracted script diverges from the original inline function in exactly two ways, both required
by the domain findings above: it selects `JAVA_HOME` from the target version's own `java` field for
its own `ant` invocation (never the node's current default alternative, and never calls
`update-java-alternatives`), and — matching the current code's existing structure, not a new
behavior — it does not `rm -rf ~/.m2` itself; that cleanup stays a once-per-bake step in
`install_cassandra.sh`, run after the whole version loop completes, exactly as today.

`java`/`python` are relevant only to the git-build mode's JDK selection inside the script; they're
also separately pushed into the node's `/etc/cassandra_versions.yaml` by `CassandraInstall` (D2) so
`cassandra use` works afterward regardless of which install mode was used.

### D4: `HostOperationsService.withHosts` per-host result collection

`parallel = true` currently fires bare threads with no result channel. Fix: the action lambda's
per-host outcome (success, or the caught exception) is collected into a thread-safe structure and
returned to the caller, so a caller can decide how to report/fail. `CassandraInstall` is the first
caller to actually need this; existing callers (`UseCassandra`, etc.) keep their current
throw-on-failure behavior.

**As implemented:** a separate `collectFromHosts` returns one `HostResult` (host + `Result`) per
targeted host and throws nothing; `withHosts` is reimplemented on top of it and keeps its
abort-the-command contract. Two refinements came out of implementation review:

- `withHosts` previously lost every failure — the bare threads swallowed them — so it now rethrows
  the first with the remaining ones attached as suppressed exceptions. An operator fixing a
  multi-node problem sees all of it in one run rather than one host per run.
- Results are keyed by **position**, not alias. Two hosts sharing an alias would otherwise overwrite
  each other's outcome and misattribute a failure.

## Risks / Trade-offs

- **Behavior-identity risk to the packer bake path.** D3 rewrites the bake-time loop to call an
  external script instead of an inline function — the risk is a subtle behavioral difference
  between "inline function call" and "external script invocation" (working directory, environment
  variables, `set -x`/`set -euo pipefail` scoping). Mitigated by the loop still doing its own
  per-version logging/backgrounding exactly as today, just calling out to the script instead of an
  inline function body; needs explicit bake-path verification (packer Docker test), not just
  runtime-path testing.
- **The git-branch install mode is genuinely untested in production today, and this change keeps
  it fully available** (an explicit owner decision — not something to relitigate). Expect real
  debugging if/when it's actually exercised, whether that happens via the bake-time loop or via
  `cassandra install`.
- **Concurrent installs across nodes** each run a full `ant realclean && ant` build when the
  git-branch mode is used (~3–10 minutes, CPU-saturating). Acceptable per domain consult —
  installing while Cassandra is running is safe (install only ever writes to
  `/usr/local/cassandra/<version>`, untouched by the running process) but not free; the command
  should warn, not refuse. Tarball-mode installs are far cheaper and carry no such concern.
- **`HostOperationsService` change touches every existing `parallel = true` caller's failure
  semantics**, even though only `CassandraInstall` needs it today. Reviewed and accepted as the
  right scope by the owner (folded into this change rather than a separate issue) specifically
  because those callers were already silently swallowing failures — this fixes a real bug for them,
  it doesn't introduce new risk.
