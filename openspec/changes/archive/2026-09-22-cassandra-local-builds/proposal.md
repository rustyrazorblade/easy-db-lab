# Build a Cassandra branch locally and install it by name

## Why

A Cassandra committer testing their own work on a real cluster has no supported path today. The
two that exist both miss:

- `cassandra install <v> --url <repo> --branch <b>` clones and builds **on every node**, in place.
  It produces no artifact, so a three-node cluster builds the same branch three times, and nothing
  survives the cluster's teardown. It also cannot build a branch that only exists on the
  committer's machine.
- `.github/workflows/build-cassandra-ref.yml` builds properly and publishes a tarball, but it
  builds a **pushed ref** in CI. Uncommitted work — the normal state of a branch being developed —
  cannot be tested at all.

What is missing is the inner loop: build the tree you are already working in, once, and install
the result by name onto a cluster.

## What changes

A new `cassandra build [<dir>] --java <N>` builds a Cassandra checkout on the developer's machine
with `ant artifacts` and publishes the tarball plus a manifest to the profile's account S3 bucket
under `cassandra-builds/<name>/`.

The build is named for what identifies it — `<version>-[JIRA-]<date>-<sha>-jdk<N>` — and that name
is its S3 directory, the version a node installs it under, and the handle `cassandra install`
takes. `cassandra list` shows published builds alongside declared versions.

Builds are discovered by listing the bucket, not from a local file, so a build belongs to the
profile rather than to the machine that produced it.

## Impact

- New: `cassandra build`, `CassandraBuildService`, `CassandraBuildCatalog`, `CassandraBuildManifest`.
- Changed: `cassandra list` gains published builds; `cassandra install` resolves a build name from
  S3 when the name is not declared locally.
- Changed: `cached_fetch` in `edl-cache-lib.sh` accepts an `s3://` source, which is how a node
  fetches a published build with its instance profile.

Out of scope: building in a container, pruning old builds, and surfacing artifacts from the
separate `cassandra-builds` repository.
