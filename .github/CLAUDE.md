# GitHub CI

## Container Tagging Rules

The `publish-container.yml` workflow publishes containers for all branches and tags. Do NOT restrict which branches trigger the workflow — branch containers are intentional for testing.

Tagging scheme:
- **main branch** → `latest`
- **feature branches** → sanitized branch name (e.g., `stress-dashboard`)
- **version tags** (`v*`) → `$VERSION` and `v$VERSION` (e.g., `1.0.0` and `v1.0.0`). NEVER include `latest`.

Only the main branch should ever produce the `latest` tag.

## Building an Arbitrary Cassandra Ref

The `build-cassandra-ref.yml` workflow (`workflow_dispatch`) turns an arbitrary
Apache Cassandra git ref into a GHCR Docker image (a drop-in for the Docker
Official `cassandra` image) plus a binary tarball on a per-build GitHub release.
It runs three sequential jobs — `resolve` → `build` → `publish` — so a build
failure publishes nothing. It also never produces `latest` (reserved per the
tagging rules above).

The image-assembly files live in `.github/cassandra-image/`:
- `Dockerfile` — reproduces the Docker Official image layout but injects the
  from-source tarball (`TARBALL` build arg) onto the `BASE_IMAGE` JRE base.
- `docker-entrypoint.sh` — a byte-identical vendored copy of the upstream
  entrypoint. Re-vendor in a deliberate PR if upstream changes; never hand-edit.
- `resolve-build-plan.sh` / `resolve-ref.sh` — pure, sourceable shell functions
  for the version → JDK / ant-flags / base-image / tag mapping and ref
  resolution, unit-tested by their `*.test.sh` siblings (run via
  `./gradlew testCassandraBuildPlan` / `testCassandraResolveRef`, and the
  `test-cassandra-build-plan` job in `packer-test.yml`).

User-facing usage and consumption are documented in
`docs/development/building-cassandra-refs.md`; the design rationale lives in the
`cassandra-branch-publish` OpenSpec change.

## Publishing the Pyroscope Java Agent Image

The `publish-pyroscope-agent` job in `publish-container.yml` builds
`docker/pyroscope-java-agent/` and pushes it to
`ghcr.io/rustyrazorblade/pyroscope-java-agent:<version>`. It never overwrites a
published tag. `.github/pyroscope-agent/image-published.sh` makes that decision:
- a tag that inspects is skipped;
- only the registry's `not found` leads to a push;
- any other inspect error fails the job.

It is unit-tested with a stubbed `docker` by `image-published.test.sh` (`./gradlew testPyroscopeAgentPublished`, part of `testScripts`).

## Publishing the Cassandra Runtime Image

The `publish-cassandra-runtime` job in `publish-container.yml` builds
`docker/cassandra-runtime/` once per JDK of its matrix (8, 11, 17, 21) for
`linux/amd64` and `linux/arm64`, and pushes
`ghcr.io/rustyrazorblade/cassandra-runtime:<revision>-jre<N>`. The revision is the
Dockerfile's `RUNTIME_REVISION`, kept equal to
`Constants.Kit.CassandraPods.RUNTIME_IMAGE_REVISION` by `CassandraRuntimeImageTest`;
a Dockerfile change bumps both. It runs on the same trusted refs as the Pyroscope job
and skips a published tag with the same `image-published.sh`. After the push,
`.github/cassandra-runtime/platforms-present.sh` fails the job when the tag lacks
either platform (`./gradlew testCassandraRuntimePlatforms`, part of `testScripts`).

Step outputs and event values reach `run:` scripts only through `env:`, never
as `${{ }}` inside the script.
