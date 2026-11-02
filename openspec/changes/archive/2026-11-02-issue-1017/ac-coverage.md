| Source | Requirement | Covering scenario(s) | Status |
|--------|-------------|----------------------|--------|
| AC #1017 | AMI baked with the cache configured → no `cache hit:` or `cache miss:` line for `5.0-HEAD`, `6.0-HEAD` or `trunk`; each shows as a direct download | `cassandra: A stale cache object for a URL version is ignored`, `cassandra: A URL version is not written to a cold cache` (bake check in tasks.md 7.3) | ✅ Covered |
| AC #1017 | AMI baked → released versions still go through the cache (`cache hit:` / `cache miss: cassandra-dist/<x.y.z>/...`) | `cassandra: Released versions still use the cache` (bake check in tasks.md 7.4) | ✅ Covered |
| AC #1017 | Newer nightly published → the bake installs it, even if a stale cache object remains | `cassandra: A stale cache object for a URL version is ignored` | ✅ Covered |
| AC #1017 | `cassandra install --url <tarball>` on a live node with `/usr/local/lib/edl-cache.sh` and a bucket → the tarball comes from the URL, not the cache | `cassandra: A stale cache object for a URL version is ignored` (covers `cassandra install`), `cassandra: A URL version is not written to a cold cache` | ✅ Covered |
| AC #1017 | URL is `s3://...` → the script still copies it directly from S3 | `cassandra: An s3 URL is copied directly from S3` | ✅ Covered |
| AC #1017 | URL download fails → the script fails with the existing "Failed to download" error and never falls back to a cached copy | `cassandra: A failed URL download does not fall back to the cache` | ✅ Covered |
| AC #1017 | `./gradlew testCassandraScripts` → a new test with a stub `aws` and the cache lib present fails if the URL path reads or writes `download-cache/`, and fails if the released path does not use the cache | `cassandra: A stale cache object for a URL version is ignored`, `cassandra: A URL version is not written to a cold cache`, `cassandra: Released versions still use the cache` (test cases in tasks.md 3.6-3.11, run in 4.1) | ✅ Covered |
| AC #1017 | `edl-cache-lib.test.sh` → the existing tests pass unchanged | No scenario fits (the library behavior does not change); tasks.md 2.1 and 4.2 | ✅ Covered |
| AC #1017 | Real Cassandra AMI baked → the log shows direct downloads for the nightlies, and the installed `6.0-HEAD` commit matches the current nightly | `cassandra: A stale cache object for a URL version is ignored` (real bake in tasks.md 7.1-7.5) | ✅ Covered |
| Risk | Blast radius: one branch of one script, run at bake time and on live nodes | `cassandra: Released versions still use the cache`, `cassandra: An s3 URL is copied directly from S3` | ✅ Covered |
| Risk | Slower bakes: about three tarballs from origin on each bake | — | ⚠️ Excluded — accepted cost (569); no behavior to specify |
| Risk | Live nodes get the fix only from a Cassandra AMI rebake; no base/Cassandra ordering concern | — | ⚠️ Excluded — deployment fact, not behavior; the rebake is tasks.md 7.2 |
| Risk | macOS BSD differences in tests (`find -printf`) | — | ⚠️ Excluded — test-harness concern; handled by tasks.md 3.12 |
| Risk | Nightly republish window (about 02:00 UTC): a bake in that window now fails with a 404 | `cassandra: A failed URL download does not fall back to the cache` | ✅ Covered |
| Risk | Commit check: tarball and directory names do not include the commit SHA | — | ⚠️ Excluded — verification method, not behavior; handled by tasks.md 7.5 |
