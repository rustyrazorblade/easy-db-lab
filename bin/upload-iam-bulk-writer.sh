#!/usr/bin/env bash
# Build the IAM bulk writer JAR and upload it to the account S3 bucket.
# The uploaded path matches what `easy-db-lab spark submit` expects, so you can
# pass the printed S3 path directly with --jar.
#
# Optional env var:
#   ACCOUNT_BUCKET  - Account-level S3 bucket name. Defaults to the s3Bucket
#                     value in ~/.easy-db-lab/profiles/default/settings.yaml
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

JAR="spark/bulk-writer-s3-iam/build/libs/bulk-writer-s3-iam-all.jar"

SETTINGS="$HOME/.easy-db-lab/profiles/default/settings.yaml"

if [ -z "${ACCOUNT_BUCKET:-}" ]; then
    if ! command -v yq &>/dev/null; then
        echo "ERROR: yq is required but not installed. Install with: brew install yq" >&2
        exit 1
    fi
    if [ ! -f "$SETTINGS" ]; then
        echo "ERROR: ACCOUNT_BUCKET is not set and $SETTINGS does not exist" >&2
        exit 1
    fi
    ACCOUNT_BUCKET=$(yq '.s3Bucket' "$SETTINGS")
    if [ -z "$ACCOUNT_BUCKET" ] || [ "$ACCOUNT_BUCKET" = "null" ]; then
        echo "ERROR: s3Bucket key not found in $SETTINGS" >&2
        exit 1
    fi
    echo "Using account bucket from $SETTINGS: $ACCOUNT_BUCKET"
fi

S3_PATH="s3://$ACCOUNT_BUCKET/spark/bulk-writer-s3-iam-all.jar"

echo "=== Building IAM bulk writer JAR ==="
./gradlew :spark:bulk-writer-s3-iam:shadowJar

echo "=== Uploading to $S3_PATH ==="
aws s3 cp "$JAR" "$S3_PATH"

echo ""
echo "JAR uploaded: $S3_PATH"
echo "Use with: easy-db-lab spark submit --jar $S3_PATH ..."
