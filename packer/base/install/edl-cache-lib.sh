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
# Never pass a url whose contents change under the same name (nightlies, latest); fetch it directly.
cached_fetch() {
    local url="$1" key="$2" dest="$3"
    local s3="s3://${EDL_S3_BUCKET}/download-cache/${key}"

    # An s3:// source is already an object in the account bucket the node can read with its
    # instance profile, so it is fetched directly and never cached: copying it under
    # download-cache/ would store a second copy of a file that is already there. This is the path
    # a locally-built Cassandra published by `cassandra build` arrives on.
    case "$url" in
        s3://*)
            echo "s3 fetch:   $url"
            aws s3 cp "$url" "$dest" --no-progress
            return
            ;;
    esac

    # Note: no sudo on the aws calls. Instance-profile creds come from IMDS and work as any user,
    # and running as the build user keeps the downloaded file user-owned so callers can rm it
    # (a sudo-downloaded file in a sticky dir like /tmp can't be removed by the non-root user).
    if [ -n "$EDL_S3_BUCKET" ] && aws s3 cp "$s3" "$dest" --no-progress 2>/dev/null; then
        echo "cache hit:  $key"
        return 0
    fi

    if [ -n "$EDL_S3_BUCKET" ]; then
        echo "cache miss: $key (downloading $url)"
    else
        echo "no S3 cache; downloading $url"
    fi
    curl -fsSL --retry 3 "$url" -o "$dest"

    if [ -n "$EDL_S3_BUCKET" ]; then
        aws s3 cp "$dest" "$s3" --no-progress 2>/dev/null || echo "WARN: failed to populate cache for $key"
    fi
}

# cache_uri_supported <cache-uri>
# True when the copy tool for the URI's scheme is known; else prints the scheme error and returns 2.
# Only s3:// is known today; a gs:// case is the one line a GCP image adds.
cache_uri_supported() {
    case "$1" in
        s3://*) return 0 ;;
        *)
            echo "ERROR: unknown cache URI scheme in '$1' (supported: s3://)" >&2
            return 2
            ;;
    esac
}

# cache_get_uri <cache-uri> <dest> - copy a cache object to <dest>; non-zero on a miss.
cache_get_uri() {
    cache_uri_supported "$1" || return 2
    aws s3 cp "$1" "$2" --no-progress 2>/dev/null
}

# cache_put_uri <src> <cache-uri> - write <src> to the cache object.
cache_put_uri() {
    cache_uri_supported "$2" || return 2
    aws s3 cp "$1" "$2" --no-progress 2>/dev/null
}

# cached_fetch_uri <cache-uri> <dest> <url> [<fallback-url>]
# The runtime download cache: the CLI names the cache object by its full URI (it knows the
# account bucket of the user, which /etc/edl-cache.env does not), so this reads no config.
# A hit copies the object to <dest>. A miss downloads <url>, then <fallback-url>, and writes the
# tarball to <cache-uri>. The copy tool is chosen by the URI scheme; only s3:// is known today.
# Fails, printing every URL tried, when no URL could be downloaded.
cached_fetch_uri() {
    local uri="$1" dest="$2" url="$3" fallback="${4:-}"

    cache_uri_supported "$uri" || return 2

    if cache_get_uri "$uri" "$dest"; then
        echo "cache hit:  $uri"
        return 0
    fi

    local tried="" candidate
    for candidate in "$url" "$fallback"; do
        [ -n "$candidate" ] || continue
        tried="${tried}  ${candidate}
"
        echo "cache miss: downloading $candidate"
        if curl -fsSL --retry 3 "$candidate" -o "$dest"; then
            cache_put_uri "$dest" "$uri" || echo "WARN: failed to write the cache object $uri"
            return 0
        fi
        rm -f "$dest"
    done

    printf 'ERROR: download failed from every URL tried:\n%s' "$tried" >&2
    return 1
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
