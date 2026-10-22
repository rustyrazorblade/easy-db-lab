#!/bin/sh
# The local stack's store check (store-check): a one-shot read of the account bucket that runs before
# every backend starts. A store the stack cannot read must fail loudly, not look empty: on a cold
# start Loki answers an empty result rather than an error, so without this check expired
# credentials would look like a store with no data.
#
# It lists one key under mimir/ through the signer. Exit 0 when the bucket is readable. Otherwise it
# exits 1 with "cannot read the account bucket ... with profile P: <reason>":
#   - the signer's HTTP 502 with a body starting "unable to proxy request" is the signer's own
#     failure, named by its text: a network error (DNS, TCP, TLS) is "the signer could not reach S3";
#     a credentials error (missing profile, expired SSO token) is "credentials", with the hint
#     `aws sso login --profile P`; anything else is "the signer could not proxy the request";
#   - any other answer is S3's: the reason is S3's error code (AccessDenied, NoSuchBucket, ...).
#
# Environment: BUCKET (the account bucket), PROFILE (the operator's AWS profile, for the message),
# SIGNER (host:port of the signer), ATTEMPTS (connection attempts while the signer starts, default 30).
# POSIX sh: it runs in the gate's alpine image (busybox ash).

set -u

# What aws-sdk-go says after "unable to proxy request - " when the signer could not reach AWS at all.
NETWORK_ERRORS='TLS handshake timeout|dial tcp|i/o timeout|no such host|connection refused|connection reset|network is unreachable'
# What it says when the profile has no usable credentials.
CREDENTIALS_ERRORS='SSO|NoCredentialProviders|ExpiredToken|credentials'

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
      # Network first: an SSO refresh that fails for want of a network names credentials too, and
      # logging in again would not help.
      if grep -qiE "$NETWORK_ERRORS" "$body"; then
        fail "the signer could not reach S3: $(cat "$body")"
      fi
      if grep -qiE "$CREDENTIALS_ERRORS" "$body"; then
        fail "credentials: $(cat "$body")" "run: aws sso login --profile ${PROFILE}"
      fi
      fail "the signer could not proxy the request: $(cat "$body")"
    fi
    ;;
esac

code=$(sed -n 's:.*<Code>\([^<]*\)</Code>.*:\1:p' "$body" | head -n 1)
fail "${code:-no error code} (HTTP ${status})"
