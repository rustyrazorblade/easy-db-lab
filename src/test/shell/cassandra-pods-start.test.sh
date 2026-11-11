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
  *"/token?"*)
    out=""; prev=""
    for a in "$@"; do [ "$prev" = "-o" ] && out="$a"; prev="$a"; done
    json='{"token":"abc"}'
    [ -n "${STUB_TOKEN_JSON:-}" ] && json="${STUB_TOKEN_JSON}"
    [ -n "$out" ] && printf '%s\n' "$json" > "$out"
    printf '%s' "${STUB_TOKEN_CODE:-200}"
    ;;
  *"-w"*) printf '%s' "${STUB_MANIFEST_CODE:-200}" ;;
esac
STUB
# ssh: db nodes listed in $STUB_AMI_RUNNING have the AMI Cassandra active.
cat > "${WORK}/stubs/ssh" << 'STUB'
#!/bin/bash
echo "$*" >> "${STUB_DIR}/ssh.calls"
if [ -n "${STUB_SSH_ERROR:-}" ]; then echo "${STUB_SSH_ERROR}" >&2; exit 255; fi
case " ${STUB_AMI_RUNNING:-} " in *" $1 "*) exit 0 ;; esac
exit 3
STUB
# kubectl: `exec ... nodetool status` prints $STUB_UN lines of UN; `get events` prints a marker.
cat > "${WORK}/stubs/kubectl" << 'STUB'
#!/bin/bash
echo "$*" >> "${STUB_DIR}/kubectl.calls"
case "$1" in
  apply) cat > "${STUB_DIR}/applied.yaml" ;;
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
  rm -f "${WORK}/curl.calls" "${WORK}/ssh.calls" "${WORK}/kit/resolved-image.env"
}

run_check() {
  (
    cd "${WORK}/kit" &&
      env PATH="${WORK}/stubs:${PATH}" EDL_NODE_SSH=true DB_NODE_COUNT=3 CASSANDRA_VERSION=5.0 CASSANDRA_IMAGE= CASSANDRA_NODES=3 \
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

begin "the build label is the tag for a tagged reference"
run_check CASSANDRA_IMAGE=ghcr.io/example/cassandra:sha-abc
grep -q '^RESOLVED_BUILD=sha-abc$' "${WORK}/kit/resolved-image.env" || fail "build is not the tag: $(cat "${WORK}/kit/resolved-image.env")"

begin "the build label is sha256- plus 12 hex chars for a digest-only reference"
run_check CASSANDRA_IMAGE=ghcr.io/example/cassandra@sha256:0123456789abcdef0123456789abcdef
grep -q '^RESOLVED_BUILD=sha256-0123456789ab$' "${WORK}/kit/resolved-image.env" || fail "build is not the short digest: $(cat "${WORK}/kit/resolved-image.env")"

begin "the build label is the tag for a tag plus digest reference"
run_check CASSANDRA_IMAGE=ghcr.io/example/cassandra:5.0-nightly@sha256:0123456789abcdef0123456789abcdef
grep -q '^RESOLVED_BUILD=5.0-nightly$' "${WORK}/kit/resolved-image.env" || fail "build is not the tag: $(cat "${WORK}/kit/resolved-image.env")"

begin "the build label is the version when no --image is given"
run_check
grep -q '^RESOLVED_BUILD=5.0$' "${WORK}/kit/resolved-image.env" || fail "build is not the version"

begin "an image reference with a character outside the OCI set is rejected before any lookup"
for bad in 'ghcr.io/example/cassandra:a&b' 'ghcr.io/example/cassandra:a|b' 'ghcr.io/example/cass andra:1' 'ghcr.io/example/cassandra:$(id)'; do
  run_check CASSANDRA_IMAGE="${bad}"
  [ "${rc}" -eq 1 ] || fail "'${bad}' exit ${rc}"
  case "${out}" in *"${bad}"*"not a valid image reference"*) ;; *) fail "'${bad}' message: ${out}" ;; esac
  [ ! -f "${WORK}/curl.calls" ] || fail "'${bad}' reached the registry"
  [ ! -f "${WORK}/kit/resolved-image.env" ] || fail "'${bad}' wrote resolved-image.env"
done

begin "apply-statefulset substitutes the image and build literally, even with sed metacharacters"
printf 'image: "@RESOLVED_IMAGE@"\nbuild=@RESOLVED_BUILD@\n' > "${WORK}/kit/statefulset.yaml"
printf "RESOLVED_IMAGE='a&b|c\\\\d'\nRESOLVED_BUILD='x&y'\n" >"${WORK}/kit/resolved-image.env"
(cd "${WORK}/kit" && env PATH="${WORK}/stubs:${PATH}" "${BASH}" lib/apply-statefulset.sh) > "${WORK}/out" 2>&1 || fail "exit $?: $(cat "${WORK}/out")"
grep -qxF 'image: "a&b|c\d"' "${WORK}/applied.yaml" || fail "image not literal: $(cat "${WORK}/applied.yaml")"
grep -qxF 'build=x&y' "${WORK}/applied.yaml" || fail "build not literal"

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
  run_check CASSANDRA_HEAP=${ok} CASSANDRA_MEMORY=32Gi
  [ "${rc}" -eq 0 ] || fail "'${ok}': exit ${rc}: ${out}"
done

begin "a malformed --heap fails before any registry lookup"
for bad in 4.5G 4 G 4GB -2G 0G; do
  run_check CASSANDRA_HEAP=${bad}
  [ "${rc}" -eq 1 ] || fail "'${bad}': exit ${rc}"
  case "${out}" in *"--heap"*"${bad}"*) ;; *) fail "'${bad}': unclear message: ${out}" ;; esac
  [ ! -f "${WORK}/curl.calls" ] || fail "'${bad}': registry was queried"
done

begin "valid --dc names pass"
for ok in dc1 us_east-2 DC_A; do
  run_check CASSANDRA_DC=${ok}
  [ "${rc}" -eq 0 ] || fail "'${ok}': exit ${rc}: ${out}"
done

begin "a --dc that is not a plain name fails before any registry lookup"
for bad in 'a b' 'dc:1' 'dc"1' "dc'1" '{x}' 'dc#1' 'dc,1' '-dc'; do
  run_check CASSANDRA_DC="${bad}"
  [ "${rc}" -eq 1 ] || fail "'${bad}': exit ${rc}"
  case "${out}" in *"--dc"*"${bad}"*) ;; *) fail "'${bad}': unclear message: ${out}" ;; esac
  [ ! -f "${WORK}/curl.calls" ] || fail "'${bad}': registry was queried"
done

begin "an unreachable db node fails start and says which node and why"
run_check STUB_SSH_ERROR="ssh: connect to host db0 port 22: Connection timed out"
[ "${rc}" -eq 1 ] || fail "exit ${rc}"
case "${out}" in *"could not check db node db0"*"Connection timed out"*) ;; *) fail "message: ${out}" ;; esac
[ ! -f "${WORK}/kit/resolved-image.env" ] || fail "resolved file written although a node was not checked"

begin "with node SSH and the AMI Cassandra inactive, every db node is checked and start proceeds"
run_check
[ "${rc}" -eq 0 ] || fail "exit ${rc}: ${out}"
[ "$(grep -c 'systemctl is-active --quiet cassandra' "${WORK}/ssh.calls" 2> /dev/null)" = 3 ] || fail "not every db node checked: $(cat "${WORK}/ssh.calls" 2> /dev/null)"

begin "without node SSH the AMI check is skipped with one line saying why, and start proceeds"
run_check EDL_NODE_SSH=false STUB_SSH_ERROR="easy-db-lab: sshConfig does not exist"
[ "${rc}" -eq 0 ] || fail "exit ${rc}: ${out}"
[ ! -f "${WORK}/ssh.calls" ] || fail "ssh was called: $(cat "${WORK}/ssh.calls")"
[ "$(printf '%s\n' "${out}" | grep -c 'AMI Cassandra check')" = 1 ] || fail "no single skip line: ${out}"
case "${out}" in *"no SSH"*) ;; *) fail "skip line does not say why: ${out}" ;; esac
grep -q '^RESOLVED_IMAGE=cassandra:5.0$' "${WORK}/kit/resolved-image.env" || fail "start did not proceed to resolve the image"

begin "a missing or unknown EDL_NODE_SSH fails start rather than guessing"
for bad in '' yes; do
  run_check EDL_NODE_SSH="${bad}"
  [ "${rc}" -eq 1 ] || fail "'${bad}': exit ${rc}"
  case "${out}" in *EDL_NODE_SSH*) ;; *) fail "'${bad}': message does not name EDL_NODE_SSH: ${out}" ;; esac
  [ ! -f "${WORK}/ssh.calls" ] || fail "'${bad}': ssh was called"
done

# The pod's heap computation, extracted from the shipped template and run through sh (the
# container's /bin/sh): from the `if [ -n "$CASSANDRA_HEAP" ]` through the HEAP_NEWSIZE export.
TEMPLATE="${KIT}/statefulset.yaml.template"
sed -n '/^ *if \[ -n "\$CASSANDRA_HEAP" \]/,/^ *export MAX_HEAP_SIZE=/p' "${TEMPLATE}" | sed 's/^ *//' > "${WORK}/heap-snippet.sh"

# Prints "<MAX_HEAP_SIZE> <HEAP_NEWSIZE>" for --heap $1 (may be empty) in a container limited to $2 MiB.
heap_for() {
  sh -c 'CASSANDRA_HEAP="$1"; MEMORY_LIMIT_MIB="$2"; . "$3"; echo "${MAX_HEAP_SIZE} ${HEAP_NEWSIZE}"' sh "$1" "$2" "${WORK}/heap-snippet.sh" 2>&1
}

# The pod's native port edit, extracted from the shipped template (from the sed through the line
# that checks it) and run through sh against a copy of cassandra.yaml.
sed -n '/^ *sed -i .s\/^native_transport_port/,/^ *fi$/p' "${TEMPLATE}" | sed 's/^ *//' |
  sed 's#/etc/cassandra/cassandra.yaml#"$CASSANDRA_YAML"#g' > "${WORK}/port-snippet.sh"

# The container's sed is GNU sed, whose `-i` takes no suffix; BSD sed (macOS) reads the script as
# one. This shim gives `sed -i SCRIPT FILE` the GNU meaning on either, for the snippet run only.
mkdir -p "${WORK}/gnu-sed"
real_sed="$(command -v sed)"
cat > "${WORK}/gnu-sed/sed" << STUB
#!/bin/sh
if [ "\$1" = -i ]; then
  "${real_sed}" "\$2" "\$3" > "\$3.tmp" && mv "\$3.tmp" "\$3"
else
  exec "${real_sed}" "\$@"
fi
STUB
chmod +x "${WORK}/gnu-sed/sed"

# Runs the port snippet against a cassandra.yaml holding $1; sets rc and out.
port_edit() {
  printf '%s\n' "$1" > "${WORK}/cassandra.yaml"
  out="$(PATH="${WORK}/gnu-sed:${PATH}" CASSANDRA_YAML="${WORK}/cassandra.yaml" sh "${WORK}/port-snippet.sh" 2>&1)"
  rc=$?
}

begin "the port snippet was extracted from the template and checks the edit"
grep -q 'native_transport_port: 30942' "${WORK}/port-snippet.sh" || fail "no port edit in snippet: $(cat "${WORK}/port-snippet.sh")"
grep -q 'grep -q' "${WORK}/port-snippet.sh" || fail "snippet does not check the edit: $(cat "${WORK}/port-snippet.sh")"

begin "a cassandra.yaml with native_transport_port is set to 30942"
port_edit 'cluster_name: x
native_transport_port: 9042
storage_port: 7000'
[ "${rc}" -eq 0 ] || fail "exit ${rc}: ${out}"
grep -qx 'native_transport_port: 30942' "${WORK}/cassandra.yaml" || fail "port not set: $(cat "${WORK}/cassandra.yaml")"

begin "a cassandra.yaml with the port commented out or absent fails, naming the file and the setting"
for yaml in '# native_transport_port: 9042' 'cluster_name: x'; do
  port_edit "${yaml}"
  [ "${rc}" -ne 0 ] || fail "'${yaml}': exit 0"
  case "${out}" in *"${WORK}/cassandra.yaml"*"native_transport_port: 30942"*) ;; *) fail "'${yaml}': message: ${out}" ;; esac
  [ "$(printf '%s\n' "${out}" | wc -l | tr -d ' ')" = 1 ] || fail "'${yaml}': not one line: ${out}"
done

begin "the heap snippet was extracted from the template"
[ -s "${WORK}/heap-snippet.sh" ] || fail "no heap snippet found in the template"
grep -q 'HEAP_NEWSIZE=' "${WORK}/heap-snippet.sh" || fail "snippet lacks HEAP_NEWSIZE"

begin "--heap sets the heap in MiB and HEAP_NEWSIZE is a quarter of it, with a 1M floor"
for pair in "1G:1024M 256M" "4G:4096M 1024M" "1g:1024M 256M" "512M:512M 128M" "16G:16384M 4096M" "2M:2M 1M"; do
  heap="${pair%%:*}"
  want="${pair##*:}"
  got="$(heap_for "${heap}" 65536)"
  [ "${got}" = "${want}" ] || fail "--heap ${heap}: expected '${want}', got '${got}'"
done

begin "with no --heap the heap is half the container memory limit, never the host memory"
for pair in "2048:1024M 256M" "4096:2048M 512M" "1024:512M 128M"; do
  limit="${pair%%:*}"
  want="${pair##*:}"
  got="$(heap_for "" "${limit}")"
  [ "${got}" = "${want}" ] || fail "limit ${limit}Mi: expected '${want}', got '${got}'"
done

begin "valid --memory values pass"
for ok in 2Gi 4096Mi 16Gi; do
  run_check CASSANDRA_MEMORY=${ok}
  [ "${rc}" -eq 0 ] || fail "'${ok}': exit ${rc}: ${out}"
done

begin "a malformed --memory fails before any registry lookup"
for bad in 2G 2 Gi 1.5Gi -2Gi 0Gi 2GiB; do
  run_check CASSANDRA_MEMORY=${bad}
  [ "${rc}" -eq 1 ] || fail "'${bad}': exit ${rc}"
  case "${out}" in *"--memory"*"${bad}"*) ;; *) fail "'${bad}': unclear message: ${out}" ;; esac
  [ ! -f "${WORK}/curl.calls" ] || fail "'${bad}': registry was queried"
done

begin "a --heap that does not fit inside --memory fails and names both"
for pair in "2G:2Gi" "3G:2Gi" "2048M:2Gi" "4G:2048Mi" "8G:4Gi"; do
  run_check CASSANDRA_HEAP="${pair%%:*}" CASSANDRA_MEMORY="${pair##*:}"
  [ "${rc}" -eq 1 ] || fail "${pair}: exit ${rc}: ${out}"
  case "${out}" in *"--heap ${pair%%:*}"*"--memory ${pair##*:}"*) ;; *) fail "${pair}: unclear message: ${out}" ;; esac
  [ ! -f "${WORK}/curl.calls" ] || fail "${pair}: registry was queried"
done

begin "a --heap smaller than --memory passes"
for pair in "1G:2Gi" "1536M:2Gi" "2G:4Gi"; do
  run_check CASSANDRA_HEAP="${pair%%:*}" CASSANDRA_MEMORY="${pair##*:}"
  [ "${rc}" -eq 0 ] || fail "${pair}: exit ${rc}: ${out}"
done

begin "the manifest HEAD accepts OCI index, OCI manifest, Docker manifest list v2 and Docker manifest v2"
run_check
for t in application/vnd.oci.image.index.v1+json application/vnd.oci.image.manifest.v1+json \
  application/vnd.docker.distribution.manifest.list.v2+json application/vnd.docker.distribution.manifest.v2+json; do
  grep -q "${t}" "${WORK}/curl.calls" || fail "Accept header lacks ${t}"
done

begin "Docker Hub official images use the registry-1.docker.io token flow with the library/ prefix"
run_check
grep -q 'auth.docker.io/token?service=registry.docker.io&scope=repository:library/cassandra:pull' "${WORK}/curl.calls" || fail "wrong token request: $(cat "${WORK}/curl.calls")"
grep -q 'https://registry-1.docker.io/v2/library/cassandra/manifests/5.0' "${WORK}/curl.calls" || fail "wrong manifest URL"

begin "ghcr.io uses its own token endpoint and sends the token"
run_check CASSANDRA_IMAGE=ghcr.io/example/cassandra:sha-abc
grep -q 'https://ghcr.io/token?service=ghcr.io&scope=repository:example/cassandra:pull' "${WORK}/curl.calls" || fail "wrong token request"
grep -q 'Authorization: Bearer abc' "${WORK}/curl.calls" || fail "token not sent"

begin "a token answered as access_token is sent"
run_check CASSANDRA_IMAGE=ghcr.io/example/cassandra:sha-abc STUB_TOKEN_JSON='{"access_token":"xyz","expires_in":300}'
[ "${rc}" -eq 0 ] || fail "exit ${rc}: ${out}"
grep -q 'Authorization: Bearer xyz' "${WORK}/curl.calls" || fail "access_token not sent: $(cat "${WORK}/curl.calls")"

begin "token wins over access_token, even when the JSON spans lines and has spaces"
run_check STUB_TOKEN_JSON='{
  "access_token" : "second",
  "token" : "first"
}'
[ "${rc}" -eq 0 ] || fail "exit ${rc}: ${out}"
grep -q 'Authorization: Bearer first' "${WORK}/curl.calls" || fail "token not preferred: $(cat "${WORK}/curl.calls")"

begin "a token answer with neither field is checked anonymously"
run_check STUB_TOKEN_JSON='{"other":"x"}'
[ "${rc}" -eq 0 ] || fail "exit ${rc}: ${out}"
if grep -q 'Authorization' "${WORK}/curl.calls"; then fail "an Authorization header was sent: $(cat "${WORK}/curl.calls")"; fi

begin "without jq on PATH start fails naming jq, before any registry request"
mkdir -p "${WORK}/nojq"
for tool in cat cut mktemp rm; do ln -sf "$(command -v "${tool}")" "${WORK}/nojq/${tool}"; done
for stub in curl ssh; do ln -sf "${WORK}/stubs/${stub}" "${WORK}/nojq/${stub}"; done
(
  cd "${WORK}/kit" &&
    env PATH="${WORK}/nojq" EDL_NODE_SSH=true DB_NODE_COUNT=3 CASSANDRA_VERSION=5.0 CASSANDRA_IMAGE= CASSANDRA_NODES=3 \
      "${BASH}" lib/check-start.sh
) > "${WORK}/out" 2>&1
rc=$?
out="$(cat "${WORK}/out")"
[ "${rc}" -eq 1 ] || fail "exit ${rc}: ${out}"
case "${out}" in *"jq"*"not installed"*) ;; *) fail "message does not name jq: ${out}" ;; esac
[ ! -f "${WORK}/curl.calls" ] || fail "registry was queried without jq"

begin "a failed token request exits 1 naming the image"
run_check STUB_TOKEN_CODE=503
[ "${rc}" -eq 1 ] || fail "exit ${rc}"
case "${out}" in *cassandra:5.0*503*) ;; *) fail "message does not name the image and status: ${out}" ;; esac

begin "a registry with no token endpoint (404) is still checked anonymously"
run_check STUB_TOKEN_CODE=404
[ "${rc}" -eq 0 ] || fail "exit ${rc}: ${out}"

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

bound_for() {
  run_wait STUB_UN="$1" CASSANDRA_NODES="$1"
  printf '%s' "${out}" | sed -n 's/.*up to \([0-9][0-9]*\)s.*/\1/p' | head -n 1
}

begin "the UN wait bound grows linearly with the node count"
b1="$(bound_for 1)"
b3="$(bound_for 3)"
b5="$(bound_for 5)"
if [ -z "${b1}" ] || [ -z "${b3}" ] || [ -z "${b5}" ]; then
  fail "no bound printed: ${out}"
else
  [ "${b3}" -gt "${b1}" ] && [ "${b5}" -gt "${b3}" ] || fail "bound does not grow: ${b1} ${b3} ${b5}"
  [ $((b5 - b3)) -eq $((b3 - b1)) ] || fail "bound is not linear in nodes: ${b1} ${b3} ${b5}"
  # One pod's readiness window is initialDelay 30s + 60 failures x 10s period = 630s.
  [ $(((b3 - b1) / 2)) -ge 630 ] || fail "per-node budget below one pod's readiness window"
fi

begin "CASSANDRA_UN_WAIT_SECONDS overrides the computed bound"
run_wait STUB_UN=3 CASSANDRA_UN_WAIT_SECONDS=77
case "${out}" in *"up to 77s"*) ;; *) fail "override ignored: ${out}" ;; esac

echo "${tests_run} run, ${tests_failed} failed"
[ "${tests_failed}" -eq 0 ]
