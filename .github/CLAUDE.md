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
