#!/usr/bin/env bash
#
# Unit tests for the local stack's store check (localstack/store-check.sh).
#
# The check lists one key of the account bucket through the signer before any backend starts. It must
# tell the signer's own credentials failure (HTTP 502, "unable to proxy request ...") apart from an S3
# error (a 4xx with an XML <Code>), and wait while the signer is still starting.
#
# curl is stubbed: each call answers from a queue of canned responses. No signer, no network. The
# script runs under /bin/sh, as in the gate's alpine image.
#
# Run directly:  bash src/test/shell/local-store-check.test.sh
# Or via gradle: ./gradlew testLocalStoreCheck

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
SCRIPT="${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/localstack/store-check.sh"

tests_run=0
tests_failed=0
WORK=""
trap 'rm -rf "${WORK}"' EXIT

fail() {
  echo "FAIL: $1"
  tests_failed=$((tests_failed + 1))
}

# Each response is "<curl exit code> <http status> <body file>", one per call, in order.
setup() {
  [ -n "${WORK}" ] && rm -rf "${WORK}"
  WORK="$(mktemp -d)"
  mkdir -p "${WORK}/stubs" "${WORK}/bodies"
  : > "${WORK}/responses"
  : > "${WORK}/calls"
  cat > "${WORK}/stubs/curl" <<EOF
#!/usr/bin/env bash
out=""
url=""
while [ \$# -gt 0 ]; do
  case "\$1" in
    -o) out="\$2"; shift 2 ;;
    -w|-sS) [ "\$1" = "-w" ] && shift; shift ;;
    *) url="\$1"; shift ;;
  esac
done
echo "\${url}" >> "${WORK}/calls"
n=\$(wc -l < "${WORK}/calls" | tr -d ' ')
line=\$(sed -n "\${n}p" "${WORK}/responses")
rc=\${line%% *}; rest=\${line#* }; status=\${rest%% *}; bodyfile=\${rest#* }
[ -n "\${out}" ] && cat "${WORK}/bodies/\${bodyfile}" > "\${out}" 2>/dev/null
[ "\${rc}" -eq 7 ] && { echo "curl: (7) Failed to connect" >&2; printf '000'; exit 7; }
printf '%s' "\${status}"
exit "\${rc}"
EOF
  cat > "${WORK}/stubs/sleep" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
  chmod +x "${WORK}/stubs/curl" "${WORK}/stubs/sleep"
}

respond() {
  echo "$1 $2 $3" >> "${WORK}/responses"
}

body() {
  printf '%s' "$2" > "${WORK}/bodies/$1"
}

run_check() {
  PATH="${WORK}/stubs:${PATH}" BUCKET=acct PROFILE=lab SIGNER=store-signer:8080 ATTEMPTS="${ATTEMPTS:-30}" \
    sh "${SCRIPT}" > "${WORK}/out.txt" 2>&1
  echo $? > "${WORK}/rc"
}

rc() { cat "${WORK}/rc"; }
out() { cat "${WORK}/out.txt"; }

test_a_readable_bucket_passes() {
  setup
  body ok '<ListBucketResult><KeyCount>1</KeyCount></ListBucketResult>'
  respond 0 200 ok
  run_check
  [ "$(rc)" = 0 ] || fail "a readable bucket: exit $(rc), $(out)"
  grep -q 'readable with profile lab' "${WORK}/out.txt" || fail "a readable bucket says so: $(out)"
  grep -q '^http://store-signer:8080/acct?list-type=2&prefix=mimir%2F&max-keys=1$' "${WORK}/calls" ||
    fail "it lists mimir/ in the account bucket through the signer: $(cat "${WORK}/calls")"
}

test_the_signers_502_is_a_credentials_failure() {
  setup
  body creds 'unable to proxy request - refresh cached SSO token failed, cached SSO token is expired, or not present, and cannot be refreshed'
  respond 0 502 creds
  run_check
  [ "$(rc)" = 1 ] || fail "expired credentials exit 1, got $(rc)"
  grep -q 'cannot read the account bucket acct with profile lab: credentials: unable to proxy request - refresh cached SSO token failed' "${WORK}/out.txt" ||
    fail "expired credentials name the profile and the signer's reason: $(out)"
  grep -q 'aws sso login --profile lab' "${WORK}/out.txt" || fail "expired credentials give the login command: $(out)"
}

test_every_credentials_text_of_the_signer_is_a_credentials_failure() {
  for reason in \
    'NoCredentialProviders: no valid providers in chain' \
    'ExpiredToken: The security token included in the request is expired' \
    'failed to refresh cached credentials, the SSO session has expired' \
    'unable to retrieve credentials'; do
    setup
    body creds "unable to proxy request - ${reason}"
    respond 0 502 creds
    run_check
    [ "$(rc)" = 1 ] || fail "credentials (${reason}) exit 1, got $(rc)"
    grep -q 'with profile lab: credentials: unable to proxy request' "${WORK}/out.txt" || fail "credentials (${reason}) are named so: $(out)"
    grep -q 'aws sso login --profile lab' "${WORK}/out.txt" || fail "credentials (${reason}) give the login command: $(out)"
  done
}

test_a_signer_that_cannot_reach_s3_is_not_a_credentials_failure() {
  for reason in \
    'RequestError: send request failed caused by: Get "https://s3.eu-west-1.amazonaws.com/acct": net/http: TLS handshake timeout' \
    'dial tcp 52.218.1.1:443: connect: network is unreachable' \
    'read tcp 172.18.0.3:40112->52.218.1.1:443: i/o timeout' \
    'dial tcp: lookup s3.eu-west-1.amazonaws.com on 127.0.0.11:53: no such host'; do
    setup
    body unreachable "unable to proxy request - ${reason}"
    respond 0 502 unreachable
    run_check
    [ "$(rc)" = 1 ] || fail "an unreachable S3 (${reason}) exits 1, got $(rc)"
    grep -q 'with profile lab: the signer could not reach S3: unable to proxy request' "${WORK}/out.txt" ||
      fail "an unreachable S3 (${reason}) is named so: $(out)"
    if grep -q 'credentials\|aws sso login' "${WORK}/out.txt"; then fail "an unreachable S3 (${reason}) is not credentials: $(out)"; fi
  done
}

test_another_signer_failure_is_named_with_the_signers_text_and_no_login_hint() {
  setup
  body other 'unable to proxy request - something else went wrong'
  respond 0 502 other
  run_check
  [ "$(rc)" = 1 ] || fail "another signer failure exits 1, got $(rc)"
  grep -q 'with profile lab: the signer could not proxy the request: unable to proxy request - something else went wrong' "${WORK}/out.txt" ||
    fail "another signer failure carries the signer's text: $(out)"
  if grep -q 'aws sso login' "${WORK}/out.txt"; then fail "another signer failure gives no login hint: $(out)"; fi
}

test_an_s3_error_is_named_by_its_code_not_as_credentials() {
  setup
  body denied '<?xml version="1.0" encoding="UTF-8"?><Error><Code>AccessDenied</Code><Message>Access Denied</Message></Error>'
  respond 0 403 denied
  run_check
  [ "$(rc)" = 1 ] || fail "an S3 403 exits 1, got $(rc)"
  grep -q 'with profile lab: AccessDenied (HTTP 403)' "${WORK}/out.txt" || fail "an S3 403 names its code: $(out)"
  if grep -q 'credentials' "${WORK}/out.txt"; then fail "an S3 403 is not a credentials failure: $(out)"; fi
}

test_a_502_from_elsewhere_is_not_a_credentials_failure() {
  setup
  body gateway '<html>502 Bad Gateway</html>'
  respond 0 502 gateway
  run_check
  [ "$(rc)" = 1 ] || fail "another 502 exits 1, got $(rc)"
  if grep -q 'credentials' "${WORK}/out.txt"; then fail "a 502 without the signer's text is not credentials: $(out)"; fi
  grep -q '(HTTP 502)' "${WORK}/out.txt" || fail "another 502 names its status: $(out)"
}

test_it_waits_for_the_signer_to_listen() {
  setup
  body ok '<ListBucketResult/>'
  respond 7 000 ok
  respond 7 000 ok
  respond 0 200 ok
  run_check
  [ "$(rc)" = 0 ] || fail "a signer that starts late passes: exit $(rc), $(out)"
  [ "$(wc -l < "${WORK}/calls" | tr -d ' ')" = 3 ] || fail "it retried until the signer listened: $(cat "${WORK}/calls")"
}

test_a_signer_that_never_listens_fails() {
  setup
  body ok ''
  respond 7 000 ok
  respond 7 000 ok
  ATTEMPTS=2 run_check
  [ "$(rc)" = 1 ] || fail "a signer that never listens exits 1, got $(rc)"
  grep -q 'the signer did not answer' "${WORK}/out.txt" || fail "a silent signer is named: $(out)"
}

for t in $(declare -F | awk '{print $3}' | grep '^test_'); do
  tests_run=$((tests_run + 1))
  "$t"
done

echo "${tests_run} tests, ${tests_failed} failed"
[ "${tests_failed}" -eq 0 ]
