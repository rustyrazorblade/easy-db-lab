## 1. Shared S3 cache helper

- [ ] 1.1 Create `packer/lib/s3_cache.sh` with `s3_cache_get` and `s3_cache_put` functions
  - `s3_cache_get <bucket> <key> <dest>`: downloads `s3://<bucket>/<key>` to `<dest>`, returns 0 on success, 1 on any failure
  - `s3_cache_put <bucket> <key> <src>`: uploads `<src>` to `s3://<bucket>/<key>`, best-effort (failure is non-fatal)
  - Both functions no-op and return 0 when `PACKER_CACHE_BUCKET` is unset or empty
  - Both functions no-op and return 0 when `PACKER_CACHE_SKIP=1`

## 2. BCC cache in install_bcc.sh

- [ ] 2.1 Source `packer/lib/s3_cache.sh` at the top of `install_bcc.sh`
- [ ] 2.2 Compute `CACHE_KEY="packer-build-cache/bcc/bcc-v${BCC_VERSION}-$(uname -m).tar.gz"` before the build
- [ ] 2.3 Add cache-read block before the cmake/make section: call `s3_cache_get`, and if successful, extract the tarball to `/` and skip compilation
- [ ] 2.4 Add post-install cache-write block: after verifying the Python import, create a tarball of the installed BCC files and call `s3_cache_put`
- [ ] 2.5 Verify the existing Python import check (`python3 -c "import bcc"`) still runs on both cache-hit and cache-miss paths

## 3. Cassandra source build cache in install_cassandra.sh

- [ ] 3.1 Source `packer/lib/s3_cache.sh` at the top of `install_cassandra.sh`
- [ ] 3.2 In the git-branch build path (the `else` branch): after `git clone`, capture `GIT_SHA=$(git -C "$version" rev-parse --short=12 HEAD)`
- [ ] 3.3 Compute `CACHE_KEY="packer-build-cache/cassandra/${version}-${GIT_SHA}.tar.gz"`
- [ ] 3.4 Add cache-read block: call `s3_cache_get`; if successful, extract the tarball to `/usr/local/cassandra/` and skip the ant build
- [ ] 3.5 Add post-build cache-write block: after `sudo mv "$version" "/usr/local/cassandra/$version"`, create a tarball of the installed version directory and call `s3_cache_put`
- [ ] 3.6 Ensure the configuration block (conf backup, `cassandra.in.sh` append) still runs on both cache-hit and cache-miss paths

## 4. Documentation

- [ ] 4.1 Document `PACKER_CACHE_BUCKET` and `PACKER_CACHE_SKIP` in `packer/README.md`
- [ ] 4.2 Document required IAM permissions (`s3:GetObject`, `s3:PutObject`, `s3:HeadObject`) for the packer instance role
- [ ] 4.3 Document the S3 key structure and how to manually invalidate a cache entry (delete the S3 object)
