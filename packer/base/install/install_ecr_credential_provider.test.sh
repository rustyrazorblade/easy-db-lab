#!/usr/bin/env bash
#
# Unit tests for install_ecr_credential_provider.sh: the base AMI installs the kubelet ECR
# credential provider and a CredentialProviderConfig matching *.dkr.ecr.*.amazonaws.com where K3s
# reads them, picks the binary for the build architecture, and refuses a binary whose checksum
# does not match the pinned one.
#
# sudo, curl and sha256sum are stubbed on PATH, and the install root is a sandbox, so this needs
# no root, no network and no Docker.
#
# Run directly:  ./install_ecr_credential_provider.test.sh
# Or via gradle: ./gradlew testEcrCredentialProviderInstall

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="${SCRIPT_DIR}/install_ecr_credential_provider.sh"

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT

BIN="${SANDBOX}/bin"
CALLS="${SANDBOX}/calls.log"
mkdir -p "$BIN"

cat >"${BIN}/sudo" <<'SHIM'
#!/bin/bash
exec "$@"
SHIM

# curl records the URL and writes a fake binary to the -o target.
cat >"${BIN}/curl" <<SHIM
#!/bin/bash
out=""
url=""
while [ \$# -gt 0 ]; do
  case "\$1" in
    -o) out="\$2"; shift 2 ;;
    http*) url="\$1"; shift ;;
    *) shift ;;
  esac
done
echo "curl \$url" >> "${CALLS}"
printf 'fake binary\n' > "\$out"
SHIM

# sha256sum prints the hash the test sets in FAKE_SHA256.
cat >"${BIN}/sha256sum" <<'SHIM'
#!/bin/bash
echo "${FAKE_SHA256}  $1"
SHIM

chmod +x "${BIN}"/*

tests_run=0
tests_failed=0

pass() {
  tests_run=$((tests_run + 1))
  echo "ok   - $1"
}

fail() {
  tests_run=$((tests_run + 1))
  tests_failed=$((tests_failed + 1))
  echo "FAIL - $1"
}

pinned_sha() {
  grep "^SHA256_$1=" "$SCRIPT" | cut -d'"' -f2
}

run_install() {
  local arch="$1" sha="$2" root="$3"
  : > "$CALLS"
  PATH="${BIN}:${PATH}" ARCH="$arch" FAKE_SHA256="$sha" CREDENTIAL_PROVIDER_ROOT="$root" bash "$SCRIPT" 2>&1
}

for arch in amd64 arm64; do
  root="${SANDBOX}/${arch}"
  upper="$(echo "$arch" | tr '[:lower:]' '[:upper:]')"
  OUTPUT="$(run_install "$arch" "$(pinned_sha "$upper")" "$root")"
  STATUS=$?

  if [[ "$STATUS" -eq 0 ]]; then
    pass "${arch}: install exits 0"
  else
    fail "${arch}: install should exit 0, got ${STATUS}: ${OUTPUT}"
  fi

  if grep -q "/linux/${arch}/ecr-credential-provider-linux-${arch}$" "$CALLS"; then
    pass "${arch}: downloads the ${arch} binary"
  else
    fail "${arch}: expected a download of the ${arch} binary, got: $(cat "$CALLS")"
  fi

  if [[ -x "${root}/bin/ecr-credential-provider" ]]; then
    pass "${arch}: the binary is executable in the K3s credential provider bin dir"
  else
    fail "${arch}: expected ${root}/bin/ecr-credential-provider to be executable"
  fi

  config="${root}/config.yaml"
  if grep -q '^kind: CredentialProviderConfig$' "$config" 2>/dev/null &&
    grep -q '^  - name: ecr-credential-provider$' "$config" &&
    grep -qF -- '- "*.dkr.ecr.*.amazonaws.com"' "$config" &&
    grep -q 'apiVersion: credentialprovider.kubelet.k8s.io/v1' "$config"; then
    pass "${arch}: the config names the provider and matches *.dkr.ecr.*.amazonaws.com"
  else
    fail "${arch}: unexpected config: $(cat "$config" 2>/dev/null)"
  fi
done

root="${SANDBOX}/mismatch"
OUTPUT="$(run_install amd64 "0000000000000000000000000000000000000000000000000000000000000000" "$root")"
STATUS=$?
if [[ "$STATUS" -ne 0 && "$OUTPUT" == *"checksum mismatch"* ]]; then
  pass "a checksum mismatch fails the install"
else
  fail "a checksum mismatch should fail, got ${STATUS}: ${OUTPUT}"
fi
if [[ ! -e "${root}/bin/ecr-credential-provider" ]]; then
  pass "a checksum mismatch installs no binary"
else
  fail "a checksum mismatch should install no binary"
fi

OUTPUT="$(run_install s390x "x" "${SANDBOX}/s390x")"
STATUS=$?
if [[ "$STATUS" -ne 0 && "$OUTPUT" == *"unsupported ARCH"* ]]; then
  pass "an unsupported ARCH fails the install"
else
  fail "an unsupported ARCH should fail, got ${STATUS}: ${OUTPUT}"
fi

echo
echo "${tests_run} tests, ${tests_failed} failed"
[[ "$tests_failed" -eq 0 ]]
