## 1. `HostOperationsService` per-host result collection

- [x] 1.1 Add per-host result collection to `HostOperationsService.withHosts(parallel = true)` —
  each host's action outcome (success or the caught exception) collected thread-safely and made
  available to the caller, instead of the current fire-and-`.join()` with no result channel
- [x] 1.2 Verify existing `parallel = true` callers (`UseCassandra`, `SetupInstance`, `ExecStop`,
  `ExecList`, `ExecRun`) keep their current externally-observable behavior — they should now
  correctly fail loudly on a swallowed exception that previously died silently, not change
  behavior otherwise

## 2. Extract the standalone install script

- [x] 2.1 Create `packer/cassandra/bin/install-cassandra-version`, containing
  `install_cassandra_version()` and `download_cassandra_version()` extracted from
  `install_cassandra.sh:11-301`, plus the S3-cache-with-fallback sourcing block
- [x] 2.2 Diverge from the bake-time version in exactly two ways: select `JAVA_HOME` from the
  target version's own `java` field per invocation (never the node's current default alternative,
  never call `update-java-alternatives`); do not `rm -rf ~/.m2` after a build
- [x] 2.3 Update `install_cassandra.sh` to source the new script and call the same function from
  its bake-time loop — no duplicated logic
- [x] 2.4 Add the `lazy: true` skip to the bake-time install loop (`install_cassandra.sh`'s `for
  version in $VERSIONS` loop) — entry still gets written to `/etc/cassandra_versions.yaml` via the
  existing unconditional file-copy step in `cassandra.pkr.hcl`, only the install itself is skipped
- [x] 2.5 Bake `install-cassandra-version` onto the AMI alongside `use-cassandra`/`set-java-version`
  (`cassandra.pkr.hcl`)

## 3. Fix `use-cassandra`'s missing existence check

- [x] 3.1 Add an existence check to `packer/cassandra/bin/use-cassandra` before the `ln -vfns`
  symlink swap — fail with a message directing to `cassandra install <version>` when
  `/usr/local/cassandra/<version>` doesn't exist

## 4. Data model — `CassandraVersion.lazy`

- [x] 4.1 Add `lazy: Boolean = false` to `CassandraVersion` (`configuration/CassandraVersion.kt`)
- [x] 4.2 No `lazy: true` entry committed to `packer/cassandra/cassandra_versions.yaml` — unit
  coverage uses a temp-file fixture, and a committed entry would show up as a phantom
  declared-but-uninstalled version in every user's `cassandra list`. Documented in
  `docs/` instead; manual bake verification (8.10) adds one via the profile extras directory

## 5. `cassandra install <version>` command

- [x] 5.1 Create `CassandraInstall.kt` in `commands/cassandra/`: `@Parameters version: String`,
  `@Mixin HostsMixin`, `--url`, `--branch`, `--java`, `--python` (default `3.11.9`),
  `--ant-flags` options, `@RequireProfileSetup`
- [x] 5.2 Version resolution: CLI flags take precedence when supplied; otherwise resolve via
  `CassandraVersion.loadFromMainAndExtras(context.packerHome + "cassandra/cassandra_versions.yaml",
  context.cassandraVersionsExtra)`, matching on `version`; error clearly if neither source has an
  entry
- [x] 5.3 Per targeted host (via the fixed `HostOperationsService.withHosts(parallel = true)`):
  skip (already-installed no-op) when `/usr/local/cassandra/<version>` exists on the node —
  **not** when the version appears in the node's yaml, since every AMI ships the whole
  cassandra_versions.yaml including `lazy: true` entries. Otherwise download
  `/etc/cassandra_versions.yaml`, parse via `CassandraVersion.loadFromFile`, and
  `CassandraVersion.write()` + upload the whole file back whenever the node's entry for this
  version differs from the resolved one (url/branch stripped — the node never reads them)
- [x] 5.4 Invoke `install-cassandra-version <version>` remotely via `RemoteOperationsService
  .executeRemotely` for each targeted host after the yaml push-up, passing whichever of
  `--url`/`--branch`/`--java`/`--ant-flags` the resolved entry (declared or CLI-flag) carries
- [x] 5.5 Collect and report per-host outcome (installed / already-present / failed-with-reason);
  exit non-zero if any host failed
- [x] 5.6 Register `CassandraInstall` as the `install` subcommand in `Cassandra.kt`

## 6. `cassandra list` — show lazy-declared versions

- [x] 6.1 Extend `ListVersions.kt` to also resolve the merged candidate set via
  `loadFromMainAndExtras`, and mark any `lazy: true` entry not present in the queried host's
  `ls /usr/local/cassandra` output as declared-but-not-installed, distinguishable in the output

## 7. Spec and docs

- [x] 7.1 `openspec/changes/issue-876/specs/cassandra/spec.md` already carries the REQ-CA-001
  rewording and the new "Runtime Version Installation" requirement; verified the implementation
  against every scenario in it. Applied to `openspec/specs/cassandra/spec.md` at archive time
- [x] 7.2 Document `cassandra install` usage (declared-entry and CLI-flag paths, `--hosts`
  targeting, `lazy: true`) in the appropriate `docs/` page

## 8. Verification

- [x] 8.1 Unit tests: `CassandraVersion` `lazy` field round-trips through `loadFromFile`/`write`
- [x] 8.2 Unit tests: `CassandraInstall`'s version-resolution precedence (CLI flags override
  declared entry; error when neither source has an entry)
- [x] 8.3 Unit tests: `HostOperationsService.withHosts(parallel = true)` result collection —
  a per-host exception is captured and surfaced, not silently dropped
- [ ] 8.4 Integration/manual: install a declared tarball-mode version onto a live cluster, confirm
  `cassandra use` succeeds afterward — **script half verified** in a container (official-release
  and `--url` tarball both install, configure, and chown correctly); the live-cluster leg still
  needs a real run
- [ ] 8.5 Integration/manual: install a git-branch-mode version (first real exercise of this path
  outside packer) — confirm build succeeds and the artifact lands correctly; budget time to debug,
  per design.md's noted risk. **Not exercised locally** — needs a real `ant` build
- [ ] 8.6 Integration/manual: `--hosts`-filtered install only touches the targeted node(s) — host
  filtering itself is unit-tested (`HostOperationsServiceTest`); the live leg still needs a run
- [x] 8.7 Re-running install for an already-installed version is a no-op — verified twice: the
  on-disk short-circuit is unit-tested (`CassandraInstallTest`), and the script's own directory
  guard is covered by `install-cassandra-version.test.sh` and was verified in a container,
  including that a failure mid-install cleans up rather than leaving a half-configured directory a
  retry would skip
- [ ] 8.8 Integration/manual: a deliberately broken install (bad branch / 404 URL) fails loudly
  with a per-host message, and a healthy host's prior success is untouched — unit-tested at the
  command level; the live leg still needs a run
- [x] 8.9 `cassandra use` against an uninstalled version fails clearly instead of leaving a
  dangling symlink — verified in a container against the real `use-cassandra` script
- [ ] 8.10 Integration/manual: a `lazy: true` version is skipped at bake time (verify bake logs show
  no install work for it) but installs successfully at runtime afterward — the bake loop's `yq`
  resolution and lazy skip were verified locally against a sample yaml; the bake itself still needs
  a run
- [ ] 8.11 Integration/manual: `cassandra list` shows a lazy-declared-but-uninstalled version,
  distinguishable from installed ones — rendering is unit-tested (`ListVersionsTest`); the live leg
  still needs a run
