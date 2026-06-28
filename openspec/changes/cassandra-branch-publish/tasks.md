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

- [ ] 5.1 Trigger against a stable ref (e.g. `cassandra-5.0`) and confirm both artifacts publish and the summary is correct
- [ ] 5.2 Trigger against `trunk` to exercise the newest-JDK / `base_image` auto-mapping path
- [ ] 5.3 Trigger with a nonexistent ref and confirm fail-fast with nothing published
- [ ] 5.4 Confirm a published tarball URL installs via `cassandra_versions.yaml` + `install_cassandra.sh`

## 6. Documentation

- [x] 6.1 Document the workflow (inputs, where artifacts land, how to consume the image and tarball) in the appropriate `docs/` page and note the `latest`-reservation rule
