#!/usr/bin/env bash
#
# Unit tests for the cassandra-pods kit's lib/check-start.sh and lib/wait-un.sh. curl, ssh and
# kubectl are stubbed. No cluster, no network.
#
# Run directly:  bash src/test/shell/cassandra-pods-start.test.sh
# Or via gradle: ./gradlew testCassandraPodsStart

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
KIT="${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/kits/cassandra-pods"
tests_run=0
tests_failed=0
current_test=""
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT
mkdir -p "${WORK}/stubs" "${WORK}/kit"
cp -R "${KIT}/lib" "${WORK}/kit/"

# curl: records its URL arguments; the token request answers a token, a manifest HEAD answers
# the status in $STUB_MANIFEST_CODE.
cat > "${WORK}/stubs/curl" << 'STUB'
#!/bin/bash
echo "$*" >> "${STUB_DIR}/curl.calls"
if [ -n "${STUB_CURL_ERROR:-}" ]; then echo "${STUB_CURL_ERROR}" >&2; case "$*" in *"-w"*) printf 000 ;; esac; exit 6; fi
case "$*" in
  *"/token?"*) echo '{"token":"abc"}' ;;
  *"-w"*) printf '%s' "${STUB_MANIFEST_CODE:-200}" ;;
esac
STUB
# ssh: db nodes listed in $STUB_AMI_RUNNING have the AMI Cassandra active.
cat > "${WORK}/stubs/ssh" << 'STUB'
#!/bin/bash
if [ -n "${STUB_SSH_ERROR:-}" ]; then echo "${STUB_SSH_ERROR}" >&2; exit 255; fi
case " ${STUB_AMI_RUNNING:-} " in *" $1 "*) exit 0 ;; esac
exit 3
STUB
# kubectl: `exec ... nodetool status` prints $STUB_UN lines of UN; `get events` prints a marker.
cat > "${WORK}/stubs/kubectl" << 'STUB'
#!/bin/bash
echo "$*" >> "${STUB_DIR}/kubectl.calls"
case "$1" in
  exec) if [ -n "${STUB_NT_ERROR:-}" ]; then echo "${STUB_NT_ERROR}" >&2; exit 1; fi; i=0; while [ "$i" -lt "${STUB_UN:-0}" ]; do echo "UN  10.0.0.$i  1 GiB"; i=$((i + 1)); done ;;
  get) echo "EVENT-MARKER pod cassandra-pods-1 FailedScheduling" ;;
esac
STUB
chmod +x "${WORK}/stubs/"*
export STUB_DIR="${WORK}"

fail() {
  echo "FAIL: ${current_test}: $1"
  tests_failed=$((tests_failed + 1))
}

begin() {
  current_test="$1"
  tests_run=$((tests_run + 1))
  rm -f "${WORK}/curl.calls" "${WORK}/kit/resolved-image.env"
}

run_check() {
  (
    cd "${WORK}/kit" &&
      env PATH="${WORK}/stubs:${PATH}" DB_NODE_COUNT=3 CASSANDRA_VERSION=5.0 CASSANDRA_IMAGE= CASSANDRA_NODES=3 \
        "$@" "${BASH}" lib/check-start.sh
  ) > "${WORK}/out" 2>&1
  rc=$?
  out="$(cat "${WORK}/out")"
}

begin "a known default image passes and is resolved from --version"
run_check
[ "${rc}" -eq 0 ] || fail "exit ${rc}: ${out}"
grep -q '^RESOLVED_IMAGE=cassandra:5.0$' "${WORK}/kit/resolved-image.env" || fail "image not resolved"
grep -q 'library/cassandra' "${WORK}/curl.calls" || fail "Docker Hub official image not looked up under library/"

begin "an unknown image exits 1 and names it"
run_check STUB_MANIFEST_CODE=404 CASSANDRA_VERSION=9.9
[ "${rc}" -eq 1 ] || fail "exit ${rc}"
case "${out}" in *cassandra:9.9*) ;; *) fail "message does not name the image: ${out}" ;; esac
[ ! -f "${WORK}/kit/resolved-image.env" ] || fail "resolved file written for a missing image"

begin "--image overrides --version and is looked up in its own registry"
run_check CASSANDRA_IMAGE=ghcr.io/example/cassandra:sha-abc CASSANDRA_VERSION=4.1
[ "${rc}" -eq 0 ] || fail "exit ${rc}: ${out}"
grep -q '^RESOLVED_IMAGE=ghcr.io/example/cassandra:sha-abc$' "${WORK}/kit/resolved-image.env" || fail "image override ignored"
grep -q 'https://ghcr.io/v2/example/cassandra/manifests/sha-abc' "${WORK}/curl.calls" || fail "wrong registry or path"

begin "--nodes above the db node count exits 1 naming both numbers"
run_check CASSANDRA_NODES=5
[ "${rc}" -eq 1 ] || fail "exit ${rc}"
case "${out}" in *5*3*) ;; *) fail "message does not name both numbers: ${out}" ;; esac
[ ! -f "${WORK}/curl.calls" ] || fail "registry was queried before the node count check"

begin "a running AMI Cassandra exits 1"
run_check STUB_AMI_RUNNING=db1
[ "${rc}" -eq 1 ] || fail "exit ${rc}"
case "${out}" in *db1*job*) ;; *) fail "message does not explain the metrics job clash: ${out}" ;; esac

begin "a digest reference is looked up by its digest, not split as a tag"
run_check CASSANDRA_IMAGE=ghcr.io/example/cassandra@sha256:0123abcd
[ "${rc}" -eq 0 ] || fail "exit ${rc}: ${out}"
grep -q 'https://ghcr.io/v2/example/cassandra/manifests/sha256:0123abcd' "${WORK}/curl.calls" || fail "digest not used as the manifest reference: $(cat "${WORK}/curl.calls")"

begin "a tag plus digest is looked up by the digest"
run_check CASSANDRA_IMAGE=ghcr.io/example/cassandra:5.0@sha256:0123abcd
[ "${rc}" -eq 0 ] || fail "exit ${rc}: ${out}"
grep -q 'ghcr.io/v2/example/cassandra/manifests/sha256:0123abcd' "${WORK}/curl.calls" || fail "digest not used"

begin "a 401 or 403 is not reported as not-found and does not block start"
for code in 401 403; do
  run_check STUB_MANIFEST_CODE=${code} CASSANDRA_IMAGE=123.dkr.ecr.us-west-2.amazonaws.com/cass:branch
  [ "${rc}" -eq 0 ] || fail "${code}: exit ${rc}: ${out}"
  case "${out}" in *"could not verify the image anonymously (HTTP ${code}), continuing"*) ;; *) fail "${code}: no verify warning: ${out}" ;; esac
  case "${out}" in *"not found"*) fail "${code}: reported as not found: ${out}" ;; esac
  grep -q '^RESOLVED_IMAGE=123' "${WORK}/kit/resolved-image.env" || fail "${code}: image not resolved"
done

begin "a registry error other than 404 fails start naming the status"
run_check STUB_MANIFEST_CODE=500
[ "${rc}" -eq 1 ] || fail "exit ${rc}"
case "${out}" in *500*) ;; *) fail "status not shown: ${out}" ;; esac

begin "a network failure shows curl's own message"
run_check STUB_CURL_ERROR="curl: (6) Could not resolve host: registry.example"
[ "${rc}" -eq 1 ] || fail "exit ${rc}"
case "${out}" in *"Could not resolve host: registry.example"*) ;; *) fail "curl message dropped: ${out}" ;; esac

begin "--nodes must be an integer of at least 1"
for bad in 0 abc 2.5 -1; do
  run_check CASSANDRA_NODES=${bad}
  [ "${rc}" -eq 1 ] || fail "'${bad}': exit ${rc}"
  case "${out}" in *"--nodes"*"${bad}"*) ;; *) fail "'${bad}': unclear message: ${out}" ;; esac
done

begin "valid --heap values pass"
for ok in 4G 512M 1g 16G; do
  run_check CASSANDRA_HEAP=${ok}
  [ "${rc}" -eq 0 ] || fail "'${ok}': exit ${rc}: ${out}"
done

begin "a malformed --heap fails before any registry lookup"
for bad in 4.5G 4 G 4GB -2G 0G; do
  run_check CASSANDRA_HEAP=${bad}
  [ "${rc}" -eq 1 ] || fail "'${bad}': exit ${rc}"
  case "${out}" in *"--heap"*"${bad}"*) ;; *) fail "'${bad}': unclear message: ${out}" ;; esac
  [ ! -f "${WORK}/curl.calls" ] || fail "'${bad}': registry was queried"
done

begin "an unreachable db node fails start and says which node and why"
run_check STUB_SSH_ERROR="ssh: connect to host db0 port 22: Connection timed out"
[ "${rc}" -eq 1 ] || fail "exit ${rc}"
case "${out}" in *"could not check db node db0"*"Connection timed out"*) ;; *) fail "message: ${out}" ;; esac

run_wait() {
  (
    cd "${WORK}/kit" &&
      env PATH="${WORK}/stubs:${PATH}" KIT_NAME=cassandra-pods CASSANDRA_NODES=3 EDL_POLL_SECONDS=0 \
        "$@" "${BASH}" lib/wait-un.sh
  ) > "${WORK}/out" 2>&1
  rc=$?
  out="$(cat "${WORK}/out")"
}

begin "the UN wait succeeds when every requested node is UN"
run_wait STUB_UN=3
[ "${rc}" -eq 0 ] || fail "exit ${rc}: ${out}"

begin "the UN wait prints recent events and exits 1 on timeout"
run_wait STUB_UN=2 CASSANDRA_UN_WAIT_SECONDS=1
[ "${rc}" -eq 1 ] || fail "exit ${rc}"
case "${out}" in *EVENT-MARKER*) ;; *) fail "no events printed: ${out}" ;; esac

begin "the UN wait timeout prints the last nodetool error"
run_wait STUB_UN=0 STUB_NT_ERROR="Error: connection refused JMX" CASSANDRA_UN_WAIT_SECONDS=1
[ "${rc}" -eq 1 ] || fail "exit ${rc}"
case "${out}" in *"connection refused JMX"*) ;; *) fail "nodetool error dropped: ${out}" ;; esac

echo "${tests_run} run, ${tests_failed} failed"
[ "${tests_failed}" -eq 0 ]
