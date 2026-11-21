## 1. Shared config merge (VM path first)

- [x] 1.1 `packer/cassandra/lib/edl-cassandra-config.sh` (POSIX): `edl_merge_cassandra_yaml <orig> <out> <patch>...` with yq `. *= load(...)`
- [x] 1.2 `patch-config` calls the library. Shell test: the "Successfully patched" marker and every exit code are unchanged, the patches merge in order, and the library runs under a real `/bin/sh`
- [x] 1.3 `cassandra.in.sh` env overrides: `ECL_AGENTS_LIB`, `EDL_CASSANDRA_BUILD`, the OTLP endpoint, the log directory, and extra resource attributes. Extend `edl-cassandra-agents.test.sh` (real `/bin/sh`): the VM defaults are unchanged, the overrides are applied, and `host.name` is kept
- [x] 1.4 `./gradlew testCassandraScripts` passes

## 2. Config patch builders in Kotlin

- [x] 2.1 `configuration/cassandra/CassandraPlacement` (sealed `Vm`, `Pods`) and `CassandraConfigPatch.common` / `placement`, with KDoc
- [x] 2.2 `WriteConfig` becomes a thin caller and writes the same file as today. `CassandraConfigPatchTest`: each placement produces exactly its own seeds, directories, snitch and native port, and `common` carries `cluster_name`, `num_tokens`, the concurrency settings and `trickle_fsync`

## 3. Tarball artifact resolution

- [x] 3.1 `Sha256` value class and `TarballArtifact`. Constants for the presign TTL, the cache root `.edl/artifacts` and the runtime image
- [x] 3.2 `TarballDigests`: ASF `.sha256`, else `.sha512` (check one download, compute the sha256); the GitHub asset `digest` (`CassandraBuildsRelease.parse` gains the field); the catalog `tarballSha256`. Tests cover both checksum file forms, a missing or malformed digest, and `Sha256` rejecting bad input
- [x] 3.3 `ObjectStore.presignedGetUrl(path, ttl)` for catalog builds
- [x] 3.4 `CassandraVersionResolver` returns a `TarballArtifact` for `--version`, and a cassandra-builds-only path for `--build`

## 4. Kit variable contributors

- [x] 4.1 `KitVariableContributor` and `KitContribution`; `contributors:` parsed from `kit.yaml`, with an unknown name failing at parse time
- [x] 4.2 The kit runner calls the contributors before `start` and before declared `commands:` (not `stop`/`uninstall`), merges their variables under cluster state, and writes their files before the first step. A contributor failure fails the command before any step runs
- [x] 4.3 Engine operation to rewrite one recorded install arg in `resolved-args.env`
- [x] 4.4 Tests for 4.1 to 4.3: a contributed variable and a contributed file reach the steps, failure stops the run, cluster state is not shadowed, and a rewrite keeps the other args

## 5. Placement variables

- [ ] 5.1 `TemplateVariables` publishes `DB_NODE_ALLOCATABLE_MIB` and `DB_NODE_ALLOCATABLE_CPU` (the smallest of the db nodes, read from the Kubernetes API) and `GUARANTEED_PODS` (owned nodes)
- [ ] 5.2 Test with node fixtures: the minimum across db nodes, and `false` off owned nodes

## 6. CassandraArtifactContributor

- [ ] 6.1 Resolves `--version`/`--build`/`--java` to the artifact env (`EDL_TARBALL_URL`, `EDL_TARBALL_FALLBACK_URL`, `EDL_TARBALL_SHA256`, `EDL_CASSANDRA_BUILD`, the runtime image tag), and writes `resolved-artifact.env`, the placement patch, `cassandra-rackdc.properties`, and the common patch when the workspace has none. Registered in Koin
- [ ] 6.2 With `--image`, skip resolution. Refuse `--build` together with `--image`. Every failure names its source and happens before any resource
- [ ] 6.3 Memory and CPU defaults: 75% of `DB_NODE_ALLOCATABLE_MIB` or `3Gi`; `--cpus` = allocatable minus 1, at least 1
- [ ] 6.4 Tests with a fake `VersionSources`: `--build` never resolves from ASF, `--image` skips resolution, a missing digest fails, the build/image conflict is refused, and the memory and CPU defaults per environment

## 7. Runtime image

- [ ] 7.1 `docker/cassandra-runtime/Dockerfile`: `eclipse-temurin:<N>-jre` for 8, 11, 17 and 21; curl, python3, procps; yq at the `install_yq.sh` version; uid 999
- [ ] 7.2 Publish job in `.github/workflows/publish-container.yml` for amd64 and arm64, failing when a platform is missing
- [ ] 7.3 Pin the image tag in `Constants`

## 8. Kit resources and scripts

- [ ] 8.1 `build.gradle.kts` `processResources` copies `edl-cassandra-config.sh`, `cassandra.in.sh` and `edl-cassandra-agents.sh` into the kit resources (as it does for the JMX rules). A test checks the packaged copies match `packer/cassandra/`
- [ ] 8.2 `lib/fetch-tarball.sh`: checks the `.complete` marker before any network call; downloads with the fallback URL; checks the sha256; extracts into a temp directory on the claim; renames into place atomically. The mismatch error names both hashes, and the expired-presign error names the expiry and the fix. Shell tests: a cache hit makes zero curl calls, a mismatch leaves no cache directory, and an interrupted extract leaves no `.complete`
- [ ] 8.3 `lib/render-config.sh`: the four-layer merge into the `emptyDir` `CASSANDRA_CONF`, `cassandra.in.sh` with the snippet, `CASSANDRA_INCLUDE`, and `cassandra-rackdc.properties`
- [ ] 8.4 `statefulset.yaml.template` for the tarball path: the `cassandra-fetch` and `cassandra-render` init containers, the artifact env, the `easydblab/config-hash` annotation, `fsGroup: 999`, restricted security contexts, CPU and memory per `GUARANTEED_PODS`, and `RollingUpdate`/`OrderedReady` with `preStop` drain. A separate `--image` template keeps today's spec
- [ ] 8.5 `kit.yaml`: the new args (`--build`, `--java`, `--cpus`, the new `--memory` default), `contributors: [cassandra-artifact]`, the `cassandra-pods-config` ConfigMap step, and the `upgrade` command. `check-start.sh` looks up the image only for `--image`, and `apply-statefulset.sh` picks the template
- [ ] 8.6 `lib/upgrade.sh`: refuse a major-version downgrade before changing anything, rewrite the recorded arg, apply the StatefulSet, and wait for the rollout and UN
- [ ] 8.7 Update the kit.yaml parse test and the template render tests (agent pin, no AMI hostPath, the config hash changes only when the inputs change)

## 9. Integration tests

- [ ] 9.1 Config parity test: the real `cassandra-runtime` image and a real tarball's `cassandra.yaml`, rendered with the VM layers and with the pod layers; the keys that differ are a subset of {addresses, ports, paths, `endpoint_snitch`, `seed_provider`}
- [ ] 9.2 Extend `CassandraPodsAnywhereIntegrationTest`: the tarball path reaches UN and answers CQL on the NodePort; the cache proof (restart with the same sha256 and an unreachable URL still reaches UN); `upgrade` with a new digest changes the pod template and the `cassandra_build` label; a restricted Pod Security namespace admits the pods; the `--image` path still passes
- [ ] 9.3 `./gradlew check` passes (run in a subagent, on JDK 21)

## 10. Docs

- [ ] 10.1 `docs/user-guide/cassandra-pods.md`: the args, `--build`, `--image`, the cache, config parity and its known differences, `upgrade`, and the 3.x Python 2 cqlsh
- [ ] 10.2 `docs/development/kits.md`: kit variable contributors and the arg-rewrite operation
- [ ] 10.3 The kit's `CLAUDE.md` and the root `CLAUDE.md` where the kit is described

## 11. Lab validation

- [ ] 11.1 Lab plan in `test-plans/`: a 3-node rolling `upgrade` under QUORUM load with RF=3, and the start on arm64 (Graviton)
- [ ] 11.2 Run the plan, and check the Cassandra dashboards show the kit pods
