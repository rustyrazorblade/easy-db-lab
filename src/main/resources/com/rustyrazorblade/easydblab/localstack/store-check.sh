#!/bin/sh
# The local stack's store check (store-check): a one-shot read of the account bucket that runs before
# every backend starts. A store the stack cannot read must fail loudly, not look empty: on a cold
# start Loki answers an empty result rather than an error, so without this check expired
# credentials would look like a store with no data.
#
# It lists one key under mimir/ through the signer. Exit 0 when the bucket is readable. Otherwise it
# exits 1 with "cannot read the account bucket ... with profile P: <reason>":
#   - the signer's HTTP 502 with a body starting "unable to proxy request" is a credentials failure
#     (missing profile, expired SSO token): the reason is the signer's own text, and the hint is
#     `aws sso login --profile P`;
#   - any other answer is S3's: the reason is S3's error code (AccessDenied, NoSuchBucket, ...).
#
# Environment: BUCKET (the account bucket), PROFILE (the operator's AWS profile, for the message),
# SIGNER (host:port of the signer), ATTEMPTS (connection attempts while the signer starts, default 30).
# POSIX sh: it runs in the gate's alpine image (busybox ash).

set -u

url="http://${SIGNER}/${BUCKET}?list-type=2&prefix=mimir%2F&max-keys=1"
body=$(mktemp)
errors=$(mktemp)
trap 'rm -f "$body" "$errors"' EXIT

fail() {
  echo "store-check: cannot read the account bucket ${BUCKET} with profile ${PROFILE}: $1" >&2
  if [ -n "${2:-}" ]; then
    echo "store-check: $2" >&2
  fi
  exit 1
}

attempt=1
while :; do
  status=$(curl -sS -o "$body" -w '%{http_code}' "$url" 2>"$errors")
  rc=$?
  # 7: the signer is not accepting connections yet.
  if [ "$rc" -ne 7 ] || [ "$attempt" -ge "${ATTEMPTS:-30}" ]; then
    break
  fi
  attempt=$((attempt + 1))
  sleep 1
done

if [ "$rc" -ne 0 ]; then
  fail "the signer did not answer: $(cat "$errors")"
fi

case "$status" in
  2??)
    echo "store-check: the account bucket ${BUCKET} is readable with profile ${PROFILE}"
    exit 0
    ;;
  502)
    if head -c 23 "$body" | grep -q '^unable to proxy request'; then
      fail "credentials: $(cat "$body")" "run: aws sso login --profile ${PROFILE}"
    fi
    ;;
esac

code=$(sed -n 's:.*<Code>\([^<]*\)</Code>.*:\1:p' "$body" | head -n 1)
fail "${code:-no error code} (HTTP ${status})"
