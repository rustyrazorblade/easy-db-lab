## 1. Install script

- [x] 1.1 In `packer/cassandra/bin/install-cassandra-version`, make the cache library path overridable: `EDL_CACHE_LIB=${EDL_CACHE_LIB:-/usr/local/lib/edl-cache.sh}`, and load the library from `$EDL_CACHE_LIB`.
- [x] 1.2 Near the cache library loader, add the local function `direct_fetch <url> <dest>`.  It prints `direct download (not cached): <url>`.  For `s3://*` it runs `aws s3 cp "$url" "$dest" --no-progress`.  Otherwise it runs `curl -fsSL --retry 3 "$url" -o "$dest"`.
- [x] 1.3 In the URL tarball path (about line 246), call `direct_fetch "$URL" "$archive_file"` instead of `cached_fetch`.  Keep the existing "Failed to download version ..." error.  Do not add a fallback to the cache.
- [x] 1.4 Rewrite the comments at about line 236 and lines 243-245.  They say that the URL tarball is fetched from its origin every time, because nightly URLs change contents under a fixed name, and that only immutable releases are cached.
- [x] 1.5 Do not change the released path (about line 126).

## 2. Cache library comment

- [x] 2.1 In `packer/base/install/edl-cache-lib.sh` (about line 20), add one comment line on `cached_fetch`: never pass a URL whose contents change under the same name (nightlies, latest); fetch it directly instead.  Do not change the behavior.

## 3. Tests

- [x] 3.1 In `packer/cassandra/bin/install-cassandra-version.test.sh`, set `EDL_CACHE_LIB=/nonexistent` for the existing tests.
- [x] 3.2 Add a separate stub directory with `aws`, `curl` and `chown` stubs, used only by the new cases.  Keep the existing `curl`, `git` and `ant` reach-out stubs and `assert_no_reach_out` as they are.
- [x] 3.3 The `aws` stub writes each call to `calls.log`.  An `s3 cp` with a source under `download-cache/` is a miss unless `AWS_CACHE_HIT=1`.  A local destination gets the fixture tarball.  A destination under `download-cache/` is a logged write-back.
- [x] 3.4 The `curl` stub returns `href="5.0.6/"` for the `dlcdn` index, copies the fixture tarball (`apache-cassandra-5.0.6/` with `conf/cassandra.yaml` and `bin/cassandra.in.sh`) for `-o`, and fails when `CURL_FAILS=1`.
- [x] 3.5 The new cases load the real library with `EDL_CACHE_LIB=$SCRIPT_DIR/../../base/install/edl-cache-lib.sh`, export `EDL_S3_BUCKET=test-bucket`, and each use a new `CASSANDRA_INSTALL_DIR`.
- [x] 3.6 Case: URL path with a stale cache object (`AWS_CACHE_HIT=1`).  Assert no `download-cache/` in `calls.log`, no `cache hit:` or `cache miss:` line, a `curl` call to the URL, and the direct download line.
- [x] 3.7 Case: URL path with a cold cache.  The same assertions as 3.6, which prove there is no write-back.
- [x] 3.8 Case: URL path with a failed download (`CURL_FAILS=1`, `AWS_CACHE_HIT=1`).  Assert a non-zero exit, "Failed to download version", and no `download-cache/` in `calls.log`.
- [x] 3.9 Case: `s3://` URL.  Assert an `aws s3 cp` from the `s3://` URL, the direct download line, no `download-cache/` in `calls.log`, and no `curl` call for the tarball.
- [x] 3.10 Case: released miss (version `5.0`).  Assert a read of `s3://test-bucket/download-cache/cassandra-dist/5.0.6/apache-cassandra-5.0.6-bin.tar.gz`, the `cache miss: cassandra-dist/5.0.6/` line, and a write-back to the same key.
- [x] 3.11 Case: released hit.  Assert the `cache hit: cassandra-dist/5.0.6/...` line and no `curl` call for the `dlcdn` tarball.
- [x] 3.12 Read the transcript and the output in the assertions, not the exit status, except in 3.8.  Pair every "absent" assertion with a "present" assertion.

## 4. Run the tests

- [x] 4.1 Run `./gradlew testCassandraScripts` and confirm that it passes.
- [x] 4.2 Run `edl-cache-lib.test.sh` and confirm that the existing tests pass unchanged.
- [ ] 4.3 Run `./gradlew testPackerCassandra` if the change touches what it covers, and confirm that it passes.

## 5. Docs

- [x] 5.1 Search `docs/` for "download cache", "download-cache" and "cassandra-dist".  Update any user doc that says Cassandra version tarballs are cached.
- [x] 5.2 Update `packer/README.md` or `packer/TESTING.md` if they describe the caching of Cassandra tarballs.

## 6. Spec

- [x] 6.1 Apply the delta to the "Runtime Version Installation" requirement in `openspec/specs/cassandra/spec.md`.

## 7. Real AWS test

- [ ] 7.1 Run `./gradlew installDist`.
- [ ] 7.2 Rebake the base image and the Cassandra image with the S3 cache configured, with the owner's AWS profile.
- [ ] 7.3 From the bake log, confirm that `5.0-HEAD`, `6.0-HEAD` and `trunk` each show `direct download (not cached):` and no `cache hit:` or `cache miss:` line.
- [ ] 7.4 From the bake log, confirm that released versions show `cache hit:` or `cache miss:` under `cassandra-dist/<x.y.z>/`.
- [ ] 7.5 Read the installed `6.0-HEAD` commit from `GitSHA` in `version.properties` inside the installed Cassandra jar.  Confirm that it matches the commit of the current nightly, from the per-commit release (`build-cassandra-ref.yml`) or from the OCI revision label of the GHCR image.
