# GitHub CI

## Container Tagging Rules

The `publish-container.yml` workflow publishes containers for all branches and tags. Do NOT restrict which branches trigger the workflow — branch containers are intentional for testing.

Tagging scheme:
- **main branch** → `latest`
- **feature branches** → sanitized branch name (e.g., `stress-dashboard`)
- **version tags** (`v*`) → `$VERSION` and `v$VERSION` (e.g., `1.0.0` and `v1.0.0`). NEVER include `latest`.

Only the main branch should ever produce the `latest` tag.

## Building an Arbitrary Cassandra Ref

Building Cassandra from an arbitrary git ref (branch, tag, SHA, or fork) — and
the nightly build of the tracked branch set — now lives in a separate repo,
[rustyrazorblade/cassandra-builds](https://github.com/rustyrazorblade/cassandra-builds),
not here. It used to be a `build-cassandra-ref.yml` workflow in this repo, but
every build published a per-build GitHub release, which meant a permanent git
tag on **this** repo for every nightly build of every tracked branch — tags
that have nothing to do with easy-db-lab's own versioning. Moving the whole
pipeline to a dedicated repo keeps those tags where they belong.

`packer/cassandra/cassandra_versions.yaml` pins tarball URLs from that repo's
releases; see that repo's README for how to add a new tracked branch or
trigger an on-demand build. User-facing usage is documented in
`docs/development/building-cassandra-refs.md`.
