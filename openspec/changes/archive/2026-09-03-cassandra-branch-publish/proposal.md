## Why

Testing an in-development Apache Cassandra branch in a lab (or local docker-compose) today requires a manual local build or waiting on the nightly tarball matrix. There is no on-demand, self-service way to turn an arbitrary Cassandra git ref into a published, reusable Docker image **and** binary tarball, which makes iterating on patches/branches slow. (Issue #729.)

## What Changes

- Add a new on-demand GitHub Actions workflow (`build-cassandra-ref.yml`) triggered via `workflow_dispatch` that builds a single arbitrary Apache Cassandra git ref and publishes **two** artifacts from one build:
  - A **GHCR Docker image** that is a drop-in replacement for the Docker Official `cassandra` image (`cassandra:5`) — same `docker-entrypoint.sh` contract, `CASSANDRA_*` env vars, exposed ports, and `/var/lib/cassandra` volume.
  - A **binary tarball** attached to a per-build GitHub release, consumable by the existing `cassandra_versions.yaml` + `install_cassandra.sh` path.
- Add a small repo-owned image-assembly directory (`.github/cassandra-image/`) containing a Dockerfile and a vendored copy of the official `docker-entrypoint.sh`; the branch-built tarball is injected onto an `eclipse-temurin` JRE base.
- Workflow inputs: required `ref` (branch/tag/SHA); optional `repo` (default `apache/cassandra`, for forks); optional `jdk` (override the build JDK); optional `base_image` (override the runtime JRE base image the container is built `FROM`).
- Image tags: an immutable `sha-<short>` tag plus a moving sanitized-ref tag; never `latest`. Tarball + release named with the version and short SHA so different refs never collide.
- The workflow surfaces the pullable image reference, the tarball URL, and the resolved version + commit SHA in the run summary, and fails fast (publishing nothing) on a bad ref or a failed build.

## Capabilities

### New Capabilities
- `cassandra-branch-publishing`: an on-demand CI capability that builds an arbitrary Cassandra git ref and publishes a `cassandra:5`-compatible GHCR image plus a consumable binary tarball, with operator-selectable build JDK and runtime base JVM image.

### Modified Capabilities
<!-- None. This is a self-contained CI/build capability; it does not change existing CLI or cluster-provisioning specs. -->

## Impact

- **New files:** `.github/workflows/build-cassandra-ref.yml`, `.github/cassandra-image/Dockerfile`, `.github/cassandra-image/docker-entrypoint.sh` (vendored from the Docker Official `cassandra` image).
- **CI/registry:** publishes to GHCR under this repo's namespace using `GITHUB_TOKEN` (requires `packages: write` + `contents: write`); creates GitHub releases for tarballs.
- **No changes** to the Kotlin CLI, cluster provisioning, the AMI/Packer pipeline, or the existing `nightly-cassandra-build.yml`. Consumers may later reference produced artifacts via `cassandra_versions.yaml`, but wiring that is out of scope here.
- **Sibling:** issue #730 (.deb variant) is separate and unaffected.
