# Building a Cassandra Ref On Demand

Building Apache Cassandra from an arbitrary git ref (branch, tag, commit SHA,
or fork) — plus the nightly build of the tracked branch set — is handled by a
separate repo: [rustyrazorblade/cassandra-builds](https://github.com/rustyrazorblade/cassandra-builds).

It used to be a workflow in easy-db-lab itself, but every build published a
per-build GitHub release, which left a permanent git tag on this repo for
every nightly build of every tracked branch — tags unrelated to easy-db-lab's
own versioning. The whole pipeline moved to a dedicated repo so those tags
land somewhere that's actually about Cassandra builds.

See that repo's README for:

- The currently tracked branches (nightly matrix)
- How to add a new branch to track
- How to trigger a one-off build for any ref, including forks
- Where the GHCR images and tarball releases land

## Consuming a build here

Pin a tarball URL in `packer/cassandra/cassandra_versions.yaml`:

```yaml
- version: "my-branch"
  url: https://github.com/rustyrazorblade/cassandra-builds/releases/download/cassandra-<version>-<short-sha>/apache-cassandra-<version>-<short-sha>-bin.tar.gz
  java: "17"
  python: "3.11.9"
```

`install_cassandra.sh` downloads the URL and expects it to unpack into a
single top-level `*cassandra*` directory, which these tarballs satisfy.

Branches tracked by the nightly matrix (`5.0-HEAD`, `6.0-HEAD`, `trunk`,
`6.0-rustyrazorblade-HEAD`) are already pinned this way in
`cassandra_versions.yaml`, pointing at the moving `nightly` release so they
always resolve to the latest build with no manual pin updates needed.
