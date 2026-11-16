## 1. Cluster state record

- [ ] 1.1 Test first (`ClusterStateTest`): a state with `nodes[alias].installed` records and an active version round-trips through `ClusterStateManager`; a `state.json` with the old `versions` key loads
- [ ] 1.2 Add `CassandraSource`, `InstalledVersion` and the `installed` map to `NodeState` (`configuration/ClusterState.kt`); remove `versions` (or keep it, per the owner's answer to Open Question 2); KDoc on each class
- [ ] 1.3 Move `Start.validateVersionsAssigned`, `ClusterStatusCollector` and the tests that read `versions` to `nodes[alias].version`

## 2. Release-line table and version sources

- [ ] 2.1 Add the release-line resource (`cassandra/release-lines.yaml`) and a `@Serializable` loader (kaml); tests: `4.1.7` maps to 4.1, `5.0-HEAD` and `5.0-rustyrazorblade-HEAD` map to 5.0, `trunk` maps to trunk, `5.1-...` maps to nothing
- [ ] 2.2 `AsfReleaseSource`: parse the archive index into GA releases and release lines; tests with a captured index page (highest GA patch of a line, `-rc`/`-beta` not picked for a line, an exact name found)
- [ ] 2.3 `CassandraBuildsSource`: parse the GitHub release JSON of the `nightly` release into `<ref>` names and URLs, and read the manifest beside a tarball when it exists; tests with captured JSON, including a release with no manifest
- [ ] 2.4 `VersionSourceCache`: the profile files `cassandra-sources/asf.json` and `cassandra-builds.json` (kotlinx.serialization) with the fetch time; tests with `@TempDir`: no file fetches and writes, a file makes no fetch, `refresh` replaces, a failed fetch with no file fails and names the source and the URL, a failed fetch with a file returns the file marked stale
- [ ] 2.5 HTTP: one plain OkHttp client for public sources (timeouts in `Constants`, never the SOCKS tunnel), with the resilience4j checked decorator for `IOException`; integration-tier test with MockWebServer for a retry and a 404

## 3. Version resolver and JDK choice

- [ ] 3.1 Tests first (`CassandraVersionResolverTest`), one per rule of design D1 and D2: state record wins; `--url` and `--url --branch`; ASF line and exact release; the archive fallback URL; cassandra-builds; catalog consulted last and only then; unknown name names the sources; `--java` not in the list fails and lists them; no list and no `--java` says to pass `--java`; highest JDK by default; recorded JDK kept; manifest beats the table; Python default and `--python`; the cache location only for ASF; credentials redacted in the recorded URL
- [ ] 3.2 Implement `CassandraVersionResolver` and `InstallSpec` (builder, not a long constructor), with the cache location from `ClusterS3Path.downloadCacheRoot(accountBucket)` (new, next to `cassandraBuildsRoot`)
- [ ] 3.3 One-time ASF refetch when an `X.Y.Z` name is not in the cached list; test

## 4. Node scripts

- [ ] 4.1 `edl-cache-lib.sh`: add `cached_fetch_uri <cache-uri> <dest> <url> [<fallback-url>]` (s3 scheme only; an unknown scheme fails naming it); extend `edl-cache-lib.test.sh`: hit, miss with write-back, primary fails and fallback succeeds, both fail and both URLs are printed, unknown scheme
- [ ] 4.2 `install-cassandra-version`: require `--url`; add `--fallback-url` and `--cache-uri`; remove the dlcdn scrape and the use of `/etc/edl-cache.env`; print the version and every URL tried on failure; update `install-cassandra-version.test.sh` (keep the direct-fetch, s3 and git cases; replace the prefix cases with `--cache-uri` cases)
- [ ] 4.3 `use-cassandra <version> --java N --python X`: remove the yaml and `yq`; update `use-cassandra.test.sh` (missing argument fails, unknown JDK fails, the switch commands are run)
- [ ] 4.4 Delete `set-java-version` and `patch-jvm-options.py`, and their packer uploads
- [ ] 4.5 `edl-cassandra-agents.sh`: add `edl_axonops_agents` (the list AxonOps ships, one source with `edl_axonops_agent_for`); keep POSIX sh; extend `edl-cassandra-agents.test.sh` under `/bin/sh`
- [ ] 4.6 New `install-axonops-agent <X.Y> <java> --cache-prefix URI` (the download function moved from `install_axon.sh`; exit 3 with the shipped list when there is no agent; no-op when the jar is there; the package version in the cache key); shell unit test with stubbed `apt-get`, `aws` and `dpkg-deb`
- [ ] 4.7 `cassandra.in.sh`: load only the jar of `EDL_AXONOPS_AGENT`; no `edl_axonops_agent_for` call, no NOTE path; test the empty and the set cases
- [ ] 4.8 Run `./gradlew testCassandraScripts` and the base script tests (in a build-engineer subagent)

## 5. Commands

- [ ] 5.1 `CassandraInstall`: resolve with the resolver; install with the new script arguments; record results in the state after all hosts (no write in the parallel lambda); keep the per-host outcome events; the mismatch check compares with the state record; remove the node yaml push and download; update `CassandraInstallTest`
- [ ] 5.2 Extract the install fan-out into a `CassandraInstaller` service, so `use`, `install` and the node restorer share one path (commands stay thin)
- [ ] 5.3 `UseCassandra`: list `/usr/local/cassandra` on each target; install where missing through `CassandraInstaller`; activate on no host when any install fails; run `use-cassandra --java --python`; record the state one time; `UseSideEffects` names the hosts that download and the JDK; tests for each case, including 50 hosts with no lost record
- [ ] 5.4 `Start`: `--skip-axonops`; with AxonOps on, run `install-axonops-agent` on every target before any start; fail with a typed event (release, JDK, shipped agents, `--skip-axonops`) when any node has no agent; write the systemd drop-in and `daemon-reload`; tests for agent found, no agent, `--skip-axonops`, AxonOps off
- [ ] 5.5 `ListVersions`: no cluster needed; one table with VERSION, SOURCE and INSTALLED via `println()`; `--refresh`; stale-cache line; unreachable node exits non-zero; remove `Event.Cassandra.VersionList`; tests on the table builder
- [ ] 5.6 `CassandraNodeRestorer.restore(host, nodeState)` for #1158 (design D7); test that it uses the recorded URL, JDK and Python and not a new resolution
- [ ] 5.7 `cassandra build`: write `javaSupported` from `build.xml` (else the table, else `[java]`); `CassandraBuildManifest` reads an old manifest as `[javaVersion]`; tests
- [ ] 5.8 New typed events in `events/Event.kt` for the download hosts, the install failure with URLs, the AxonOps refusal and the stale cache; update `events/CLAUDE.md`

## 6. Remove the yaml

- [ ] 6.1 Delete `packer/cassandra/cassandra_versions.yaml`, `CassandraVersion`, `DeclaredVersions.kt`, `Context.cassandraVersionsExtra` (per the owner's answer to Open Question 3), `Constants.Packer.CASSANDRA_VERSIONS_FILE`, the yaml step in `containers/Packer.kt`, `Up.downloadCassandraVersions`, the `CASSANDRA_VERSIONS` backup target, `ClusterS3Path.cassandraVersions()` and the `Clean` entry, with their tests
- [ ] 6.2 Remove the yaml check from `.github/cassandra-image/resolve-build-plan.test.sh`
- [ ] 6.3 Confirm with `grep` that no code, script, workflow or test names `cassandra_versions` or `/etc/cassandra_versions.yaml`

## 7. Bake

- [ ] 7.1 `install_cassandra.sh`: keep the one-time setup; remove the install loop and the `yq` functions; update `install_cassandra.test.sh`
- [ ] 7.2 `install_axon.sh`: keep the repository, `axon-agent` and the sudoers file; remove the per-release downloads; install `install-axonops-agent` on the `PATH`; update `install_axon.test.sh`
- [ ] 7.3 `cassandra.pkr.hcl`: remove the yaml provisioner; correct the `s3_bucket` comment
- [ ] 7.4 Run `./gradlew testPackerCassandra testPackerBase` (Docker, in a build-engineer subagent)

## 8. Docs and CLAUDE.md

- [ ] 8.1 `docs/user-guide/installing-cassandra.md`: the on-demand flow, the three sources, the JDK rules and table, the download cache, `cassandra list` and `--refresh`, `--skip-axonops`, how to get a new nightly build
- [ ] 8.2 `docs/reference/commands.md`: `cassandra use`, `install`, `list`, `start`
- [ ] 8.3 `docs/user-guide/tutorial.md`, `packer/README.md`, `packer/TESTING.md`, `packer/CLAUDE.md`, `commands/CLAUDE.md` (the script contract), `configuration/CLAUDE.md` (the version record, no `CassandraVersion`), `.claude/skills/cluster-ops/SKILL.md` and `.claude/skills/agent-test/SKILL.md`
- [ ] 8.4 Confirm the spec deltas still match the code, and `openspec validate issue-1130 --strict` passes

## 9. Local gates

- [ ] 9.1 `./gradlew ktlintFormat`, then `./gradlew check` on JDK 21 (in a build-engineer subagent); no new `@Suppress`, threshold change or baseline entry

## 10. Real AWS test (`requires-aws`, `requires-ami-bake`)

- [ ] 10.1 `./gradlew installDist`, then `build-image`; record the AMI IDs in the state file
- [ ] 10.2 A new node from the AMI has no version under `/usr/local/cassandra` and no `/etc/cassandra_versions.yaml`
- [ ] 10.3 A 3-node cluster with the off-hours tag that WORKFLOW.md names: `cassandra list` before `init` (no cluster) and after `up`; then `cassandra use 5.0` (ASF, JDK 17, cache miss then a second cluster or node shows a cache hit), `start`, `nodetool status`
- [ ] 10.4 `cassandra use 4.1.7` (ASF, not in any file), `start`; `cassandra use 5.0-rustyrazorblade-HEAD` (cassandra-builds), `start`, JDK checked on the node; a second `use` of the same version downloads nothing
- [ ] 10.5 `cassandra use 5.0.6 --java 21` fails and lists 11 and 17; a bad name fails before any node changes
- [ ] 10.6 With AxonOps on: `start` on 4.1 installs the agent and it connects; on 5.0 with JDK 21, `start` fails before Cassandra starts and names `--skip-axonops`; `start --skip-axonops` starts on JDK 21
- [ ] 10.7 `cassandra build` (local) then `cassandra use <build>` from the catalog; `cassandra install <name> --url <git> --branch <b> --java 11` builds as before
- [ ] 10.8 `down`; put the commands, their output and the AWS-side result in the PR
