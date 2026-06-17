#!/usr/bin/env bash
#
# Saves the warm apt archive cache back to S3 at the end of the build so the next build reuses it.
# Direct downloads are cached inline by cached_fetch as they happen, so only the apt cache needs a
# final save here.
set -euo pipefail

echo "=== Running: save_s3_cache.sh ==="

# shellcheck disable=SC1091
. /usr/local/lib/edl-cache.sh
apt_cache_save

echo "✓ save_s3_cache.sh completed successfully"
