## Context

The packer build pipeline produces two image layers: `base` and `cassandra` (db). The bottlenecks are:

- `packer/base/install/install_bcc.sh`: Downloads BCC v0.35.0 source, runs `cmake` + `make -j$(nproc)` (C++ compilation). Installs to `/usr/lib/libbcc*`, `/usr/share/bcc/`, and Python bindings. Takes ~15–20 minutes. Version is pinned — the compiled output never changes for a given version + arch combination.

- `packer/cassandra/install/install_cassandra.sh`: Has three install paths controlled by `cassandra_versions.yaml`:
  1. Download from Apache CDN by version prefix (no URL, no branch) — already fast
  2. Download tarball from explicit URL — already fast
  3. Clone git repo + `ant realclean && ant` build (URL ending in `.git` + `branch` field) — slow; pays full build cost every time

The YAML currently uses paths 1 and 2 for all versions (including `trunk` and `5.0-HEAD` which point to nightly pre-built tarballs on GitHub Releases). Path 3 is available for custom branch builds and will be used as the project evolves.

The `aws` CLI is already installed on packer instances (`install_awscli.sh` runs before these scripts).

## Goals / Non-Goals

**Goals:**
- Cache the BCC compiled artifact in S3, keyed by version + architecture. Skip compilation on cache hit.
- Cache Cassandra git-branch build artifacts in S3, keyed by version name + git SHA of the branch tip. Skip ant build on cache hit.
- Make cache operations best-effort: any S3 failure falls through to a normal build.
- Allow cache bypass via `PACKER_CACHE_SKIP=1` for debugging or forced rebuilds.
- Keep the S3 bucket name configurable via `PACKER_CACHE_BUCKET`.

**Non-Goals:**
- Caching Cassandra binary downloads (Apache CDN / GitHub Releases) — these are already pre-built and fast to download.
- Caching sidecar builds (addressed in a separate PR).
- Creating or managing the S3 bucket from Kotlin — the bucket is provisioned separately (same bucket used for cluster data, or a dedicated build cache bucket).
- Invalidating the cache automatically when build scripts change — the cache key is content-based (version + arch/SHA), so script changes only matter for BCC (which is version-pinned).

## Decisions

### 1. Cache key design

**BCC**: `bcc/bcc-v{VERSION}-{ARCH}.tar.gz`
- `VERSION` = `BCC_VERSION` variable (e.g., `0.35.0`)
- `ARCH` = output of `uname -m` (e.g., `x86_64`, `aarch64`)
- Rationale: BCC is version-pinned. The output is fully determined by version + architecture. No content hash needed.

**Cassandra source builds**: `cassandra/{VERSION}-{GIT_SHA}.tar.gz`
- `VERSION` = the version field from `cassandra_versions.yaml` (e.g., `trunk`, `5.1-dev`)
- `GIT_SHA` = `git rev-parse HEAD` after cloning (first 12 chars)
- Rationale: Branch HEAD moves. The git SHA uniquely identifies the exact source code that was compiled. Old entries accumulate in S3 but storage cost is negligible vs build time.

Both keys are prefixed with `packer-build-cache/` within the bucket to keep them organized.

### 2. What gets cached for BCC

The cache tarball captures the installed output:
- `/usr/lib/libbcc*`
- `/usr/lib/libbpf*` (installed by BCC build)
- `/usr/share/bcc/`
- `/usr/lib/python3/dist-packages/bcc/` (Python bindings)

The tarball is created with `tar -czf - /usr/lib/libbcc* /usr/share/bcc /usr/lib/python3/dist-packages/bcc` and extracted with `tar -xzf - -C /`.

**Alternative considered:** Cache the entire build directory and re-run `make install`. Rejected — more complex and slower than caching the install artifacts.

### 3. Cache write timing

For BCC: upload happens immediately after `sudo make install` succeeds and the Python bindings are verified.

For Cassandra source builds: upload happens after the built directory is moved to `/usr/local/cassandra/$version` and configured.

Cache writes are best-effort — a failure to upload does not fail the build (`aws s3 cp ... || true`).

### 4. Cache reads are also best-effort

If the `aws s3 cp` download fails for any reason (bucket doesn't exist, no permissions, network error), the script proceeds with the normal build path. This ensures the cache never blocks a build.

**Implementation**: Use `aws s3 cp --no-progress` wrapped in an `if` statement. A non-zero exit falls through to the build.

### 5. Shared helper function

A shared `s3_cache_get` / `s3_cache_put` helper will be extracted to `packer/lib/s3_cache.sh` and sourced by both `install_bcc.sh` and `install_cassandra.sh`. This avoids duplicating the bucket name resolution and error handling logic.

**Alternative considered:** Inline the logic in each script. Rejected — duplication makes it harder to change the bucket naming convention later.

### 6. `PACKER_CACHE_SKIP` bypass

Setting `PACKER_CACHE_SKIP=1` skips both cache reads and writes. Useful when:
- Debugging a build issue and wanting a clean compile
- The cache entry is suspected to be corrupted
- Testing that the build still works from scratch

### 7. IAM requirements

The packer instance profile needs:
```json
{
  "Effect": "Allow",
  "Action": ["s3:GetObject", "s3:PutObject", "s3:HeadObject"],
  "Resource": "arn:aws:s3:::{PACKER_CACHE_BUCKET}/packer-build-cache/*"
}
```

This will be documented but not automated — the IAM policy for packer instances is managed outside this codebase.

## Risks / Trade-offs

- **Stale BCC cache**: If the BCC version bumps and the variable is updated but the cache key doesn't change for some reason — impossible by design since `BCC_VERSION` is in the key.
- **Corrupted cache entry**: If a cache upload was interrupted, the S3 object may be partial. The `tar` extraction will fail, causing the build to fall back to compiling from scratch (best-effort read handles this).
- **S3 bucket not configured**: If `PACKER_CACHE_BUCKET` is unset, cache operations are skipped entirely and the build proceeds normally. This is the safe default for users who haven't set up caching.
- **Architecture mismatch**: `uname -m` in the key prevents cross-architecture cache hits.
