## Why

The Cassandra kit from #1038 (PR #1085) has two problems:

- **Branch builds lose their source after #961.** The kit runs a branch build only from an image (`--image`). #961 removes the Cassandra AMI and deletes `build-cassandra-ref.yml`, `.github/cassandra-image/` and the GHCR `cassandra` package, so after it a branch build exists only as a tarball.
- **The kit and the VM path run different configs.** The VM path renders `cassandra.yaml` and the JVM options with the packer scripts (`patch-config`, `cassandra.in.sh`). The kit uses the Docker image's defaults through `CASSANDRA_*` env vars. The same version on the same EC2 type is therefore not comparable.

Owner default, 2026-11-11: the tarball is the one Cassandra artifact on both paths, and `--image` stays as an escape hatch.

## What Changes

- **Runtime image.** A new generic multi-arch (amd64, arm64) JRE image, `ghcr.io/rustyrazorblade/cassandra-runtime`, with one tag per JDK (`<rev>-jre8|11|17|21`). It holds the JRE, cqlsh's python3, curl, procps, and yq pinned to the AMI's version. It runs as uid 999. The publish job fails when either platform is missing.
- **Tarball fetch and cache.** A new `cassandra-fetch` init container downloads the Cassandra tarball and checks its sha256 against the value the CLI resolved. It caches the tree on the claim at `.edl/artifacts/sha256-<hex>/`, with a `.complete` marker written by an atomic rename. A cache hit makes no network call. A checksum mismatch fails the pod and caches nothing.
- **Artifact resolution in the CLI.** `--version` (any name `cassandra use` resolves) and the new `--build <ref>` (cassandra-builds only) resolve through `CassandraVersionResolver` to a `TarballArtifact`: URL, fallback URL, sha256, build label and JDK. Builds from the `cassandra build` catalog get a presigned URL. An unknown version or ref, a missing digest, a presign failure, or `--build` together with `--image` fails before any resource is created.
- **Kit variable contributors.** A new in-process `KitVariableContributor` interface. A kit names its contributors in `kit.yaml` (`contributors: [cassandra-artifact]`), and the runner calls them before `start`. `CassandraArtifactContributor` is the only implementation. The engine stays generic.
- **Config parity with the VM path.** A `cassandra-render` init container builds `cassandra.yaml` with the shared POSIX library `packer/cassandra/lib/edl-cassandra-config.sh`. The merge order is: the tarball's `cassandra.yaml`, the workspace `cassandra.patch.yaml`, the kit placement patch, then the per-pod addresses. `patch-config` calls the same library and keeps its output contract. The VM path's scripts ship in the kit ConfigMap, copied from `packer/cassandra/` at build time. `cassandra.in.sh` gains env overrides and stays POSIX sh.
- **Upgrade.** A new kit command, `cassandra-pods upgrade --version|--build`. It re-resolves the artifact, updates the recorded arg, applies the StatefulSet, and waits for the rollout and for UN. It refuses a major-version downgrade. Pods restart one at a time.
- **Resources per environment.** New placement variables: `DB_NODE_ALLOCATABLE_MIB`, `DB_NODE_ALLOCATABLE_CPU` and `GUARANTEED_PODS`. On owned nodes, `--memory` defaults to about 75% of a db node's allocatable memory, else `3Gi`. The new `--cpus` defaults to allocatable minus 1, with request equal to limit where Guaranteed pods are required.
- **Restarts by config hash.** A config-hash annotation on the pod template rolls the pods when the patch changes. Every container meets Pod Security "restricted" (`fsGroup: 999`).
- `--image` keeps today's pod spec and bypasses the tarball path.
- **BREAKING** (kit args): `--memory` no longer defaults to `4Gi`, and `--version` no longer names a Docker Hub image tag.

## Capabilities

### New Capabilities

- `kit-variable-contributors`: in-process Kotlin contributors, named in `kit.yaml`, that add variables and files to a kit's `start` and its other commands.

### Modified Capabilities

- `cassandra-kit`: "Image selection" becomes "Artifact selection" (tarball by default, `--image` as the escape hatch). Adds the runtime image, the fetch and cache, config parity, upgrade, and resource defaults. The memory, telemetry, built-in-kit and docs requirements change to match.
- `cassandra`: `patch-config` and the kit share one merge library, and `cassandra.in.sh` takes env overrides with the VM defaults unchanged.
- `platform-substrate`: the template variable contract gains `DB_NODE_ALLOCATABLE_MIB`, `DB_NODE_ALLOCATABLE_CPU` and `GUARANTEED_PODS`.

## Impact

- Code:
  - `services/cassandraversions/` (`CassandraVersionResolver`, `CassandraBuildsRelease` digest parsing, new `TarballDigests`, `TarballArtifact`, `Sha256`);
  - `services/CassandraBuildCatalog.kt` (manifest sha256);
  - `ObjectStore.presignedGetUrl`;
  - new `configuration/cassandra/CassandraConfigPatch` and `CassandraPlacement`;
  - `commands/cassandra/WriteConfig.kt` becomes a thin caller of the patch builders;
  - the kit runner (`contributors:` in `kit.yaml`, `KitVariableContributor`, `CassandraArtifactContributor` in Koin, rewriting one recorded arg for `upgrade`);
  - `TemplateVariables` (the placement variables);
  - `Constants` (presign TTL, runtime image, cache paths).
- Shell:
  - new `packer/cassandra/lib/edl-cassandra-config.sh`;
  - `packer/cassandra/bin/patch-config`;
  - `packer/cassandra/cassandra.in.sh`;
  - the kit's `lib/check-start.sh`, `apply-statefulset.sh`, and new `fetch-tarball.sh`, `render-config.sh` and `upgrade.sh`;
  - `statefulset.yaml.template`, plus an `--image` template.
- Build: `build.gradle.kts` `processResources` copies the packer scripts into the kit resources.
- Image and CI: new `docker/cassandra-runtime/Dockerfile`, published from `.github/workflows/publish-container.yml` with a two-platform gate.
- Docs:
  - `docs/user-guide/cassandra-pods.md`;
  - `docs/development/kits.md` (contributors);
  - the kit's `CLAUDE.md`;
  - the root `CLAUDE.md`, if the kit's description there changes.
- Other issues:
  - This change replaces the Cassandra half of #1036.
  - Once it lands, #961 can delete `build-cassandra-ref.yml`, `.github/cassandra-image/` and the GHCR `cassandra` package. The `cassandra-branch-publishing` requirement "GHCR image is a drop-in for the Docker Official cassandra image" goes with them.
  - When #961 deletes `resolve-build-plan.sh`, `release-lines.yaml`'s `build_java` loses its `ReleaseLinesBuildPlanTest` cross-check. #961 has to handle that.
