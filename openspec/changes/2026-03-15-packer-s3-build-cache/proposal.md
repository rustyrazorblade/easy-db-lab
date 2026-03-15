## Why

Packer AMI builds are slow due to repeated compilation of large artifacts whose content never changes between builds. Two key bottlenecks have been identified:

1. **BCC compilation**: `install_bcc.sh` compiles BCC v0.35.0 from source on every build using cmake + make. This C++ compilation takes 15–20 minutes. The version is pinned, so the compiled output is always identical for a given architecture. There is no reason to recompile it.

2. **Cassandra source builds**: `install_cassandra.sh` supports building Cassandra from a git branch (clone + `ant realclean && ant`). When branch-based versions are configured in `cassandra_versions.yaml`, the full build runs from scratch every time, including Maven dependency resolution (which is then explicitly cleared between versions).

AMIs are built infrequently, but the operator provisions clusters from the same AMI repeatedly. Every AMI build currently pays these costs in full even when none of the inputs have changed.

## What Changes

- Add an S3-based binary cache to `install_bcc.sh`. Before compiling, check S3 for a cached tarball keyed by BCC version and architecture. On a cache hit, download and extract instead of compiling. On a miss, compile as normal then upload the result to S3.

- Add an S3-based binary cache to `install_cassandra.sh` for the git-branch build path only. Cache key is derived from the version name and git SHA of the branch tip. Downloaded binary releases (Apache CDN, GitHub releases) do not need caching — they are already pre-built tarballs.

- The S3 bucket name is configurable via the `PACKER_CACHE_BUCKET` environment variable. Cache operations are best-effort: S3 unavailability or a missing object falls back to a normal build.

- Both scripts expose a `PACKER_CACHE_SKIP` environment variable to bypass the cache (useful for forcing a clean rebuild).

## Capabilities

### New Capabilities

- `packer-s3-build-cache`: S3-backed binary artifact cache for compiled packer dependencies. Reduces image build time by 15–40 minutes on cache hits.

### Modified Capabilities

- `packer-base-image`: `install_bcc.sh` now checks/populates S3 cache before compiling BCC.
- `packer-db-image`: `install_cassandra.sh` now checks/populates S3 cache for git-branch Cassandra builds.

## Impact

- **AMI build time**: Reduces to ~5 minutes for BCC (download vs compile) and ~2–5 minutes per Cassandra source version (download vs ant build) on cache hits.
- **First build / cache miss**: No change — builds proceed exactly as today and populate the cache.
- **IAM permissions**: The packer instance role needs `s3:GetObject`, `s3:PutObject`, and `s3:HeadObject` on the cache bucket.
- **No Kotlin changes**: This is entirely packer/bash scope.
