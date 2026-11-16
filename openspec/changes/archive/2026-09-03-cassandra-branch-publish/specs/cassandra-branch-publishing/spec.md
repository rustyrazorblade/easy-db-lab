## ADDED Requirements

### Requirement: On-demand build of an arbitrary Cassandra ref

The workflow SHALL be triggered manually via `workflow_dispatch` and SHALL accept a required `ref` input (branch, tag, or commit SHA) and an optional `repo` input (default `apache/cassandra`) so that a fork can be built. It SHALL resolve the `ref` to an immutable commit SHA and derive the Cassandra version string before building.

#### Scenario: Valid ref builds and publishes both artifacts
- **WHEN** the workflow is triggered with a `ref` that exists in the source repo
- **THEN** it checks out that exact ref, builds it, and publishes both a GHCR Docker image and a binary tarball

#### Scenario: Nonexistent ref fails fast
- **WHEN** the supplied `ref` does not exist in the source repo
- **THEN** the workflow fails in the resolve step with a message naming the bad ref, and no image or tarball is published

#### Scenario: Fork repo is honored
- **WHEN** the workflow is triggered with a `repo` input pointing at a fork
- **THEN** the ref is resolved and built from that fork rather than `apache/cassandra`

### Requirement: Cassandra build failures publish nothing

The publish step SHALL depend on a successful build step, so that a compilation or artifact failure prevents any artifact from being published.

#### Scenario: Build failure blocks all publishing
- **WHEN** the Cassandra `ant artifacts` build fails
- **THEN** the workflow fails, and no image is pushed and no tarball/release is created

### Requirement: GHCR image is a drop-in for the Docker Official cassandra image

The produced Docker image SHALL be published to GHCR and SHALL honor the Docker Official `cassandra` image contract: the `docker-entrypoint.sh` behavior, the `CASSANDRA_*` environment variables, the exposed ports (7000/7001/7199/9042/9160), and the `/var/lib/cassandra` data volume. The image SHALL be assembled by injecting the branch-built tarball onto a JRE base image.

#### Scenario: Image substitutes for cassandra:N in compose
- **WHEN** the produced image replaces `cassandra:<n>` in a docker-compose service using the same `CASSANDRA_*` environment variables and no other changes
- **THEN** the container starts and accepts CQL on port 9042

#### Scenario: Smoke test gates publication
- **WHEN** the image is built during the workflow
- **THEN** the workflow starts the image, polls port 9042 with a bounded timeout, and runs a CQL query against `system.local`, failing the run if the container does not serve CQL

### Requirement: Operator-selectable build JDK and runtime base image

The workflow SHALL select a build JDK and a runtime JRE base image automatically from the resolved Cassandra version (e.g. 4.x→11, 5.0→17, trunk→newest supported), and SHALL allow the operator to override each independently via an optional `jdk` input (build JDK) and an optional `base_image` input (the runtime JRE image the container is built `FROM`).

#### Scenario: Auto-mapped JDK and base image
- **WHEN** the workflow runs without `jdk` or `base_image` inputs
- **THEN** it selects the build JDK and runtime base image mapped from the resolved version

#### Scenario: Operator overrides the base JVM image
- **WHEN** the operator supplies a `base_image` input
- **THEN** the produced container is built `FROM` that image instead of the auto-mapped JRE base

#### Scenario: Operator overrides the build JDK
- **WHEN** the operator supplies a `jdk` input
- **THEN** the ref is compiled with that JDK instead of the auto-mapped one

### Requirement: Deterministic, non-colliding tags and release artifacts

The image SHALL be tagged with both an immutable `sha-<short>` tag and a sanitized-ref tag, and SHALL never use the `latest` tag. Tag sanitization SHALL replace tag-invalid characters consistently with the existing `publish-container.yml` transform. The tarball SHALL be attached to a per-build GitHub release named with the version and short SHA so that distinct refs never overwrite each other's artifacts.

#### Scenario: Different refs produce different artifacts
- **WHEN** two builds are run from different refs
- **THEN** their image `sha-<short>` tags and their tarball/release artifacts differ, with no silent overwrite

#### Scenario: Ref with invalid tag characters is sanitized
- **WHEN** a ref contains tag-invalid characters (e.g. `feature/foo`)
- **THEN** the sanitized-ref image tag is a deterministic transform of the ref and the `latest` tag is never assigned

### Requirement: Authenticated GHCR publishing with no trigger-time secrets

The workflow SHALL authenticate to GHCR using the repository's `GITHUB_TOKEN` with `packages: write` and `contents: write` permissions, requiring no manually entered secrets at trigger time.

#### Scenario: Publishes using GITHUB_TOKEN
- **WHEN** the workflow runs
- **THEN** it authenticates to GHCR via `GITHUB_TOKEN` and requires no secrets to be entered when triggering the run

### Requirement: Run output surfaces consumable references

On success the workflow SHALL write to the run summary the pullable image reference, the tarball download URL, and the resolved Cassandra version and commit SHA.

#### Scenario: Summary lists artifacts
- **WHEN** the build completes successfully
- **THEN** the run summary shows the pullable image reference, the tarball URL, and the resolved version and commit SHA

### Requirement: Published tarball is consumable by the lab install path

The published tarball SHALL extract to a single top-level `*cassandra*` directory so that its release URL can be added to `cassandra_versions.yaml` and installed via `install_cassandra.sh`.

#### Scenario: Tarball installs as a valid Cassandra
- **WHEN** the published tarball URL is added to `cassandra_versions.yaml` and installed via `install_cassandra.sh`
- **THEN** it unpacks into a single Cassandra directory and runs as a valid Cassandra install
