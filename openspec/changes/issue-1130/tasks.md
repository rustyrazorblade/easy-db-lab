## 1. Cluster state record

- [x] 1.1 Test first (`ClusterStateTest`): a state with `nodes[alias].installed` records and an active version round-trips through `ClusterStateManager`; a `state.json` with the old `versions` key loads
- [x] 1.2 Add `CassandraSource` (with `PROFILE`), `InstalledVersion` and the `installed` map to `NodeState` (`configuration/ClusterState.kt`); remove `ClusterState.versions` (owner decision); KDoc on each class
- [x] 1.3 Move `Start.validateVersionsAssigned`, `ClusterStatusCollector` and the tests that read `versions` to `nodes[alias].version`

## 2. Release-line table and version sources

- [x] 2.1 Add the release-line resource (`cassandra/release-lines.yaml`) with the per-ref entry `5.0-rustyrazorblade` (11, 17, 21) and a `@Serializable` loader (kaml); tests: `4.1.7` maps to 4.1, `5.0-HEAD` and `5.0.6` map to 5.0, `5.0-rustyrazorblade-HEAD` maps to `5.0-rustyrazorblade` (longest prefix at a `-` boundary), `5.0-rustyrazorbladeX` does not, `trunk` maps to trunk, `5.1-...` maps to nothing
- [x] 2.1a Integration-tier test: fetch `build.xml` from the fork branch `cassandra-5.0-rustyrazorblade` and fail when its `java.supported` differs from the `5.0-rustyrazorblade` entry
- [x] 2.1b Follow-up: when cassandra-builds#1 ships the manifest with the supported JDKs, remove the `5.0-rustyrazorblade` entry and its test (open an issue for this when this change merges, linked to cassandra-builds#1): #1193
- [x] 2.1c `ProfileBuildSource`: read `<profile dir>/cassandra_versions/*.yaml` (`Context.cassandraVersionsExtra`) into `@Serializable` `ProfileBuild` entries (kaml: `version`, `url`, `branch`, `java_supported`, `java`, `python`, `ant_flags`; unknown keys ignored); tests with `@TempDir`: an entry loads; no directory gives no builds; an entry with no `url` fails and names the file and the entry; a name in two files fails and names both; a file that cannot be parsed fails and names it; `java` not in `java_supported` fails
- [x] 2.2 `AsfReleaseSource`: parse the archive index into GA releases and release lines; tests with a captured index page (highest GA patch of a line, `-rc`/`-beta` not picked for a line, an exact name found)
- [x] 2.3 `CassandraBuildsSource`: parse the GitHub release JSON of the `nightly` release into `<ref>` names and URLs, and read the manifest beside a tarball when it exists; tests with captured JSON, including a release with no manifest
- [x] 2.4 `VersionSourceCache`: the profile files `cassandra-sources/asf.json` and `cassandra-builds.json` (kotlinx.serialization) with the fetch time; tests with `@TempDir`: no file fetches and writes, a file makes no fetch, `refresh` replaces, a failed fetch with no file fails and names the source and the URL, a failed fetch with a file returns the file marked stale
- [x] 2.5 HTTP: one plain OkHttp client for public sources (timeouts in `Constants`, never the SOCKS tunnel), with the resilience4j checked decorator for `IOException`; integration-tier test with MockWebServer for a retry and a 404

## 3. Version resolver and JDK choice

- [x] 3.1 Tests first (`CassandraVersionResolverTest`), one per rule of design D1, D2 and D13: state record wins over a profile build; a profile build wins over `--url`, ASF, cassandra-builds and the catalog (no call to them); the profile entry's JDKs and Python replace the manifest and the table; `--java`/`--python` override the entry; `--java` outside the entry's list fails; a profile entry's `java` is the default JDK; a profile entry with a `branch` is a git build; a profile build gets no cache location; `--url` and `--url --branch`; ASF line and exact release; the archive fallback URL; cassandra-builds; catalog consulted last and only then; unknown name names the sources; `--java` not in the list fails and lists them; no list and no `--java` says to pass `--java`; highest JDK by default; recorded JDK kept; manifest beats the table; Python default and `--python`; the cache location only for ASF; credentials redacted in the recorded URL
- [x] 3.2 Implement `CassandraVersionResolver` and `InstallSpec` (builder, not a long constructor), with the cache location from `ClusterS3Path.downloadCacheRoot(accountBucket)` (new, next to `cassandraBuildsRoot`)
- [x] 3.3 One-time ASF refetch when an `X.Y.Z` name is not in the cached list; test

## 4. Node scripts

- [x] 4.1 `edl-cache-lib.sh`: add `cached_fetch_uri <cache-uri> <dest> <url> [<fallback-url>]` (s3 scheme only; an unknown scheme fails naming it); extend `edl-cache-lib.test.sh`: hit, miss with write-back, primary fails and fallback succeeds, both fail and both URLs are printed, unknown scheme
- [x] 4.2 `install-cassandra-version`: require `--url`; add `--fallback-url`, `--cache-uri` and `--reinstall`; remove the dlcdn scrape and the use of `/etc/edl-cache.env`; print the version and every URL tried on failure; update `install-cassandra-version.test.sh` (keep the direct-fetch, s3 and git cases; replace the prefix cases with `--cache-uri` cases; `--reinstall` replaces an existing install; a failed `--reinstall` leaves the old directory in place)
- [x] 4.3 `use-cassandra <version> --java N --python X`: remove the yaml and `yq`; update `use-cassandra.test.sh` (missing argument fails, unknown JDK fails, the switch commands are run)
- [x] 4.4 Delete `set-java-version` and `patch-jvm-options.py`, and their packer uploads
- [x] 4.5 `edl-cassandra-agents.sh`: add `edl_axonops_agents` (the list AxonOps ships, one source with `edl_axonops_agent_for`); keep POSIX sh; extend `edl-cassandra-agents.test.sh` under `/bin/sh`
- [x] 4.6 New `install-axonops-agent <X.Y> <java> --cache-prefix URI` (the download function moved from `install_axon.sh`; exit 3 with the shipped list when there is no agent; no-op when the jar is there; the package version in the cache key); shell unit test with stubbed `apt-get`, `aws` and `dpkg-deb`
- [x] 4.7 `cassandra.in.sh`: load only the jar of `EDL_AXONOPS_AGENT`; no `edl_axonops_agent_for` call, no NOTE path; test the empty and the set cases
- [x] 4.8 Run `./gradlew testCassandraScripts` and the base script tests (in a build-engineer subagent)

- [x] 4.9 `install-cassandra-version --verify-checksum` (design D14): check an ASF tarball against `.sha512`, else `.sha256`, else `.sha1`, from `--url` then `--fallback-url`, before the extract; fail on a mismatch or no checksum, and remove the cache object; the CLI passes the flag for the ASF source only; a shell test table (match, both file forms, sha256 only, sha1 only, preference, dlcdn-to-archive, mismatch, none, a bad cache object, a non-ASF source) and a Kotlin table over every source

## 5. Commands

- [x] 5.1 `CassandraInstall`: resolve with the resolver (replaces `candidateVersions` and `declaredCassandraVersions`); install with the new script arguments; record results in the state after all hosts (no write in the parallel lambda); keep the per-host outcome events; the mismatch check compares with the state record; remove the node yaml push and download; update `CassandraInstallTest`
- [x] 5.1a `cassandra install --reinstall` (design D12): skip the already-installed check, pass `--reinstall` to the script, update the record of each host that succeeded, keep the old record on a failed host, refuse a host where the version is active and Cassandra runs (before any download, with the `cassandra stop` message), re-apply `cassandra.patch.yaml` where the version is active; tests for each case
- [x] 5.2 Extract the install fan-out into a `CassandraInstaller` service, so `use`, `install` and the node restorer share one path (commands stay thin)
- [x] 5.3 `UseCassandra`: list `/usr/local/cassandra` on each target; install where missing through `CassandraInstaller`; activate on no host when any install fails; run `use-cassandra --java --python`; record the state one time; `UseSideEffects` names the hosts that download and the JDK; tests for each case, including 50 hosts with no lost record
- [x] 5.4 `Start`: `--skip-axonops`; with AxonOps on, run `install-axonops-agent` on every target before any start; fail with a typed event (release, JDK, shipped agents, `--skip-axonops`) when any node has no agent; write the systemd drop-in and `daemon-reload`; tests for agent found, no agent, `--skip-axonops`, AxonOps off
- [x] 5.5 `ListVersions`: no cluster needed; one table with VERSION, SOURCE (`profile`, `asf`, `cassandra-builds`, `build`) and INSTALLED via `println()`; profile builds from `ProfileBuildSource` in place of `declaredCassandraVersions`; `--refresh`; stale-cache line; unreachable node exits non-zero; remove `Event.Cassandra.VersionList`; tests on the table builder
- [x] 5.6 `CassandraNodeRestorer.restore(host, nodeState)` for #1158 (design D7); tests: it uses the recorded URL, JDK and Python and not a new resolution; it installs only the active version when `installed` lists more; afterwards the node's `installed` holds only the active version
- [x] 5.7 `cassandra build`: write `javaSupported` from `build.xml` (else the table, else `[java]`); `CassandraBuildManifest` reads an old manifest as `[javaVersion]`; tests
- [x] 5.9 Review fixes: `cassandra install --reinstall` activates the version again, on the reinstalled JDK, on each node where it is active, and records the JDK; `cassandra use` selects the AxonOps agent and re-applies the config whatever the other step did; `wait-for-up-normal` hides the sjk error of each poll before NORMAL
- [x] 5.10 Review fixes: `cassandra use` re-applies the config even when an AxonOps drop-in cannot be written (`AxonOpsDropInNotWritten`); the cluster default JDK is the resolved one; `use-cassandra` switches the JDK and Python before the version links; `install-axonops-agent` fails when the agent cannot be moved into place
- [x] 5.8 New typed events in `events/Event.kt` for the download hosts, the install failure with URLs, the AxonOps refusal and the stale cache; update `events/CLAUDE.md`

## 6. Remove the yaml

- [x] 6.1 Delete `packer/cassandra/cassandra_versions.yaml`, `CassandraVersion` (replaced by `ProfileBuild`), `DeclaredVersions.kt` (replaced by `ProfileBuildSource`), `Constants.Packer.CASSANDRA_VERSIONS_FILE`, the yaml merge step in `containers/Packer.kt` (the bake no longer reads the profile directory), `Up.downloadCassandraVersions`, the `CASSANDRA_VERSIONS` backup target, `ClusterS3Path.cassandraVersions()` and the `Clean` entry, with their tests.  Keep `Context.cassandraVersionsExtra`
- [x] 6.2 Remove the yaml check from `.github/cassandra-image/resolve-build-plan.test.sh`
- [x] 6.3 Confirm with `grep` that no code, script, workflow or test names `cassandra_versions.yaml` or `/etc/cassandra_versions.yaml`, and that only `Context` and `ProfileBuildSource` name the profile `cassandra_versions` directory

## 7. Bake

- [x] 7.1 `install_cassandra.sh`: keep the one-time setup; remove the install loop and the `yq` functions; update `install_cassandra.test.sh`
- [x] 7.2 `install_axon.sh`: keep the repository, `axon-agent` and the sudoers file; remove the per-release downloads; install `install-axonops-agent` on the `PATH`; update `install_axon.test.sh`
- [x] 7.3 `cassandra.pkr.hcl`: remove the yaml provisioner; correct the `s3_bucket` comment
- [x] 7.4 Run `./gradlew testPackerCassandra testPackerBase` (Docker, in a build-engineer subagent)

## 8. Docs and CLAUDE.md

- [x] 8.1 `docs/user-guide/installing-cassandra.md`: the on-demand flow, the sources, the JDK rules and table, the download cache, `cassandra list` and `--refresh`, `--skip-axonops`, `cassandra install --reinstall` for a new nightly build; rewrite the "Bake into the AMI" section as "Named builds in your profile" (new format with a required `url`, no rebuild of the AMI, no `lazy`)
- [x] 8.2 `docs/reference/commands.md`: `cassandra use`, `install`, `list`, `start`
- [x] 8.3 `docs/user-guide/tutorial.md`, `packer/README.md`, `packer/TESTING.md`, `packer/CLAUDE.md`, `commands/CLAUDE.md` (the script contract), `configuration/CLAUDE.md` (the version record, no `CassandraVersion`), `.claude/skills/cluster-ops/SKILL.md` and `.claude/skills/agent-test/SKILL.md`
- [x] 8.4 Confirm the spec deltas still match the code, and `openspec validate issue-1130 --strict` passes

## 9. Local gates

- [x] 9.1 `./gradlew ktlintFormat`, then `./gradlew check` on JDK 21 (in a build-engineer subagent); no new `@Suppress`, threshold change or baseline entry

## 10. Real AWS test (`requires-aws`, `requires-ami-bake`)

- [x] 10.1 `./gradlew installDist`, then `build-image`; record the AMI IDs in the state file
- [x] 10.2 A new node from the AMI has no version under `/usr/local/cassandra` and no `/etc/cassandra_versions.yaml`
- [x] 10.3 A 3-node cluster with the off-hours tag that WORKFLOW.md names: `cassandra list` before `init` (no cluster) and after `up`; then `cassandra use 5.0` (ASF, JDK 17, cache miss then a second cluster or node shows a cache hit), `start`, `nodetool status`
- [x] 10.4 `cassandra use 4.1.7` (ASF, not in any file), `start`; `cassandra use 5.0-rustyrazorblade-HEAD` (cassandra-builds), `start`, JDK checked on the node; a second `use` of the same version downloads nothing
- [x] 10.5 `cassandra use 5.0.6 --java 21` fails and lists 11 and 17; a bad name fails before any node changes
- [ ] 10.6 With AxonOps on: `start` on 4.1 installs the agent and it connects; on 5.0 with JDK 21, `start` fails before Cassandra starts and names `--skip-axonops`; `start --skip-axonops` starts on JDK 21 (only the `--skip-axonops` path ran on AWS: the profile has no AxonOps credentials; the agent selection is covered by unit and shell tests)
- [x] 10.7 `cassandra build` (local) then `cassandra use <build>` from the catalog; `cassandra install <name> --url <git> --branch <b> --java 11` builds as before
- [x] 10.7a A profile build (a yaml in the profile `cassandra_versions/` directory with a tarball URL and `java`): `cassandra list` shows it with source `profile`; `cassandra use <it>` installs it with that JDK
- [x] 10.7b `cassandra stop`, then `cassandra install 5.0-HEAD --reinstall`: the node downloads again and the record is updated; `cassandra start` works; with Cassandra running, the reinstall of the active version is refused
- [x] 10.7c After the AMI rebake with the checksum check: `cassandra use 4.1.7` shows `checksum sha512 OK`
- [x] 10.7d On the same AMI: `cassandra stop --hosts db0`, `cassandra install 4.1.7 --reinstall --java 8 --hosts db0` leaves db0 on JDK 8 with `javaVersion` 8 recorded, and `cassandra start` reaches NORMAL on JDK 8 with no sjk error in the output
- [x] 10.8 `down`; put the commands, their output and the AWS-side result in the PR
