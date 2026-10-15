## Context

`packer/cassandra/bin/install-cassandra-version` installs one Cassandra version.  The AMI bake runs it (`install_cassandra.sh`), and `cassandra install` and `cassandra use` run it over SSH on a live node (`CassandraInstall.kt`).  It loads `/usr/local/lib/edl-cache.sh` when that file is present, and that library provides `cached_fetch`.  When the file is absent, the script defines a stub `cached_fetch` that downloads directly.

The script has two tarball paths:

- The released path (about line 126) resolves the latest patch release from `dlcdn.apache.org` and calls `cached_fetch` with the key `cassandra-dist/<x.y.z>/<archive>`.  The key names a full release number, and a release never changes, so caching is correct.
- The URL path (about line 246) calls `cached_fetch` with the key `cassandra-dist/<version>/<archive>`.  For the nightly versions the URL serves a new build every night under the same name, so the key never changes and the cache serves an old build forever.

`cached_fetch` already copies an `s3://` source directly, without the cache.  The `cassandra-local-builds` spec ("Installing a published build") requires that behavior, and this change keeps it.

## Goals / Non-Goals

**Goals:**

- A URL tarball comes from its origin on every install, at bake time and on a live node.
- A URL tarball is never read from or written to the S3 download cache.
- Released versions stay cached.
- An `s3://` URL is still copied directly from S3.
- A failed URL download fails with the existing error and never falls back to a cache.
- The bake log shows `direct download (not cached): <url>` for each URL tarball.

**Non-Goals:**

- A change to other cache users or to the apt cache.
- Deleting the stale `cassandra-dist/` objects.
- ETag, checksum, or commit-hash freshness checks.
- New retry or fallback logic.

## Decisions

### D1: The direct download is a local function in the install script

Near the cache-library loader (lines 86-93), add a local function `direct_fetch <url> <dest>`:

- It prints `direct download (not cached): <url>`.
- For an `s3://*` URL it runs `aws s3 cp "$url" "$dest" --no-progress`.
- For any other URL it runs `curl -fsSL --retry 3 "$url" -o "$dest"`.  This is the same invocation that `cached_fetch` uses.  There is no new retry logic.

The URL tarball path (about line 246) calls `direct_fetch "$URL" "$archive_file"` instead of `cached_fetch`.  It keeps the existing "Failed to download version ..." error and never falls back to a cache.  The released path (about line 126) does not change.

The comments at about line 236 and lines 243-245 are rewritten.  They say that the URL tarball is fetched from its origin every time, because nightly URLs change contents under a fixed name, and that only immutable releases are cached.

The cache-library path becomes overridable, so that a test can load the real library:

```bash
EDL_CACHE_LIB=${EDL_CACHE_LIB:-/usr/local/lib/edl-cache.sh}
```

Alternatives considered:

- (A, chosen) A local `direct_fetch` in the install script.  The fix ships with the Cassandra image alone.  It works on a node with an old base library or with no library.  The cost is about three lines of `s3://` handling that are also in the library.
- (B, rejected) Add `direct_fetch` to `edl-cache-lib.sh`.  The `s3://` rule would be in one place, but the fix would depend on the base image and would need a fallback stub.  A node with an old library does not have the function.
- (C, rejected) A "do not cache" mode on `cached_fetch`, for example an empty key.  This gives one function two contracts, and it puts a path for mutable URLs into the cache helper.

### D2: One log line for every URL tarball

Every URL tarball prints the same `direct download (not cached): <url>` line, also for an `s3://` URL.  The rejected option kept the library's `s3 fetch:` wording for `s3://` URLs.  One line makes the bake log easy to check.

### D3: The rule goes into the spec

The rule is added to the "Runtime Version Installation" requirement in `openspec/specs/cassandra/spec.md`, so that a later speed-up cannot undo it without a spec change.  The rejected option was to make no spec change.  The `cassandra-local-builds` requirement "Installing a published build" does not change.

### D4: Rebake both images

`packer/base/install/edl-cache-lib.sh` changes (one comment line), so both the base image and the Cassandra image are rebaked after `./gradlew installDist`, as CLAUDE.md requires.  The rejected option rebaked only the Cassandra image.

### Edit to `edl-cache-lib.sh`

At about line 20, add one comment line on `cached_fetch`: never pass a URL whose contents change under the same name (nightlies, latest); fetch it directly instead.  The behavior of `cached_fetch` does not change.  `edl-cache-lib.test.sh` does not change and still passes.

### Tests

The tests are in `packer/cassandra/bin/install-cassandra-version.test.sh`.  The `testCassandraInstallScript` task runs them, inside `./gradlew testCassandraScripts`.

- The existing tests set `EDL_CACHE_LIB=/nonexistent`, so they do not depend on the machine.
- The new cases load the real library with `EDL_CACHE_LIB=$SCRIPT_DIR/../../base/install/edl-cache-lib.sh` and export `EDL_S3_BUCKET=test-bucket`.
- The new stubs (`aws`, `curl`, `chown`) are in a separate stub directory.  Only the new cases put it first on `PATH`.  The existing `curl`, `git` and `ant` reach-out stubs and `assert_no_reach_out` stay as they are.
- Each new case uses its own new `CASSANDRA_INSTALL_DIR`.  The existing test leaves a 5.0 install behind, and that install would stop a new case with "already installed".
- The `aws` stub writes each call to `calls.log`.  An `s3 cp` whose source is under `download-cache/` is a miss, unless `AWS_CACHE_HIT=1`.  A local destination gets a fixture tarball.  A destination under `download-cache/` is a write-back, and the stub logs it.
- The `curl` stub returns `href="5.0.6/"` for the `dlcdn` index.  With `-o` it copies the fixture tarball (`apache-cassandra-5.0.6/` with `conf/cassandra.yaml` and `bin/cassandra.in.sh`).  With `CURL_FAILS=1` it fails.
- `find -printf` is GNU-only.  The assertions read the transcript and the output, not the exit status, except where the exit status is the point of the case.
- Each "absent" assertion has a matching "present" assertion.

Cases:

1. URL path with a stale object (`AWS_CACHE_HIT=1`).  `calls.log` has no `download-cache/`.  The output has no `cache hit:` and no `cache miss:` line.  A `curl` call to the URL is present.  The direct download line is present.
2. URL path with a cold cache.  The same assertions as case 1.  This proves that there is no write-back.
3. URL path with a failed download (`CURL_FAILS=1`, `AWS_CACHE_HIT=1`).  The exit status is not zero.  The output has "Failed to download version".  `calls.log` has no `download-cache/`.
4. An `s3://` URL.  An `aws s3 cp` from the `s3://` URL is present.  The direct download line is present.  `calls.log` has no `download-cache/`.  There is no `curl` call for the tarball.
5. Released path with a miss (version `5.0`).  A read of `s3://test-bucket/download-cache/cassandra-dist/5.0.6/apache-cassandra-5.0.6-bin.tar.gz` is present.  The `cache miss: cassandra-dist/5.0.6/` line is present.  A write-back to the same key is present.
6. Released path with a hit.  The `cache hit: cassandra-dist/5.0.6/...` line is present, and there is no `curl` call for the `dlcdn` tarball.

## Risks / Trade-offs

- Blast radius: the change is one branch of one script.  The script runs at bake time and on live nodes (`cassandra install --url` runs it over SSH through `CassandraInstall.kt`).  Other cache users do not change.
- Slower bakes: about three tarballs come from origin on each bake.  The owner accepted this (569).
- A live node gets the fix only from a rebaked Cassandra AMI.  There is no ordering concern between the base image and the Cassandra image, because `direct_fetch` does not depend on the library.
- macOS BSD differences in the tests (`find -printf`): the assertions on fetch routing hold on both GNU and BSD.
- Nightly republish window: at about 02:00 UTC, `build-cassandra-set.yml` deletes and creates the nightly release again.  A bake in that window now fails with a 404.  Before this change, the stale cache hid that failure.  No retry logic is added (out of scope).
- Commit check: the tarball and directory names do not include the commit SHA.  The AWS test reads the installed `6.0-HEAD` commit from `GitSHA` in `version.properties` inside the installed Cassandra jar.  It compares that value with the commit of the current nightly, from the per-commit release (`build-cassandra-ref.yml`) or from the OCI revision label of the GHCR image.
