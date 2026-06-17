#!/usr/bin/env bash
#
# Shared S3-backed cache helpers for Packer provisioning, sourced by install scripts.
#
# Two caches, both stored under the account S3 bucket:
#   - apt archives:   s3://$EDL_S3_BUCKET/apt-cache/$EDL_ARCH/      (all .deb downloads)
#   - direct fetches: s3://$EDL_S3_BUCKET/download-cache/<key>      (pinned binaries/jars/tarballs)
#
# Config (EDL_S3_BUCKET, EDL_ARCH) is read from /etc/edl-cache.env, written once by
# setup_s3_cache.sh. Every function degrades gracefully: with no bucket configured, or on any
# cache error, it falls back to fetching from the origin so a build is never blocked by the cache.

# shellcheck disable=SC1091
[ -f /etc/edl-cache.env ] && . /etc/edl-cache.env
EDL_S3_BUCKET="${EDL_S3_BUCKET:-}"
EDL_ARCH="${EDL_ARCH:-amd64}"

# cached_fetch <url> <cache_key> <dest>
# Fetch <url> to <dest>, using s3://$EDL_S3_BUCKET/download-cache/<cache_key> as a cache.
# Cache key should encode the version so a new version is a cache miss (e.g. "k9s/v0.50.18/...").
cached_fetch() {
    local url="$1" key="$2" dest="$3"
    local s3="s3://${EDL_S3_BUCKET}/download-cache/${key}"

    # Note: no sudo on the aws calls. Instance-profile creds come from IMDS and work as any user,
    # and running as the build user keeps the downloaded file user-owned so callers can rm it
    # (a sudo-downloaded file in a sticky dir like /tmp can't be removed by the non-root user).
    if [ -n "$EDL_S3_BUCKET" ] && aws s3 cp "$s3" "$dest" --no-progress 2>/dev/null; then
        echo "cache hit:  $key"
        return 0
    fi

    echo "cache miss: $key (downloading $url)"
    curl -fsSL --retry 3 "$url" -o "$dest"

    if [ -n "$EDL_S3_BUCKET" ]; then
        aws s3 cp "$dest" "$s3" --no-progress 2>/dev/null || echo "WARN: failed to populate cache for $key"
    fi
}

# Restore the apt archive cache from S3 into /var/cache/apt/archives so subsequent apt installs
# reuse previously-downloaded .debs instead of hitting the mirrors.
apt_cache_restore() {
    [ -n "$EDL_S3_BUCKET" ] || return 0
    echo "=== Restoring apt cache from s3://${EDL_S3_BUCKET}/apt-cache/${EDL_ARCH}/ ==="
    sudo aws s3 sync "s3://${EDL_S3_BUCKET}/apt-cache/${EDL_ARCH}/" /var/cache/apt/archives/ --no-progress \
        || echo "WARN: apt cache restore failed (cold cache?); continuing"
}

# Save the (now warm) apt archive cache back to S3 for the next build.
apt_cache_save() {
    [ -n "$EDL_S3_BUCKET" ] || return 0
    echo "=== Saving apt cache to s3://${EDL_S3_BUCKET}/apt-cache/${EDL_ARCH}/ ==="
    sudo aws s3 sync /var/cache/apt/archives/ "s3://${EDL_S3_BUCKET}/apt-cache/${EDL_ARCH}/" \
        --exclude '*' --include '*.deb' --no-progress \
        || echo "WARN: apt cache save failed; continuing"
}
