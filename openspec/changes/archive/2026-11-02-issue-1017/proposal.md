## Why

The Cassandra install script fetches every version tarball through the S3 download cache, with a cache key made from the version name.  The nightly versions (`5.0-HEAD`, `6.0-HEAD`, `trunk`) get new contents every night under the same URL, but their cache key never changes.  After the first bake, every later bake gets a cache hit and installs the old build.

This caused a real failure.  The `6.0-HEAD` cache entry was written on 2026-09-08, and every Cassandra AMI baked after that date shipped that build.  That build does not have the CASSANDRA-21671 fix, so a node with a BTI / UCS / trie memtable config crashed at boot.

Released versions do not change upstream, so they can stay cached.

## What Changes

- The install script fetches a version that has a tarball `url:` (or a tarball given with `cassandra install --url`) from its origin on every install.  It never reads that tarball from the S3 download cache and never writes it to the cache.
- The script prints one `direct download (not cached): <url>` line for each URL tarball.
- An `s3://` URL (a `cassandra build` artifact) is still copied directly from S3.
- A failed URL download fails with the existing "Failed to download version" error.  There is no fallback to a cached copy.
- Released versions (resolved from `dlcdn.apache.org`) stay cached under `cassandra-dist/<x.y.z>/`.
- The change applies to both callers of the script: the AMI bake and `cassandra install` / `cassandra use` on a live node.
- The comments in the install script that say the URL tarball is cached are corrected.
- One comment line on `cached_fetch` says never to pass a URL whose contents change under the same name.
- A shell unit test covers the URL path and the released path with the real cache library loaded.

Out of scope:

- Other cache users (yq, k3s, OTel agent, async-profiler, and the rest) and the apt cache.
- Deleting the stale `cassandra-dist/5.0-HEAD`, `cassandra-dist/6.0-HEAD` and `cassandra-dist/trunk` objects.  The owner removes them by hand.
- ETag, checksum, or commit-hash freshness checks.
- New retry or fallback logic.
- Removing the AMI (961).

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `cassandra`: the "Runtime Version Installation" requirement now says that a tarball `url:` version is always fetched from its origin and is never read from or written to the S3 download cache, and that official releases may be cached.

## Impact

- `packer/cassandra/bin/install-cassandra-version`: a new local `direct_fetch` function, the URL tarball path calls it, the cache library path can be overridden with `EDL_CACHE_LIB`, and the comments are corrected.
- `packer/base/install/edl-cache-lib.sh`: one comment line.  The behavior does not change.
- `packer/cassandra/bin/install-cassandra-version.test.sh`: new test cases.
- `openspec/specs/cassandra/spec.md`: the modified requirement.
- Both the base image and the Cassandra image are rebaked after `./gradlew installDist`, because a file under `packer/base/` changes.
- Bakes are a little slower, because about three tarballs come from origin on each bake (accepted in 569).
- User docs: no page under `docs/` describes the download cache for Cassandra versions today.  The task list checks this again during the change.
