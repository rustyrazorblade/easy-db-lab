## Context

The `cassandra-pods` kit (#1038, PR #1085) runs `cassandra:<version>` from Docker Hub, or any `--image`, as a plain StatefulSet. Branch builds reach it only as images, from `build-cassandra-ref.yml` and `.github/cassandra-image/`, which #961 deletes. The pod is configured through the image's `docker-entrypoint.sh` and `CASSANDRA_*` env vars (`statefulset.yaml.template:52-83`). The VM path instead renders config with `patch-config` (a yq merge of `cassandra.patch.yaml` onto the version's original `cassandra.yaml`, `packer/cassandra/bin/patch-config:43`) and appends `cassandra.in.sh` for agents, the JDK and the GC log. Because the two paths use different configs, results from them are not comparable.

Kit args are fixed at `kit install` (`docs/user-guide/cassandra-pods.md:75`). `stop` deletes the StatefulSet, so it is not a rolling restart. On environments other than AWS, `up` does not write `cassandra.patch.yaml` (`Up.kt:223`).

In short, the kit stops depending on a Cassandra image:
- Each pod runs a generic multi-arch JRE image.
- Init containers fetch the release tarball, check it, cache it on the claim by checksum, and render the config with the VM path's own scripts.
- The CLI decides which tarball runs and what it must hash to, using the resolver the VM path already uses, so nothing is resolved twice.
- Changing the version changes the pod's environment, and the StatefulSet's rolling update restarts pods one at a time.
- The current image path stays behind `--image`.

## Decision record

The architect proposed D1 to D9, each with alternatives. The owner approved every recommendation on 2026-11-20. D3 departs from the issue's wording "packaged in the image", and D9 amends acceptance criterion 3. D10 is the owner's decision of 2026-11-22.

## Goals / Non-Goals

**Goals:**

- The tarball is the one Cassandra artifact on both paths: Apache releases, cassandra-builds fixed-name builds, and `cassandra build` catalog builds.
- Fetch the tarball once per checksum per claim. A cache hit makes no network call.
- Render `cassandra.yaml` with the VM path's merge, so the only differences are in addresses, ports, paths and the snitch.
- Change the version on a running kit as a rolling restart that keeps QUORUM.
- Size memory and CPU from the environment, with Guaranteed QoS where the environment asks for it.
- Keep `--image` as a bypass, and keep the plain StatefulSet (no cass-operator).

**Non-Goals:**

- #961 itself: removing the AMI, the image workflow and the GHCR `cassandra` package.
- The VM's per-version `<version>/` config directory from `download-config` (the JVM options files) does not reach the kit in v1.
- Pruning old cached tarballs. They are kept, at about 50 MB each, so a rollback is a cache hit (YAGNI).
- Pre-fetching tarballs before a roll.
- Multi-DC and per-pod DC/rack from #1097. That issue later fills in `CassandraPlacement` and nothing else.

## Approach

At `start` (or `upgrade`, D7), in this order:

1. The CLI resolves `--version` or `--build` with `CassandraVersionResolver` (`services/cassandraversions/CassandraVersionResolver.kt:48`). That gives an `InstallSpec` plus a new `TarballArtifact`: URL, fallback URL, sha256, build label and JDK.
2. The CLI builds the config inputs:
   - the common patch: the workspace `cassandra.patch.yaml`, with the user's edits;
   - the kit's placement patch;
   - `cassandra-rackdc.properties`.

   They go into a ConfigMap together with the shared scripts.
3. `apply-statefulset.sh` applies the StatefulSet. It puts the artifact in the pod's env and adds a config-hash annotation.
4. In each pod, these run in order:
   - the `otel-agent` init container (unchanged);
   - `cassandra-fetch`: looks for `/var/lib/cassandra/.edl/artifacts/sha256-<hex>/.complete`. If it is missing, it downloads to a temp directory, checks the sha256, extracts, and renames the result into place atomically.
   - `cassandra-render`: builds `cassandra.yaml` into an `emptyDir` `CASSANDRA_CONF` by merging, in order:
     1. the tarball's `conf/cassandra.yaml`;
     2. the common patch;
     3. the kit placement patch;
     4. the per-pod addresses.

     It also writes `cassandra.in.sh` as the tarball's own file plus the packer snippet, and points `CASSANDRA_INCLUDE` at it.
   - `cassandra`: runs `bin/cassandra -f` from the cached tree. Nothing ever changes the cached tree, so it stays safe to reuse.

| Scope item | Where it lives |
| --- | --- |
| Multi-arch JRE image | `docker/cassandra-runtime/`, published like `pyroscope-java-agent` (`.github/workflows/publish-container.yml:100-174`) |
| Fetch and cache by checksum | `cassandra-fetch` init container; the sha256 is known before any download |
| Same scripts as the VM path | Shared merge library; `cassandra.in.sh` and `edl-cassandra-agents.sh` delivered unchanged in purpose (D3) |
| Version change rolls one pod at a time | Artifact in the pod env, plus the default StatefulSet `RollingUpdate` and `OrderedReady` |
| `--image` bypass | A second StatefulSet template that keeps today's pod spec |
| Plain StatefulSet | Unchanged |
| Memory and CPU per environment | New placement variables (D8) |

## Structure and boundaries

**Kotlin**

- `configuration/cassandra/CassandraConfigPatch` (new). Pure functions, one job each:
  - `common(state, tokens)` gives `cluster_name`, `num_tokens`, concurrency and `trickle_fsync`.
  - `placement(CassandraPlacement)` gives the seeds, directories and snitch.
  - `WriteConfig` (`commands/cassandra/WriteConfig.kt:36-72`) becomes a thin caller of both and writes the same file as today.
- `CassandraPlacement` (sealed):
  - `Vm(seedIps, snitch, dataRoot = /mnt/db1/cassandra)`;
  - `Pods(seedDns, dataRoot = /var/lib/cassandra, nativePort = 30942)`.

  #1097 later supplies the snitch and DC here and nowhere else.
- `TarballArtifact` (value type): `url`, `fallbackUrl`, `sha256: Sha256` (a value class, checked in `init`), `build` and `java`. It comes from a new `TarballDigests` with one function per source:
  - ASF: the published `.sha256`, else the `.sha512`. The cache key is always the sha256, so in the `.sha512` case the CLI downloads the tarball once, checks it against the `.sha512`, and computes the sha256 from that download.
  - cassandra-builds: the GitHub asset `digest` field (`sha256:…`). The architect confirmed the field is present on the `nightly` release. `CassandraBuildsRelease.parse` gains it.
  - Build catalog: `CassandraBuildManifest.tarballSha256`.
- `ObjectStore.presignedGetUrl(path, ttl)` (new), for catalog builds only, because pods off AWS have no instance profile. The TTL is in `Constants`.
- `KitVariableContributor` (new, D2): an interface the kit runner calls before `start`. It takes the kit's resolved args and returns extra variables and files. `CassandraArtifactContributor` is the only implementation and is registered in Koin. `kit.yaml` names it with `contributors: [cassandra-artifact]`. The engine stays generic and never mentions Cassandra.

**Shell and image**

- `packer/cassandra/lib/edl-cassandra-config.sh` (new, POSIX): `edl_merge_cassandra_yaml <orig> <out> <patch>...`, which runs the yq `. *= load(...)` merge.
  - `patch-config` (`packer/cassandra/bin/patch-config:43`) calls it, so its contract with `UpdateConfig` (the "Successfully patched" marker and the exit codes) stays the same.
  - The pod's render script calls the same function.
- `cassandra.in.sh` gains env overrides for the values it hardcodes today. On the VM every default stays the same.
  - `ECL_AGENTS_LIB` (line 6);
  - `EDL_CASSANDRA_BUILD`, which today comes from `readlink /usr/local/cassandra/current`;
  - the OTLP endpoint, today `localhost:4318`;
  - the log directory;
  - an extra resource-attributes value. Today `-Dotel.resource.attributes` overrides the pod's `OTEL_RESOURCE_ATTRIBUTES`, which would drop `host.name`.
- `docker/cassandra-runtime/Dockerfile`, one tag per JDK (`<rev>-jre8|11|17|21`):
  - `FROM eclipse-temurin:<N>-jre`;
  - curl, python3 (for cqlsh), procps, and yq pinned to the same version as `packer/base/install/install_yq.sh`;
  - uid 999, matching `install_cassandra.sh:22`.
- The kit gets new lib scripts `lib/fetch-tarball.sh`, `lib/render-config.sh` and `lib/upgrade.sh`. `check-start.sh` keeps its validation; its image lookup now runs only for `--image`.

## Data model and state

- **`state.json`:** no schema change. The resolved artifact lives in the StatefulSet env, which is the cluster's own record, and in `<kit>/resolved-artifact.env` for the scripts.
- **ConfigMap `cassandra-pods-config`.** It carries the kit label, so `stop` and `uninstall` remove it. It holds:
  - `cassandra.patch.yaml`;
  - `placement.patch.yaml`;
  - `cassandra-rackdc.properties`, with the DC set to `--dc` (it becomes `${DATACENTER}` after #1097);
  - the scripts.
- **Claim layout:**
  - Data stays at `/var/lib/cassandra/{data,commitlog,hints,saved_caches}`.
  - The cache is at `/var/lib/cassandra/.edl/artifacts/sha256-<hex>/`, with a `.complete` marker.
  - Older versions are kept, so a rollback is a cache hit. There is no pruning.
- **Pod env:** `EDL_TARBALL_SHA256` and `EDL_CASSANDRA_BUILD`, and `EDL_TARBALL_URL` and `EDL_TARBALL_FALLBACK_URL` read from the `cassandra-pods-artifact` Secret (a catalog build is signed again on every `upgrade`, and a URL in the pod template would roll every pod each time), plus the annotation `easydblab/config-hash`. The annotation follows the repo's "restarts by config hash" rule, so a patch edit rolls the pods too.
- **Pod security:** `securityContext.fsGroup: 999`. Every container meets Pod Security "restricted" (#1095).

## Key interfaces

```kotlin
/** Extra variables and files a kit's start gets from Kotlin, named in kit.yaml `contributors:`. */
interface KitVariableContributor {
    val name: String
    fun contribute(args: Map<String, String>, state: ClusterState, kitDir: File): KitContribution
}
data class KitContribution(val variables: Map<String, String>, val files: Map<String, String>)

@JvmInline value class Sha256(val hex: String) { init { require(hex.matches(Regex("[0-9a-f]{64}"))) } }
data class TarballArtifact(val url: String, val fallbackUrl: String?, val sha256: Sha256, val build: String, val java: String)
```

**Kit args:**

- `--version` takes the names `cassandra use` resolves.
- `--build <ref>` resolves only from cassandra-builds, so `5.0` (an ASF release line) and `5.0-HEAD` (a nightly) cannot be confused.
- `--java` is optional. Its default comes from `release-lines.yaml`.
- `--image` overrides all three.
- `--cpus` is new (D8).

**New kit command:** `cassandra-pods upgrade --version|--build` (D7).

**Failures.** Each of these happens before any resource is created and names its source:

- an unknown version or ref;
- no digest available;
- a presign failure;
- `--build` combined with `--image`.

**In the pod:** a sha256 mismatch fails `cassandra-fetch` and leaves no cache directory. The error names both the expected and the actual hash.

## Decisions

### D1. How the tarball reaches the pod (approved by the owner, 2026-11-20)

**Chosen:** a generic multi-arch JRE image, `ghcr.io/rustyrazorblade/cassandra-runtime` (D6 name), with one tag per JDK. It contains cqlsh's python3 and yq pinned like the AMI, and runs as uid 999.

- A `cassandra-fetch` init container downloads the tarball, checks the sha256 the CLI resolved, and caches the tree on the claim at `.edl/artifacts/sha256-<hex>/.complete` with an atomic rename.
- A cache hit makes no network call, which is the strongest form of the "no second download" criterion.
- A nightly rebuilt under the same name has a new digest, so it becomes an env change and rolls the StatefulSet.
- `--image` keeps today's path.

**Rejected:**

- (b) Download by URL and cache by URL. Simpler, but a rebuilt nightly is either silently stale or downloaded again every time.
- (c) A per-version image built locally (as `PackerImage` does) and pushed to a registry. This brings back the image pipeline #961 removes, and needs a registry on every environment.
- (d) Upstream `eclipse-temurin` with no image of our own, plus a tools init container that carries yq. There is no new artifact to publish, but the pod has no cqlsh and the tests lose in-pod `cqlsh`.

### D2. How the CLI's resolution reaches the kit (approved by the owner, 2026-11-20)

**Chosen:** an in-process `KitVariableContributor` interface, named in `kit.yaml` as `contributors: [cassandra-artifact]`. `CassandraArtifactContributor` is the only implementation, and the engine stays generic.

- Errors are typed, no second JVM starts, and nothing races `state.json`.
- It works the same from the REPL and the MCP server.

**Rejected:**

- The kit script runs `"$EASY_DB_LAB_EXEC" cassandra resolve`. This needs no engine change, but it starts a CLI inside the CLI, which no kit does today.
- Rebuild the kit as Kotlin fabric8 builders. This is the biggest change, and kits are template-based by convention.

### D3. How the VM path's scripts reach the pod (approved by the owner, 2026-11-20)

**Chosen:** `processResources` copies the scripts from `packer/cassandra/` into the kit's resources at build time, as it already does for the JMX rules (`build.gradle.kts:1183-1190`). They ship in the kit's ConfigMap.

- There is one source of truth. The CLI and the scripts ship in the same distribution, so they never disagree.
- The image holds only the JRE and tools, so it rarely changes.
- **This departs from the issue's wording**, "packaged in the image". The owner accepted the departure.

**Rejected:**

- Bake the scripts into the image. Every script change would then need a new image tag, a publish, and a pin bump before a Homebrew release.

### D4. The merge layers (approved by the owner, 2026-11-20)

**Chosen:** merge in this order, through the shared POSIX library `packer/cassandra/lib/edl-cassandra-config.sh`:

1. the tarball's `cassandra.yaml`;
2. the workspace `cassandra.patch.yaml`, with the common keys and the user's edits;
3. the kit placement patch;
4. the per-pod addresses.

`patch-config` keeps its output contract.

- Placement comes after the user's patch, so the VM-specific keys that `WriteConfig` writes (`Ec2Snitch`, the `/mnt/db1` paths, the seed IPs) are always replaced.
- The user's tuning reaches both paths.
- Criterion 3 holds by construction, because only addresses, ports, paths and the snitch come from placement.
- Off AWS, `up` does not write `cassandra.patch.yaml` today, so the contributor writes the common patch itself when the file is missing.
- `cassandra.in.sh` gains env overrides and must stay dash/POSIX.

**Rejected:**

- Split the workspace file into a common file and a VM file. This is cleaner, but it changes the `write-config` and `update-config` UX.

### D5. `--version` and `--build` (approved by the owner, 2026-11-20)

**Chosen:** both, on one resolver. `--build <ref>` resolves from cassandra-builds only.

**Rejected:**

- `--version` alone, matching `cassandra use`.

### D6. The image name (approved by the owner, 2026-11-20)

**Chosen:** `ghcr.io/rustyrazorblade/cassandra-runtime`.

**Rejected:**

- Reusing the `cassandra` package, which #961 deletes.

### D7. Changing the version on a running kit (approved by the owner, 2026-11-20)

**Chosen:** a new kit-declared command, `cassandra-pods upgrade --version|--build`. It:

1. re-resolves the artifact;
2. updates the kit's recorded `CASSANDRA_VERSION` (or build), through a small engine call that rewrites one resolved arg;
3. applies the StatefulSet;
4. waits for the rollout and then for UN.

It refuses a major-version downgrade, matching the docs.

**Rejected:**

- Allow `start` with args on a running kit. This blurs what `start` means.

### D8. Memory and CPU defaults (approved by the owner, 2026-11-20)

**Chosen:** new placement variables from the cluster contract: `DB_NODE_ALLOCATABLE_MIB`, `DB_NODE_ALLOCATABLE_CPU` and `GUARANTEED_PODS`.

- `--memory` defaults to about 75% of a db node's allocatable memory when `GUARANTEED_PODS=true` (owned nodes), and to `3Gi` elsewhere. The 75% is a starting figure to tune. The `3Gi` floor is where the kit README says testing stopped.
- `--cpus` defaults to the node's allocatable CPUs minus 1, as an integer. Where Guaranteed pods are required, the CPU request equals the limit, so the QoS comment in the template becomes true. Elsewhere there is no CPU limit.

**Rejected:**

- Keep fixed defaults and document them. Simpler, but it breaks the A/B grading in #1098.

### D9. The wording of acceptance criterion 3 (approved by the owner, 2026-11-20)

**Chosen:** amend criterion 3 to read "cassandra.yaml differs only in addresses, ports, paths and the snitch". The kit's `native_transport_port` is 30942, the NodePort value. It cannot match the VM's 9042 without host networking, which #1101 forbids.

**Rejected:**

- Keep the wording as written. No design can meet it under #1101.

### D10. How a patch edit reaches running pods (decided by the owner, 2026-11-22)

**Chosen:** amend the spec so that `upgrade` applies a `cassandra.patch.yaml` edit to running pods. The kit keeps `collision-check: true`. A patch edit reaches running pods through `upgrade` with the running `--version` or `--build`, which rolls them one at a time on the new config hash, or through `stop` then `start`. The scenario "Start on a running kit is refused" stays. "Nothing changed, nothing rolls" is now an `upgrade` with the running version and no edit.

**Why:** `start` is collision-checked, so it never reaches a running kit, and D7 kept what `start` means: it starts a kit that is not running. Letting `start` re-apply on a running kit is the alternative D7 rejected.

**Rejected:**

- Turn off the collision check so `start` re-applies. It undoes D7, and a second `start` would race the running pods' claims.

## Known differences outside `cassandra.yaml`

- **Heap.** The kit keeps setting the heap from the container limit, as it does today. The VM uses `cassandra-env.sh`'s default, which is based on host memory.
- **Per-version config directory.** The VM's per-version `<version>/` directory from `download-config` (the JVM options files) does not reach the kit in v1.

## Impact on #961 and #1036

- **#961:**
  - Once this lands, the kit no longer needs `build-cassandra-ref.yml`, `.github/cassandra-image/` or the GHCR `cassandra` package, so #961 can delete them. Land #1099 first; until then, `--image` still works with the old images.
  - When #961 deletes `resolve-build-plan.sh`, `release-lines.yaml`'s `build_java` loses its `ReleaseLinesBuildPlanTest` cross-check. #961 must handle that.
  - The `cassandra-branch-publishing` requirement "GHCR image is a drop-in for the Docker Official cassandra image" goes away with #961, not in this change.
- **#1036:**
  - This design replaces its Cassandra half; `cassandra-runtime` is the multi-arch artifact.
  - Its criterion "the check fails if either platform is missing" applies to `cassandra-runtime`, as a gate in the publish job.
  - s3manager stays in #1036.
  - Local K3s-in-Docker needs the multi-arch preload path (#1041, B12).

## Test strategy

**Unit tier (`./gradlew test`, no Docker):**

- `CassandraConfigPatch`: given a state, the `Vm` placement and the `Pods` placement each produce exactly their own seeds, directories and snitch.
- `TarballDigests`:
  - parses ASF checksum files, in both the hash-only and the `hash  filename` form;
  - parses the GitHub `digest` field, including missing and malformed values;
  - parses the catalog manifest;
  - `Sha256` rejects bad input.
- `CassandraArtifactContributor`, with a fake `VersionSources`:
  - `--build` never resolves from ASF;
  - `--image` skips resolution;
  - a missing digest fails before anything is created.
- Shell tests, on the existing `run-with-bashes` / `testCassandraScripts` harness:
  - `fetch-tarball.sh`: a cache hit makes zero curl calls (the stub fails if it is called); a mismatch leaves no cache directory; an interrupted extract leaves no `.complete`.
  - `edl_merge_cassandra_yaml` and `patch-config`: the success marker and exit codes are unchanged.
  - `cassandra.in.sh` overrides run through a real `/bin/sh`, by extending `edl-cassandra-agents.test.sh`.

**Integration tier (`./gradlew integrationTest`, TestContainers):**

- **Config parity.** Run the real `cassandra-runtime` image, built from its Dockerfile like the Pyroscope image in `CassandraPodsAnywhereIntegrationTest`, against a real tarball's `cassandra.yaml`. Render it once with the VM layers and once with the pod layers. Assert that the set of keys that differ is a subset of {addresses, ports, paths, `endpoint_snitch`, `seed_provider`}.
- **K3s**, extending `CassandraPodsAnywhereIntegrationTest`:
  - The tarball path reaches UN and answers CQL on the NodePort.
  - Cache proof: stop the kit, then start it again with the same sha256 but the URL pointed at an unreachable host. It must still reach UN, which proves no download happened.
  - An `upgrade` with a different digest changes the pod template and gives a new `cassandra_build` label.
  - The namespace enforces Pod Security "restricted".
- **The `--image` path** keeps its existing test.

**Lab (AWS):** a plan in `test-plans/` for the 3-node rolling upgrade under QUORUM load and for the start on arm64. A single-node K3s container cannot hold three anti-affine 3Gi pods.

**Docs** updated in the same change:

- `docs/user-guide/cassandra-pods.md`: the new args, `upgrade`, the cache and config parity;
- `docs/development/kits.md`: contributors;
- the kit's `CLAUDE.md`.

## Risks / Trade-offs

- **[`cassandra.in.sh` is sourced by `/bin/sh` (dash)]** Any bashism added with the new overrides stops Cassandra from starting on VMs. Mitigation: extend the real-`/bin/sh` coverage in `edl-cassandra-agents.test.sh` to the overrides.
- **[A presigned URL expires]** A pod rescheduled later with a cold cache fails `cassandra-fetch`. Mitigation: the error names the expiry and says to run `upgrade` again, or `stop` then `start`. The TTL is in `Constants`.
- **[Downloads during a roll]** Each pod downloads its tarball while it is down, which lengthens its outage. QUORUM still holds with RF=3, because only one pod is down at a time. Pre-fetching is YAGNI.
- **[3.x tarballs ship a Python 2 cqlsh]** In-pod `cqlsh` fails on 3.0 and 3.11. CQL from the operator's machine is unaffected. Mitigation: document it.
- **[Half-extracted cache]** An extract killed partway through is never used: only `.complete` is checked, and the rename into place is atomic.
- **[Stale `cassandra.in.sh` snippet]** The snippet is applied through `CASSANDRA_INCLUDE` and never written into the cache, so a CLI upgrade never runs a stale snippet.
- **[Patch edit rolls the cluster]** The config-hash annotation restarts every pod when `cassandra.patch.yaml` changes. This is intended, and follows the repo's restarts-by-config-hash rule.
- **[Old tarballs accumulate on the claim]** About 50 MB per version. Accepted; it makes a rollback a cache hit.
- **[`--memory` default changes]** The old `4Gi` default is replaced by the environment-sized one. The clusters are ephemeral, so no migration is needed.
