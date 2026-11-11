## 1. Image assembly scaffolding

- [x] 1.1 Create `.github/cassandra-image/docker-entrypoint.sh` as a vendored copy of the Docker Official `cassandra` image entrypoint (strip the GPG/archive-download steps that don't apply to a from-source tarball)
- [x] 1.2 Create `.github/cassandra-image/Dockerfile` reproducing the DOI layout (`CASSANDRA_HOME=/opt/cassandra`, `CASSANDRA_CONF` symlink, `VOLUME /var/lib/cassandra`, `EXPOSE 7000 7001 7199 9042 9160`, gosu, jemalloc), with `BASE_IMAGE` and a tarball path as build args, injecting the tarball into `/opt/cassandra`, `CMD ["cassandra","-f"]`
- [ ] 1.3 Build the image locally from a known-good tarball and confirm it starts and serves CQL on 9042 (validates the entrypoint/layout contract before wiring CI)

## 2. Resolve job

- [x] 2.1 Add `.github/workflows/build-cassandra-ref.yml` with a `workflow_dispatch` trigger and inputs: required `ref`; optional `repo` (default `apache/cassandra`); optional `jdk`; optional `base_image`
- [x] 2.2 Implement the `resolve` job: validate the ref via `git ls-remote` (fail fast naming a bad ref), output full + short SHA
- [x] 2.3 Derive the `version` string from `build.xml` `base.version`; compute auto-mapped build JDK and runtime base image, applying `jdk`/`base_image` overrides when supplied
- [x] 2.4 Compute outputs: sanitized-ref tag (reuse the `publish-container.yml` transform), `sha-<short>` tag, image repo, tarball asset name, release tag — and assert `latest` is never produced

## 3. Build job

- [x] 3.1 Implement the `build` job: check out the exact resolved SHA from `repo`, set up the build JDK
- [x] 3.2 Run `ant artifacts` to produce the binary tarball (runner-native, not `.build/docker/`, so it works on 4.0/4.1)
- [x] 3.3 Upload the tarball as an intra-workflow artifact for the publish job

## 4. Publish job

- [x] 4.1 Implement the `publish` job with `needs: [resolve, build]` and `permissions: { packages: write, contents: write }`
- [x] 4.2 Download the tarball; create a per-build GitHub release (`cassandra-<version>-<short-sha>`) and attach the tarball asset
- [x] 4.3 `docker build` the `.github/cassandra-image/` Dockerfile with the tarball + resolved `base_image` build args; authenticate to GHCR via `GITHUB_TOKEN`; push `sha-<short>` + sanitized-ref tags
- [x] 4.4 Smoke test: run the pushed image, poll 9042 with a bounded timeout, run a CQL query against `system.local`; fail the run if it does not serve CQL
- [x] 4.5 Write the run summary (`$GITHUB_STEP_SUMMARY`): pullable image reference, tarball URL, resolved version, commit SHA

## 5. Verification

### Automated (CI)

- [x] 5.0 Unit-test the pure build-plan logic (`.github/cassandra-image/resolve-build-plan.sh`):
  version → JDK / ant-flags / base-image auto-mapping (4.x/5.0/trunk), `jdk` and
  `base_image` overrides winning over auto-map, ref-tag sanitization
  (`feature/foo` → `feature-foo`), the `latest`-tag guard, deterministic
  non-colliding `sha-<short>` / tarball / release names, and empty-version
  fail-fast. Runs via `./gradlew testCassandraBuildPlan` and the
  `test-cassandra-build-plan` job in `packer-test.yml`
  (`.github/cassandra-image/resolve-build-plan.test.sh`).
  - Covers spec scenarios: Auto-mapped JDK and base image; Operator overrides the
    build JDK; Operator overrides the base JVM image; Ref with invalid tag
    characters is sanitized (incl. `latest` guard); Different refs produce
    different artifacts.
  - Covers the 4.x `-Duse.jdk11=true` build-flag requirement so the stated
    "4.0 → trunk" coverage actually compiles 4.x under the auto-mapped JDK 11.

- [x] 5.0b Unit-test the ref-resolution logic
  (`.github/cassandra-image/resolve-ref.sh`): a resolvable branch/tag yields its
  SHA (and a 12-char short SHA), a 40-hex input is accepted as a raw SHA with no
  remote match, and an unresolvable ref (including a too-short hex string) exits
  non-zero with a message naming the bad ref. `git ls-remote` is injected
  (`LS_REMOTE_CMD`) and stubbed, so the test needs no network. Runs via
  `./gradlew testCassandraResolveRef` and the `test-cassandra-build-plan` job in
  `packer-test.yml` (`.github/cassandra-image/resolve-ref.test.sh`). The workflow
  sources this same function, so the tested logic is what actually runs.
  - Covers spec scenario: Nonexistent ref fails fast (the fail-fast / bad-ref
    naming behavior previously only exercised by the manual gate 5.3).

### Structural / by-inspection (recorded acceptance arguments)

- [x] 5.5 **Build failure blocks all publishing** — guaranteed structurally by
  `publish: needs: [resolve, build]`; a failing `build` short-circuits `publish`,
  so no release is created and no image is pushed. No separate test asserts this;
  the job dependency is the acceptance argument.
- [x] 5.6 **Authenticated GHCR publishing with no trigger-time secrets** — the
  `publish` job declares `permissions: { packages: write, contents: write }` and
  authenticates with `docker/login-action` using `secrets.GITHUB_TOKEN`; no input
  or repository secret is entered at trigger time. Acceptance is by inspection of
  the permissions + login wiring.

### Manual (acceptance gate before archive)

Items below exercise a real `workflow_dispatch` run (network + Docker + GHCR) and
are the manual acceptance gate; they cannot run in unit tests:

- [ ] 5.1 Trigger against a stable ref (e.g. `cassandra-5.0`) and confirm both artifacts publish, the in-workflow CQL smoke test passes (gating publication), and the summary is correct
- [ ] 5.2 Trigger against `trunk` to exercise the newest-JDK / `base_image` auto-mapping path
- [x] 5.3 Nonexistent-ref fail-fast (resolve step names the bad ref) is covered
  automatically by 5.0b (`resolve-ref.test.sh`), which exercises the same
  `resolve_ref` function the workflow sources. A live `workflow_dispatch` run is
  no longer required to accept this scenario.
- [ ] 5.4 Confirm a published tarball URL installs via `cassandra_versions.yaml` + `install_cassandra.sh` (single top-level `*cassandra*` dir)
- [ ] 5.7 Trigger with a fork `repo` input and confirm the ref resolves and builds from the fork
- [ ] 5.8 Confirm the produced image is a drop-in for `cassandra:<n>` in a compose service using the same `CASSANDRA_*` env vars (CQL on 9042)

## 6. Documentation

- [x] 6.1 Document the workflow (inputs, where artifacts land, how to consume the image and tarball) in the appropriate `docs/` page and note the `latest`-reservation rule
