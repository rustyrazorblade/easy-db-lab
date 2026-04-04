#!/usr/bin/env bash
# S3 build cache helpers for packer scripts.
#
# Environment variables:
#   PACKER_CACHE_BUCKET  - S3 bucket name. When unset or empty, all cache
#                          operations are no-ops and the build proceeds normally.
#   PACKER_CACHE_SKIP    - Set to 1 to bypass both cache reads and writes.
#                          Useful for forced rebuilds or debugging.

# s3_cache_get <bucket> <key> <dest>
#
# Downloads s3://<bucket>/<key> to <dest>.
# Returns 0 on success, 1 on any failure (including no bucket configured).
# A non-zero return means the caller should proceed with a normal build.
s3_cache_get() {
    local bucket="$1"
    local key="$2"
    local dest="$3"

    if [[ -z "${bucket:-}" ]]; then
        echo "[s3_cache] PACKER_CACHE_BUCKET not set, skipping cache read"
        return 1
    fi

    if [[ "${PACKER_CACHE_SKIP:-0}" == "1" ]]; then
        echo "[s3_cache] PACKER_CACHE_SKIP=1, skipping cache read"
        return 1
    fi

    echo "[s3_cache] Checking cache: s3://${bucket}/${key}"
    if aws s3 cp --no-progress "s3://${bucket}/${key}" "${dest}" 2>/dev/null; then
        echo "[s3_cache] Cache hit: s3://${bucket}/${key}"
        return 0
    else
        echo "[s3_cache] Cache miss: s3://${bucket}/${key}"
        return 1
    fi
}

# s3_cache_put <bucket> <key> <src>
#
# Uploads <src> to s3://<bucket>/<key>.
# Best-effort: failure is non-fatal (the build has already succeeded).
# No-ops when PACKER_CACHE_BUCKET is unset or PACKER_CACHE_SKIP=1.
s3_cache_put() {
    local bucket="$1"
    local key="$2"
    local src="$3"

    if [[ -z "${bucket:-}" ]]; then
        echo "[s3_cache] PACKER_CACHE_BUCKET not set, skipping cache write"
        return 0
    fi

    if [[ "${PACKER_CACHE_SKIP:-0}" == "1" ]]; then
        echo "[s3_cache] PACKER_CACHE_SKIP=1, skipping cache write"
        return 0
    fi

    echo "[s3_cache] Writing cache: s3://${bucket}/${key}"
    if aws s3 cp --no-progress "${src}" "s3://${bucket}/${key}"; then
        echo "[s3_cache] Cache write successful: s3://${bucket}/${key}"
    else
        echo "[s3_cache] Cache write failed (non-fatal): s3://${bucket}/${key}"
    fi
    return 0
}
