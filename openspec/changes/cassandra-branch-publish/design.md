## Context

easy-db-lab needs a self-service way to turn an arbitrary Apache Cassandra git ref into a reusable, runnable artifact for testing. Today the only paths are a manual local build or the nightly tarball matrix (`nightly-cassandra-build.yml`). The repo already has the relevant prior art: the nightly workflow (builds `apache/cassandra` refs with `ant artifacts`, publishes tarballs to a GitHub release) and `publish-container.yml` (GHCR auth, tag sanitization, pull-and-run smoke test). `.github/CLAUDE.md` reserves `latest` for the main CLI image.

A key constraint discovered during design: **Apache Cassandra ships no runnable server image in-tree.** The `.build/docker/` tooling is a build harness (it runs `ant artifacts` and emits a tarball), exists only on 5.0/trunk, and requires docker-in-docker. The only runnable Cassandra-project server image that exists is the Docker Official Image (`docker-library/cassandra`). Its contract is a `docker-entrypoint.sh` (byte-identical across 4.1/5.0) plus a fixed filesystem layout.

## Goals / Non-Goals

**Goals:**
- One `workflow_dispatch` workflow that builds a single arbitrary ref and publishes both a GHCR image and a consumable tarball from one build.
- The image is a drop-in for the Docker Official `cassandra:5` image.
- Operator can select the build JDK and the runtime base JVM image per run (required for multi-branch use).
- Works across ref families 4.0 → trunk; fails fast on bad input; publishes nothing on failure.

**Non-Goals:**
- The `.deb` variant (#730).
- Wiring the lab to auto-deploy the produced artifacts (AMI rebuild, containerized cluster mode).
- Multi-arch images (amd64 only for now).
- Replacing the existing nightly workflow.
- Publishing to Docker Hub or any registry other than GHCR.

## Decisions

### D0. Image assembly — vendor the DOI scaffolding, inject our tarball (owner decision: Option A)
A small repo-owned `.github/cassandra-image/Dockerfile` reproduces the Docker Official `cassandra` layout (`CASSANDRA_HOME=/opt/cassandra`, `CASSANDRA_CONF=/etc/cassandra` symlink, `VOLUME /var/lib/cassandra`, `EXPOSE 7000 7001 7199 9042 9160`, gosu step-down, jemalloc) but, instead of downloading + GPG-verifying a released tarball, `COPY`s in the tarball this workflow just built and extracts it into `/opt/cassandra`. The `docker-entrypoint.sh` is a vendored copy of the official file. The JRE base is a build arg.

- **Alternatives considered:**
  - *Use Cassandra's in-tree Docker tooling* — rejected: it is a tarball harness, ships no server image, is absent on 4.0/4.1, and needs docker-in-docker.
  - *`FROM cassandra:5`, replace the install* — rejected: brittle in-image surgery, inherits a JRE pinned to the base tag (wrong for trunk).
- **Why:** Option A is the only approach that reproduces the exact `cassandra:5` contract the owner depends on, builds on every ref family, and avoids docker-in-docker. The drift risk (official entrypoint changes) is small and a re-vendor is a deliberate, reviewable PR.

### D1. Three sequential jobs — resolve → build → publish
- `resolve`: validate the ref (`git ls-remote`), pin to full + short SHA, derive `version` (from `build.xml` `base.version`), select build JDK and runtime base image, compute all tags/names. The only "thinking" job; bad-ref fail-fast lives here.
- `build`: check out the exact SHA, run `ant artifacts` on the runner (preferred over `.build/docker/build-artifacts.sh`, which is absent on 4.0/4.1 and needs docker-in-docker), upload the tarball as an intra-workflow artifact.
- `publish` (`needs: [resolve, build]`): attach tarball to a per-build release; `docker build` the image with the tarball + base-image build args, push tags; run the CQL smoke test; write the run summary.
- **Why:** a hard job dependency makes "build fails → nothing published" structural rather than best-effort.

### D2. Operator-selectable build JDK and runtime base image
Two independent knobs, both auto-mapped from the resolved version with optional overrides:
- `jdk` input → build JDK for `ant artifacts` (4.x→11, 5.0→11/17, trunk→newest supported).
- `base_image` input → the runtime JRE image the container is built `FROM` (default `eclipse-temurin:<N>-jre` per version).
- **Why:** different branches need different JREs; an explicit per-run override avoids editing the workflow when upstream bumps the floor, and lets the owner pin a specific distro. Alternative (single fixed JDK) rejected — breaks the moment trunk's floor moves.

### D3. Tagging and release naming
- Image tags: immutable `sha-<short>` + a moving sanitized-ref tag (sanitization reuses the `publish-container.yml` transform); never `latest` (reserved per `.github/CLAUDE.md`).
- Tarball asset: `apache-cassandra-<version>-<short-sha>-bin.tar.gz`; one GitHub release per build (`cassandra-<version>-<short-sha>`), additive, manual pruning.
- **Why:** the SHA guarantees "different refs → different artifacts" regardless of the moving ref tag; per-build releases match the no-silent-overwrite requirement and the `cassandra_versions.yaml` pin-a-URL consumption model.

### D4. Single architecture (amd64)
Build amd64 only; lab nodes are x86 (`i4i.xlarge`). Multi-arch (buildx + QEMU) is a one-line addition if arm64 local dev is later needed.

## Risks / Trade-offs

- **Vendored `docker-entrypoint.sh` drifts from upstream** → the CQL smoke test catches contract breaks; re-vendoring is a deliberate PR.
- **JRE/version mismatch (e.g. trunk built with too-old a JDK, or run on too-old a JRE)** → the smoke test (container must actually serve CQL) is the guard; D2 override is the fix.
- **Smoke-test flakiness from Cassandra startup time** → poll 9042 with a bounded timeout and fail loudly; never skip the test.
- **Partial publish within `publish` (release created but image push fails, or vice-versa)** → re-running with the same SHA is idempotent on the image (same tag) and additive on the release; no cleanup logic. Accepted as fix-forward.
- **`base.version` is not unique across two trunk builds** → the short SHA in every tag/asset name disambiguates.

## Migration Plan

Additive only — new workflow + new `.github/cassandra-image/` files. No existing workflow, CLI, or cluster behavior changes. Rollback = delete the workflow; nothing else depends on it. Produced GHCR images/releases are inert until a consumer references them.

## Open Questions

None blocking. Retention of accumulated per-build releases is left to manual pruning for now (low volume, ephemeral-cluster repo).
