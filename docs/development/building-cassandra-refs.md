# Building a Cassandra Ref On Demand

The **Build Cassandra Ref** workflow turns an arbitrary Apache Cassandra git ref
(branch, tag, or commit SHA) into two reusable artifacts in a single CI run:

1. A **GHCR Docker image** that is a drop-in replacement for the Docker Official
   `cassandra` image — same `docker-entrypoint.sh` contract, `CASSANDRA_*`
   environment variables, exposed ports (7000/7001/7199/9042/9160), and
   `/var/lib/cassandra` data volume.
2. A **binary tarball** attached to a per-build GitHub release, consumable by the
   lab install path (`cassandra_versions.yaml` + `install_cassandra.sh`).

This is the self-service alternative to a manual local build or waiting on the
nightly tarball matrix. It does not change cluster provisioning or the AMI/Packer
pipeline — the produced artifacts are inert until a consumer references them.

## Triggering the workflow

Run it from the Actions tab (`Build Cassandra Ref` → **Run workflow**) or via the
CLI:

```bash
gh workflow run build-cassandra-ref.yml \
  -f ref=cassandra-5.0
```

### Inputs

| Input | Required | Default | Purpose |
| --- | --- | --- | --- |
| `ref` | yes | — | Branch, tag, or commit SHA to build |
| `repo` | no | `apache/cassandra` | Source repo (`owner/name`); set this to build a fork |
| `jdk` | no | auto | Override the **build** JDK (e.g. `11`, `17`, `21`) |
| `base_image` | no | auto | Override the **runtime** JRE base image the container is built `FROM` |

When `jdk` / `base_image` are left blank they are auto-mapped from the resolved
Cassandra version: `4.x → JDK 11 / eclipse-temurin:11-jre`,
`5.0 → JDK 17 / eclipse-temurin:17-jre`, `5.1+`/`trunk` →
`JDK 21 / eclipse-temurin:21-jre`. Supply an override when a branch needs a
different JRE than its version maps to (for example when trunk's floor moves).

For 4.0/4.1 refs the build also passes `-Duse.jdk11=true` automatically: those
branches default their `ant` build to JDK 8 and need that flag to compile under
the auto-mapped JDK 11 (the same flag the Packer install path uses for 4.x). This
version → JDK / ant-flags / base-image / tag mapping lives in
`.github/cassandra-image/resolve-build-plan.sh` and is unit-tested by
`resolve-build-plan.test.sh` (run via `./gradlew testCassandraBuildPlan`).

## How it works

Three sequential jobs make "build fails → nothing published" structural:

1. **resolve** — validates `ref` with `git ls-remote` (fails fast, naming the bad
   ref, if it does not exist), pins it to a full + short commit SHA, reads
   `base.version` from `build.xml`, and computes the build JDK, runtime base
   image, image tags, tarball name, and release tag. The ref-resolution logic
   (ls-remote / raw-SHA fallback / fail-fast naming the bad ref) lives in
   `.github/cassandra-image/resolve-ref.sh` and is unit-tested by
   `resolve-ref.test.sh` (run via `./gradlew testCassandraResolveRef`) with the
   `git ls-remote` call stubbed, so the fail-fast path is covered without a live
   run.
2. **build** — checks out the exact resolved SHA, sets up the build JDK, runs
   `ant artifacts` on the runner, and uploads the tarball as an intra-workflow
   artifact.
3. **publish** (`needs: [resolve, build]`) — creates the per-build release with
   the tarball attached, builds the image from `.github/cassandra-image/` with
   the tarball + resolved base image, pushes the tags to GHCR, runs a CQL smoke
   test against the pushed image, and writes the run summary.

Authentication to GHCR uses the repository's `GITHUB_TOKEN`
(`packages: write` + `contents: write`) — no secrets are entered at trigger time.

## Where the artifacts land

- **Image**: `ghcr.io/<owner>/<repo>/cassandra`, tagged with both an immutable
  `sha-<short>` tag and a moving sanitized-ref tag. The `latest` tag is reserved
  for the main CLI image and is **never** produced here.
- **Tarball**: `apache-cassandra-<version>-<short-sha>-bin.tar.gz`, attached to a
  GitHub release tagged `cassandra-<version>-<short-sha>`.

The short SHA in every tag and asset name guarantees that distinct refs never
overwrite each other's artifacts. Accumulated per-build releases are pruned
manually.

The run summary surfaces the pullable image reference, the tarball download URL,
and the resolved version + commit SHA.

## Consuming the artifacts

### Docker image

Drop it into a compose file in place of `cassandra:<n>` using the same
`CASSANDRA_*` environment variables:

```bash
docker pull ghcr.io/<owner>/<repo>/cassandra:sha-<short>
```

### Tarball (lab install path)

Pin the release's tarball URL in `packer/cassandra/cassandra_versions.yaml`:

```yaml
- version: "my-branch"
  url: https://github.com/<owner>/<repo>/releases/download/cassandra-<version>-<short-sha>/apache-cassandra-<version>-<short-sha>-bin.tar.gz
  java: "17"
  python: "3.11.9"
```

`install_cassandra.sh` downloads the URL and expects it to unpack into a single
top-level `*cassandra*` directory, which the `ant artifacts` tarball satisfies.

## Image assembly

The image is assembled from repo-owned files in `.github/cassandra-image/`:

- `Dockerfile` — reproduces the Docker Official `cassandra` layout but injects the
  branch-built tarball (passed as the `TARBALL` build arg) onto the `BASE_IMAGE`
  JRE base instead of downloading and GPG-verifying a released tarball.
- `docker-entrypoint.sh` — a vendored, byte-identical copy of the official
  entrypoint. If upstream changes it, re-vendor in a deliberate PR; the CQL smoke
  test guards against contract drift.
