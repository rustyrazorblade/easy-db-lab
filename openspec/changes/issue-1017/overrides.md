## Overrides existing behavior

### cassandra: Runtime Version Installation
**Currently:** the requirement says only that a version is installed from a tarball `url:` (or an official release resolved from a version prefix) or from a git `url:`+`branch:`, with one implementation at bake time and at runtime.  It says nothing about the S3 download cache.  In practice the install script fetches every tarball, URL and released, through the cache with a key made from the version name, so a nightly URL is cached under a fixed key and later installs get the old build.
**This change:** a version with a tarball `url:` (or a tarball given with `cassandra install --url`) is fetched from its origin on every install and is never read from or written to the S3 download cache.  An `s3://` URL is copied directly from S3.  A failed URL download fails with the existing error and does not fall back to a cached copy.  An official release may be fetched through the cache.  New scenarios: a stale cache object for a URL version is ignored, a URL version is not written to a cold cache, a failed URL download does not fall back to the cache, released versions still use the cache, and an s3 URL is copied directly from S3.

## Conflicts with other in-flight changes

Two other in-flight changes touch `specs/cassandra/`:

- `issue-971`: modifies "Cluster Lifecycle" only.  No conflict.
- `issue-992`: modifies "JDK 25 Selection" and "GC Log on JDK 17 and Higher" only.  No conflict.

No other in-flight change modifies "Runtime Version Installation" or mentions the download cache.  None found.
