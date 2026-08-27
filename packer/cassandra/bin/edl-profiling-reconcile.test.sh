#!/usr/bin/env bash
#
# Unit tests for edl-profiling-reconcile — the node-resident reconciler that attaches
# async-profiler to the running Cassandra JVM, ships completed JFR chunks to Pyroscope,
# and prunes the profile directory.
#
# No Docker, no network, no real JVM: `asprof`, `cassandra-pid` and `curl` are stubbed, the
# profile directory is a fixture, and the clock is injected via EDL_NOW. That makes the whole
# decision table plus the shipping and pruning rules directly assertable.
#
# Run directly:  ./edl-profiling-reconcile.test.sh
# Or via gradle: ./gradlew testProfilingReconcile

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RECONCILE="${SCRIPT_DIR}/edl-profiling-reconcile"

tests_run=0
tests_failed=0

ok() {
  tests_run=$((tests_run + 1))
  echo "ok   - $1"
}

fail() {
  tests_run=$((tests_run + 1))
  tests_failed=$((tests_failed + 1))
  echo "FAIL - $1"
  [[ $# -gt 1 ]] && echo "       $2"
}

assert_eq() {
  local desc="$1" expected="$2" actual="$3"
  if [[ "$expected" == "$actual" ]]; then
    ok "$desc"
  else
    fail "$desc" "expected [$expected], got [$actual]"
  fi
}

assert_contains() {
  local desc="$1" haystack="$2" needle="$3"
  if [[ "$haystack" == *"$needle"* ]]; then
    ok "$desc"
  else
    fail "$desc" "expected to find [$needle] in: $haystack"
  fi
}

assert_not_contains() {
  local desc="$1" haystack="$2" needle="$3"
  if [[ "$haystack" != *"$needle"* ]]; then
    ok "$desc"
  else
    fail "$desc" "did not expect [$needle] in: $haystack"
  fi
}

assert_file() {
  local desc="$1" path="$2"
  if [[ -f "$path" ]]; then
    ok "$desc"
  else
    fail "$desc" "expected file to exist: $path"
  fi
}

assert_no_file() {
  local desc="$1" path="$2"
  if [[ ! -e "$path" ]]; then
    ok "$desc"
  else
    fail "$desc" "expected file to be gone: $path"
  fi
}

# --- sandbox -----------------------------------------------------------------

# GNU on the nodes and in CI, BSD on a macOS developer machine.
# `touch -t` reads LOCAL time, so the stamp must be formatted in local time too or every fixture
# lands one UTC offset away from where the test meant to put it.
set_mtime() {
  local path="$1" epoch="$2" stamp
  if ! stamp="$(date -d "@${epoch}" +%Y%m%d%H%M.%S 2>/dev/null)"; then
    stamp="$(date -r "${epoch}" +%Y%m%d%H%M.%S)"
  fi
  touch -t "$stamp" "$path"
}

# An address on a real interface, for the one fixture that has to be off loopback.
#
# `hostname -I` is the Linux answer and is what CI and the nodes use; `ipconfig getifaddr` is the
# macOS one. The `ifconfig` scrape is the last resort for a machine with neither.
first_non_loopback_address() {
  local address
  address="$(hostname -I 2>/dev/null | awk '{print $1}')"
  if [[ -z "$address" ]] && command -v ipconfig >/dev/null 2>&1; then
    address="$(ipconfig getifaddr en0 2>/dev/null)"
  fi
  if [[ -z "$address" ]] && command -v ifconfig >/dev/null 2>&1; then
    address="$(ifconfig 2>/dev/null | awk '/inet /{print $2}' | grep -v '^127\.' | head -1)"
  fi
  echo "$address"
}

NOW=1756000000

setup() {
  SANDBOX="$(mktemp -d)"
  STUB_BIN="$SANDBOX/bin"
  PROFILE_DIR="$SANDBOX/profiles"
  CONFIG="$SANDBOX/profiling.json"
  STUB_LOG="$SANDBOX/stub.log"
  ASPROF_ARGV="$SANDBOX/asprof.argv"
  CURL_LOG="$SANDBOX/curl.log"
  OUTPUT="$SANDBOX/output.txt"
  ASPROF_STATE="$SANDBOX/asprof.state"
  CURL_BODY="$SANDBOX/curl.body"
  mkdir -p "$STUB_BIN" "$PROFILE_DIR"
  : >"$STUB_LOG"
  : >"$ASPROF_ARGV"
  : >"$CURL_LOG"
  : >"$CURL_BODY"

  # The stub models the one asprof behaviour the reconciler leans on: `status` answers with what the
  # JVM actually has attached, and that changes only when a start or stop *succeeds*. Modelling it
  # is what makes "never assert a detach worked — ask the JVM again" testable at all.
  #
  # STUB_ASPROF_START_EXIT / STUB_ASPROF_STOP_EXIT make the feature's primary failure mode — a
  # refused jattach, from PrivateTmp, a java.io.tmpdir mismatch, or perf_event_paranoid — reachable.
  cat >"$STUB_BIN/asprof" <<'STUB'
#!/usr/bin/env bash
printf '%s\0' "$@" >>"$ASPROF_ARGV"
echo "asprof $*" >>"$STUB_LOG"

attached() { cat "$ASPROF_STATE" 2>/dev/null || echo "${STUB_ASPROF_RUNNING:-false}"; }

case "${1:-}" in
  status)
    if [[ "$(attached)" == "true" ]]; then
      echo "Profiling is running for 30 seconds"
    else
      echo "Profiler is not active"
    fi
    ;;
  start)
    # STUB_ASPROF_START_SLEEP models the attach itself taking a long time — jattach waiting on a JVM
    # in a safepoint. It is the phase of a pass that can actually consume TimeoutStartSec, so it is
    # the phase a SIGTERM most likely lands in.
    [[ "${STUB_ASPROF_START_SLEEP:-0}" != "0" ]] && sleep "$STUB_ASPROF_START_SLEEP"
    if [[ "${STUB_ASPROF_START_EXIT:-0}" != "0" ]]; then
      echo "${STUB_ASPROF_START_STDERR:-Could not open /tmp/.java_pid4242}" >&2
      exit "${STUB_ASPROF_START_EXIT}"
    fi
    echo true >"$ASPROF_STATE"
    ;;
  stop)
    if [[ "${STUB_ASPROF_STOP_EXIT:-0}" != "0" ]]; then
      echo "${STUB_ASPROF_STOP_STDERR:-Target JVM is not responding}" >&2
      exit "${STUB_ASPROF_STOP_EXIT}"
    fi
    echo false >"$ASPROF_STATE"
    ;;
  # STUB_ASPROF_METRICS_HANG models the jattach round-trip blocking on a JVM in a long safepoint —
  # which is both a leading reason a pass is killed and, without a timeout, what would keep the
  # kill handler from persisting anything.
  metrics)
    [[ "${STUB_ASPROF_METRICS_HANG:-0}" != "0" ]] && sleep "$STUB_ASPROF_METRICS_HANG"
    echo "asprof_sample_count 123"
    ;;
esac
exit 0
STUB

  cat >"$STUB_BIN/cassandra-pid" <<'STUB'
#!/usr/bin/env bash
echo "${STUB_CASSANDRA_PID:-0}"
STUB

  # The readiness gate. Ready by default, because every test that is not about the gate assumes a
  # database that has finished starting — which is what the reconciler saw on every pass but the
  # first few after a restart, and what the bash tier could not distinguish before the gate existed.
  #
  # STUB_DB_READY=false is the state that killed a node: a database process that exists and is not
  # yet able to take a signal. The stub cannot reproduce the kill — nothing here is a JVM — so what
  # these tests pin is the decision, which is the part that was wrong.
  # It answers the way the real probe does — a reason code, a colon, a sentence — because the
  # reconciler records that code in its attach and deferral lines, and a stub that printed nothing
  # would let that plumbing rot unnoticed.
  cat >"$STUB_BIN/cassandra-ready" <<'STUB'
#!/usr/bin/env bash
echo "cassandra-ready $*" >>"$STUB_LOG"
if [[ "${STUB_DB_READY:-true}" == "true" ]]; then
  echo "${STUB_READY_VERDICT:-listening}: the native transport is listening on port 9042"
  exit 0
fi
echo "${STUB_READY_VERDICT:-starting}: nothing is listening on port 9042"
exit 1
STUB

  # Mimics `curl -w '%{http_code}'`: the status goes to stdout, diagnostics to stderr. A `@-` body
  # is captured so the OTLP payload can be asserted; chunk uploads pass a file path instead and are
  # left alone, since reading stdin there would block.
  cat >"$STUB_BIN/curl" <<'STUB'
#!/usr/bin/env bash
echo "curl $*" >>"$CURL_LOG"
[[ "$*" == *"@-"* ]] && cat >>"$CURL_BODY"
# STUB_CURL_SLEEP models a Pyroscope that answers slowly, which is what puts a pass over
# TimeoutStartSec and gets it killed mid-upload.
[[ "${STUB_CURL_SLEEP:-0}" != "0" ]] && sleep "$STUB_CURL_SLEEP"
echo "${STUB_HTTP_STATUS:-200}"
if [[ "${STUB_CURL_EXIT:-0}" != "0" ]]; then
  echo "${STUB_CURL_STDERR:-curl: (7) Failed to connect to 10.0.1.5 port 4040}" >&2
  exit "${STUB_CURL_EXIT}"
fi
exit 0
STUB

  chmod +x "$STUB_BIN"/*
}

teardown() {
  [[ -n "${SANDBOX:-}" ]] && rm -rf "$SANDBOX"
}

write_config() {
  local enabled="$1" args_json="$2" loop="${3:-1m}" retention="${4:-60}" max_bytes="${5:-2147483648}"
  cat >"$CONFIG" <<EOF
{
  "enabled": $enabled,
  "asprofArgs": $args_json,
  "loopInterval": "$loop",
  "retentionMinutes": $retention,
  "maxBytes": $max_bytes,
  "pyroscopeUrl": "http://10.0.1.5:4040",
  "clusterName": "test-cluster",
  "updatedAt": "2026-08-24T10:15:30Z"
}
EOF
}

# What a healthy pass leaves behind. The bounds and the ingest URL are part of it because a later
# pass whose desired-state document has gone unreadable recovers them from here rather than
# reverting to the tool's built-in defaults.
write_effective_state() {
  local pid="$1" args_json="$2" retention="${3:-60}"
  cat >"$PROFILE_DIR/effective-state.json" <<EOF
{"running": true, "desiredEnabled": true, "pid": $pid, "args": $args_json,
 "loopInterval": "1m", "retentionMinutes": $retention, "maxBytes": 2147483648,
 "pyroscopeUrl": "http://10.0.1.5:4040", "clusterName": "test-cluster", "startedAt": 1755999000,
 "chunksPending": 0, "chunksShipped": 0, "chunksRejected": 0, "shipFailures": 0,
 "prunedForAge": 0, "prunedForSize": 0, "prunedUnshipped": 0,
 "bytesOnDisk": 0, "lastError": "", "attachFailures": 0, "lastAttachError": "",
 "attachDeferred": false, "configError": "", "updatedAt": 1755999000}
EOF
}

# The sandbox environment every pass runs under, as an array so the foreground and background
# runners share one definition.
pass_env() {
  PASS_ENV=(
    PATH="$STUB_BIN:$PATH"
    STUB_LOG="$STUB_LOG"
    ASPROF_ARGV="$ASPROF_ARGV"
    ASPROF_STATE="$ASPROF_STATE"
    CURL_LOG="$CURL_LOG"
    CURL_BODY="$CURL_BODY"
    EDL_PROFILING_CONFIG="$CONFIG"
    EDL_PROFILE_DIR="$PROFILE_DIR"
    EDL_ASPROF="$STUB_BIN/asprof"
    EDL_CASSANDRA_PID="$STUB_BIN/cassandra-pid"
    EDL_CASSANDRA_READY="$STUB_BIN/cassandra-ready"
    EDL_METRICS_FILE="$SANDBOX/metrics.prom"
    EDL_HOSTNAME="db0"
    EDL_NOW="$NOW"
  )
}

# Runs one reconcile pass. Extra KEY=VALUE arguments become environment overrides.
run_pass() {
  pass_env
  env "${PASS_ENV[@]}" "$@" bash "$RECONCILE" >"$OUTPUT" 2>&1
  echo $?
}

# Starts a pass in the background and leaves its pid in PASS_PID, so a test can signal it.
#
# `exec` matters: without it $! is the subshell's pid and a signal never reaches the reconciler,
# which would make a test of the SIGTERM trap pass whether or not the trap exists.
run_pass_bg() {
  pass_env
  (exec env "${PASS_ENV[@]}" "$@" bash "$RECONCILE" >"$OUTPUT" 2>&1) &
  PASS_PID=$!
}

stub_log() { cat "$STUB_LOG"; }
curl_log() { cat "$CURL_LOG"; }

# The effective-state document is JSON, built by yq, so it is read as JSON rather than matched as
# text: a substring assertion over it pins the formatter rather than the contract.
state_field() { jq -r "$1" <"$PROFILE_DIR/effective-state.json"; }

# Makes a completed chunk `age_seconds` old.
make_chunk() {
  local name="$1" age_seconds="$2" size="${3:-1024}"
  local path="$PROFILE_DIR/$name"
  head -c "$size" /dev/zero >"$path"
  set_mtime "$path" "$((NOW - age_seconds))"
}

# --- decision table ----------------------------------------------------------

test_starts_when_not_running() {
  setup
  write_config true '["-e", "cpu"]'
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=false >/dev/null

  local argv
  argv="$(tr '\0' ' ' <"$ASPROF_ARGV")"
  assert_contains "starts a session when none is running" "$argv" "start -e cpu"
  assert_contains "supplies JFR output format" "$argv" "-o jfr"
  assert_contains "supplies the rotation interval" "$argv" "--loop 1m"
  assert_contains "supplies a %t-bearing output path" "$argv" "-f $PROFILE_DIR/cassandra-%p-%t.jfr"
  assert_contains "targets the current Cassandra pid" "$argv" "4242"
  assert_not_contains "does not stop anything first" "$(stub_log)" "asprof stop"
  teardown
}

test_matching_session_is_left_alone() {
  setup
  write_config true '["-e", "cpu"]'
  write_effective_state 4242 '["-e", "cpu"]'
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true >/dev/null

  # A positive anchor first. Four negative assertions alone pass against a script replaced with
  # `exit 0`, and this covers the most-executed production path there is — the steady-state pass.
  assert_contains "the live JVM is asked what it has attached" "$(stub_log)" "asprof status 4242"
  assert_eq "the converged pass rewrites state for this pid" "4242" "$(state_field '.pid')"
  assert_eq "and reports the session as running" "true" "$(state_field '.running')"

  assert_not_contains "matching session is not restarted" "$(stub_log)" "asprof start"
  assert_not_contains "matching session is not stopped" "$(stub_log)" "asprof stop"

  # Idempotence: a second pass over the same state must also change nothing.
  : >"$STUB_LOG"
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true >/dev/null
  assert_not_contains "a second pass changes nothing (start)" "$(stub_log)" "asprof start"
  assert_not_contains "a second pass changes nothing (stop)" "$(stub_log)" "asprof stop"
  teardown
}

test_empty_args_session_is_left_alone() {
  setup
  # "profile with async-profiler's own defaults", which is what an MCP client sending
  # `asprofArgs: []` means. The converged case must be recognised as converged: if an empty argument
  # list is mistaken for "no record for this pid", every pass tears the session down and re-attaches,
  # losing samples across each detach and resetting the session age forever.
  write_config true '[]'
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=false >/dev/null
  assert_contains "an empty argument list still attaches" "$(stub_log)" "asprof start"

  # The stub keeps the attached state, so this pass sees exactly what the timer would see 60s later.
  : >"$STUB_LOG"
  run_pass STUB_CASSANDRA_PID=4242 >/dev/null
  assert_not_contains "a converged empty-args session is not stopped" "$(stub_log)" "asprof stop"
  assert_not_contains "a converged empty-args session is not restarted" "$(stub_log)" "asprof start"

  : >"$STUB_LOG"
  run_pass STUB_CASSANDRA_PID=4242 >/dev/null
  assert_not_contains "and stays untouched on the pass after that" "$(stub_log)" "asprof start"
  teardown
}

test_differing_spec_stops_then_starts() {
  setup
  write_config true '["-e", "wall"]'
  write_effective_state 4242 '["-e", "cpu"]'
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true >/dev/null

  local log
  log="$(stub_log)"
  assert_contains "differing spec stops the running session" "$log" "asprof stop"
  assert_contains "differing spec starts the new session" "$log" "asprof start"
  assert_contains "new session carries the new arguments" "$(tr '\0' ' ' <"$ASPROF_ARGV")" "start -e wall"
  teardown
}

test_interval_only_change_also_switches() {
  setup
  write_config true '["-e", "cpu", "-i", "5ms"]'
  write_effective_state 4242 '["-e", "cpu", "-i", "10ms"]'
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true >/dev/null

  assert_contains "an interval-only difference switches the session" "$(stub_log)" "asprof stop"
  teardown
}

test_disabled_and_running_stops() {
  setup
  write_config false '["-e", "cpu"]'
  write_effective_state 4242 '["-e", "cpu"]'
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true >/dev/null

  local log
  log="$(stub_log)"
  assert_contains "disabled desired state stops the session" "$log" "asprof stop"
  assert_not_contains "disabled desired state does not start one" "$log" "asprof start"
  teardown
}

test_pid_mismatched_record_is_discarded() {
  setup
  write_config true '["-e", "cpu"]'
  # The record names a JVM that no longer exists; its args must not be trusted.
  write_effective_state 999 '["-e", "cpu"]'
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true >/dev/null

  local log
  log="$(stub_log)"
  assert_contains "a record naming a dead pid converges by stopping" "$log" "asprof stop"
  assert_contains "a record naming a dead pid converges by starting" "$log" "asprof start"
  teardown
}

test_unknown_running_session_converges() {
  setup
  write_config true '["-e", "cpu"]'
  # No record at all: someone attached by hand, or the record was lost.
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true >/dev/null

  local log
  log="$(stub_log)"
  assert_contains "an unrecognised session is stopped" "$log" "asprof stop"
  assert_contains "an unrecognised session is replaced" "$log" "asprof start"
  teardown
}

test_corrupt_config_leaves_session_untouched() {
  setup
  printf '%s' '{"enabled": true, "asprofArgs": [' >"$CONFIG"
  local exit_code
  exit_code="$(run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true)"

  assert_eq "a corrupt config still exits 0" "0" "$exit_code"
  assert_not_contains "a corrupt config never stops a running session" "$(stub_log)" "asprof stop"
  assert_not_contains "a corrupt config never starts a session" "$(stub_log)" "asprof start"
  assert_contains "a corrupt config reports a structured error" "$(cat "$OUTPUT")" "event=config_unreadable"
  teardown
}

test_a_negative_retention_window_is_rejected_wholesale() {
  setup
  # The other half of the CLI's bounds check. The node cannot act on a negative window, so it
  # refuses the whole document — and refusing must never mean stopping what is already running,
  # because the operator asked for profiling and a bad bound is not a request to stop.
  write_config true '["-e", "cpu"]' "1m" "-5"
  write_effective_state 4242 '["-e", "cpu"]'
  local exit_code
  exit_code="$(run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true)"

  assert_eq "a negative retention window still exits 0" "0" "$exit_code"
  assert_contains "a negative retention window is reported as unreadable config" \
    "$(cat "$OUTPUT")" "event=config_unreadable"
  assert_not_contains "a negative retention window never stops the running session" "$(stub_log)" "asprof stop"
  teardown
}

# A rejected bound must not survive the rejection.
#
# The validation block reads every field into a shell variable before testing it, so on a failure the
# rejected value is already loaded. The recovery block that resets it used to be gated on
# effective-state.json existing, which left one branch uncovered: a node whose reconciler has never
# completed a pass. There RETENTION_MINUTES reached `$((NOW - RETENTION_MINUTES * 60))` verbatim —
# and bash arithmetic expands array subscripts, so `NAME[$(command)]` runs the command. Under `set -u`
# an unset base aborts the pass with exit 1 instead, so pruning never ran and the unit entered
# *failed*, contradicting this script's contract that it exits 0 on every non-fatal condition.
#
# The other bad-bound tests all write an effective-state document first, so they only ever exercise
# the recovery path. This one is the first pass on a fresh node.
test_a_rejected_bound_is_never_used_on_a_node_with_no_prior_state() {
  setup
  # Well-formed JSON: the failure under test is the bound, not the document.
  cat >"$CONFIG" <<'CFG'
{
  "enabled": "sometimes",
  "asprofArgs": ["-e", "cpu"],
  "loopInterval": "1m",
  "retentionMinutes": "x[$(touch /tmp/edl-pwned)]",
  "maxBytes": "lots",
  "pyroscopeUrl": "http://10.0.1.5:4040",
  "clusterName": "test-cluster",
  "updatedAt": "2026-08-24T10:15:30Z"
}
CFG
  rm -f "$PROFILE_DIR/effective-state.json"
  local exit_code
  exit_code="$(run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true)"

  assert_eq "a rejected bound on a node with no prior state still exits 0" "0" "$exit_code"
  assert_contains "and is reported as an unreadable config" "$(cat "$OUTPUT")" "event=config_unreadable"
  assert_file "the pass still reports what it did" "$PROFILE_DIR/effective-state.json"
  assert_eq "the rejected retention window is replaced by the fallback" "60" \
    "$(state_field '.retentionMinutes')"
  assert_eq "the rejected byte ceiling is replaced by the fallback" "2147483648" \
    "$(state_field '.maxBytes')"
  # A non-boolean reaches env() unquoted, producing a document the Kotlin reader cannot decode.
  assert_eq "a non-boolean enabled flag becomes a boolean" "false" "$(state_field '.desiredEnabled')"
  teardown
}

# The rotation interval is interpolated raw into the effective-state document, which `status` parses.
# A value carrying a quote is escaped correctly in the desired-state document the CLI writes, so it
# arrives here intact and only breaks on the way out — producing JSON that never parses again,
# rewritten identically every pass with nothing logged. `status` then answers "unknown" for that node
# forever, hiding every attach failure, ship failure and rejection on it.
#
# The CLI is the primary gate. This is the node refusing to act on a value that reached it anyway.
test_a_malformed_loop_interval_is_refused_wholesale() {
  setup
  # Valid JSON carrying an invalid interval: exactly what the CLI would have written before --loop
  # was validated, so the failure under test is the interval itself and not a broken document.
  cat >"$CONFIG" <<'EOF'
{
  "enabled": true,
  "asprofArgs": ["-e", "cpu"],
  "loopInterval": "30s\"",
  "retentionMinutes": 60,
  "maxBytes": 2147483648,
  "pyroscopeUrl": "http://10.0.1.5:4040",
  "clusterName": "test-cluster",
  "updatedAt": "2026-08-24T10:15:30Z"
}
EOF
  write_effective_state 4242 '["-e", "cpu"]'
  local exit_code
  exit_code="$(run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true)"

  assert_eq "a malformed rotation interval still exits 0" "0" "$exit_code"
  assert_contains "a malformed rotation interval is reported as unreadable config" \
    "$(cat "$OUTPUT")" "event=config_unreadable"
  assert_not_contains "and never stops the running session" "$(stub_log)" "asprof stop"
  assert_eq "the document status reads is left parseable rather than rewritten broken" "4242" \
    "$(jq -r '.pid' <"$PROFILE_DIR/effective-state.json" 2>/dev/null)"
  teardown
}

# asprof --loop also takes an hh:mm:ss time of day, which rotates once a day. This tool refuses it:
# it ships every completed chunk continuously and sizes both the completion grace window and the
# Pyroscope upload window from a fixed interval, so a daily rotation is a configuration that looks
# entirely valid and produces no usable profiles. Refused at the CLI, and refused again here.
test_a_time_of_day_rotation_is_refused() {
  setup
  write_config true '["-e", "cpu"]' "02:30:00"
  write_effective_state 4242 '["-e", "cpu"]'
  local exit_code
  exit_code="$(run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true)"

  assert_eq "a time-of-day rotation still exits 0" "0" "$exit_code"
  assert_contains "a time-of-day rotation is refused as unreadable config" \
    "$(cat "$OUTPUT")" "event=config_unreadable"
  assert_not_contains "the node never attaches with it" "$(stub_log)" "asprof start"
  assert_not_contains "and refusing it never stops a running session" "$(stub_log)" "asprof stop"
  teardown
}

test_missing_config_is_idle() {
  setup
  local exit_code
  exit_code="$(run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=false)"

  assert_eq "a missing config exits 0" "0" "$exit_code"
  assert_contains "a missing config says so, rather than passing silently" \
    "$(cat "$OUTPUT")" "event=profiling_idle reason=no_desired_state"
  assert_not_contains "a missing config starts nothing" "$(stub_log)" "asprof start"

  # Reporting continues: an unconfigured node must still be visible in VictoriaMetrics, or "nobody
  # configured this node" and "the reconciler is dead" look identical there.
  assert_file "a missing config still writes metrics" "$SANDBOX/metrics.prom"
  assert_eq "and names the reason in effective state" "no_desired_state" "$(state_field '.configError')"
  teardown
}

test_cassandra_down_still_ships_and_prunes() {
  setup
  write_config true '["-e", "cpu"]'
  make_chunk "cassandra-1-100.jfr" 600
  make_chunk "cassandra-1-200.jfr" 300
  local exit_code
  exit_code="$(run_pass STUB_CASSANDRA_PID=0 STUB_ASPROF_RUNNING=false)"

  assert_eq "Cassandra being down still exits 0" "0" "$exit_code"
  assert_not_contains "Cassandra being down skips attaching" "$(stub_log)" "asprof start"
  assert_file "Cassandra being down still ships completed chunks" "$PROFILE_DIR/cassandra-1-100-shipped.jfr"
  teardown
}

test_final_chunk_ships_after_the_session_stops() {
  setup
  # `profile stop` ran a pass ago: the reconciler detached, which finalized the in-flight chunk.
  # Nothing will ever be newer than it, so a rule that always skips the newest file would strand the
  # last minute of the run until retention deleted it.
  write_config false '[]'
  make_chunk "cassandra-1-100.jfr" 600
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=false >/dev/null

  assert_file "the last chunk of a stopped session still ships" "$PROFILE_DIR/cassandra-1-100-shipped.jfr"
  teardown
}

# Refusing to act on a document this node cannot read is correct. Going dark is not, and this script
# used to do both: it returned before shipping, pruning and reporting. The profiler keeps rotating
# chunks regardless, so the directory grew without bound on the same volume as the database's data;
# every edl_jfr_* series stopped being pushed, which silently RESOLVES the documented
# `desired == 1 and attached == 0` alert rather than firing it; and effective-state.json stopped
# being rewritten, so `status` reported the node as STALE and told the operator to go and look at
# the one component that was still working.
test_corrupt_config_still_ships_prunes_and_reports() {
  setup
  write_effective_state 4242 '["-e", "cpu"]'
  make_chunk "cassandra-1-050-shipped.jfr" 7300
  make_chunk "cassandra-1-100.jfr" 600
  make_chunk "cassandra-1-200.jfr" 5
  printf '%s' '{"enabled": true, "asprofArgs": [' >"$CONFIG"
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true >/dev/null

  assert_not_contains "a corrupt config still never stops a running session" "$(stub_log)" "asprof stop"
  assert_file "a completed chunk still ships" "$PROFILE_DIR/cassandra-1-100-shipped.jfr"
  assert_no_file "an over-age chunk is still pruned" "$PROFILE_DIR/cassandra-1-050-shipped.jfr"

  local metrics
  metrics="$(cat "$SANDBOX/metrics.prom")"
  assert_contains "the gauges keep being written, so the alert can still fire" \
    "$metrics" "edl_jfr_profiling_desired 1"
  assert_contains "and the corrupt document is its own signal" "$metrics" "edl_jfr_config_unreadable 1"
  assert_contains "counters keep being pushed to the collector" "$(curl_log)" "http://localhost:4318/v1/metrics"

  assert_eq "effective state is still rewritten, so status is not reported as stale" \
    "$NOW" "$(state_field '.updatedAt')"
  assert_eq "and names the corrupt document as the reason" "config_unreadable" "$(state_field '.configError')"
  teardown
}

# The bounds are the operator's, not the tool's. Reverting to the built-in 60 minutes because the
# document went unreadable would prune away exactly the longer window someone chose with --retention
# — the same silent substitution `stop` already announces on the CLI side.
test_corrupt_config_prunes_under_last_known_good_bounds() {
  setup
  # A previous good pass ran with a 24-hour window and recorded it.
  write_config true '["-e", "cpu"]' "1m" "1440"
  make_chunk "cassandra-1-100-shipped.jfr" 7300
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true >/dev/null
  assert_file "the long window spares a two-hour-old chunk" "$PROFILE_DIR/cassandra-1-100-shipped.jfr"

  # Now the document is corrupted out from under the running session.
  printf '%s' '{"enabled": true, "asprofArgs": [' >"$CONFIG"
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true >/dev/null

  assert_file "the operator's own retention window survives an unreadable config" \
    "$PROFILE_DIR/cassandra-1-100-shipped.jfr"
  assert_eq "and is recorded as what the pass ran under" "1440" "$(state_field '.retentionMinutes')"
  assert_eq "the last-known-good desired state is reported, not dropped" "true" "$(state_field '.desiredEnabled')"
  teardown
}

# Without the ingest URL inside the fail-closed guard, a document missing it produced the literal
# URL `null/ingest?...`: every upload failed forever and was reported as jfr_ship_failed, sending
# the operator to look at Pyroscope and the network when the document on this node is what is wrong.
test_a_missing_ingest_url_is_refused_wholesale() {
  setup
  cat >"$CONFIG" <<'CFG'
{
  "enabled": true,
  "asprofArgs": ["-e", "cpu"],
  "loopInterval": "1m",
  "retentionMinutes": 60,
  "maxBytes": 2147483648,
  "clusterName": "test-cluster",
  "updatedAt": "2026-08-24T10:15:30Z"
}
CFG
  make_chunk "cassandra-1-100.jfr" 600
  make_chunk "cassandra-1-200.jfr" 5
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true >/dev/null

  local output
  output="$(cat "$OUTPUT")"
  assert_contains "a missing ingest URL is reported as an unreadable config" "$output" "event=config_unreadable"
  assert_contains "and shipping says why it did nothing" "$output" "event=jfr_ship_skipped reason=no_pyroscope_url"
  assert_not_contains "nothing is POSTed at a URL that cannot work" "$(curl_log)" "null/ingest"
  assert_file "the chunk is left for a pass that can ship it" "$PROFILE_DIR/cassandra-1-100.jfr"
  teardown
}

# --- attach and detach failures ----------------------------------------------

# A refused jattach is this design's anticipated failure mode — it is why cassandra.service carries
# a DO-NOT-EDIT warning about PrivateTmp and java.io.tmpdir. Everything below is the contract an
# operator has to be able to rely on when it happens.
test_failed_attach_is_recorded_without_failing_the_pass() {
  setup
  write_config true '["-e", "cpu"]'
  local exit_code
  exit_code="$(run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=false \
    STUB_ASPROF_START_EXIT=1 STUB_ASPROF_START_STDERR="Could not open /tmp/.java_pid4242: No such file")"

  assert_eq "a failed attach still exits 0, so the timer unit does not go into failure" "0" "$exit_code"

  local output
  output="$(cat "$OUTPUT")"
  assert_contains "a failed attach is logged" "$output" "event=jfr_attach_failed"
  assert_contains "a failed attach keeps the PrivateTmp hint" "$output" "hint=check_PrivateTmp_and_java.io.tmpdir"
  assert_contains "a failed attach reports what asprof actually said" "$output" "Could not open /tmp/.java_pid4242"

  local state
  state="$(cat "$PROFILE_DIR/effective-state.json")"
  assert_contains "a failed attach is never recorded as running" "$state" '"running": false'
  assert_contains "a failed attach records no arguments, because nothing is applied" "$state" '"args": []'
  assert_contains "the operator's intent stays visible next to the outcome" "$state" '"desiredEnabled": true'
  assert_contains "the attach error is durable, not only in journald" "$state" '"lastAttachError": "Could not open'

  assert_contains "attach failures are counted" "$(cat "$SANDBOX/metrics.prom")" "edl_jfr_attach_failures_total 1"
  teardown
}

test_a_hostile_tool_message_cannot_corrupt_the_state_document() {
  setup
  write_config true '["-e", "cpu"]'
  # `status` reads this document; a message that broke the JSON would turn a diagnosable attach
  # failure into "unknown state on this node", which is the opposite of what it is there for.
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=false STUB_ASPROF_START_EXIT=1 \
    STUB_ASPROF_START_STDERR='he said "no" \ and then
newline' >/dev/null

  assert_eq "the effective state stays parseable JSON" "false" \
    "$(jq -r '.running' <"$PROFILE_DIR/effective-state.json" 2>/dev/null)"
  assert_contains "the message still survives in readable form" \
    "$(jq -r '.lastAttachError' <"$PROFILE_DIR/effective-state.json")" "he said"
  teardown
}

test_failed_detach_is_never_recorded_as_a_clean_stop() {
  setup
  write_config true '["-e", "wall"]'
  write_effective_state 4242 '["-e", "cpu"]'
  local exit_code
  exit_code="$(run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true \
    STUB_ASPROF_STOP_EXIT=1 STUB_ASPROF_STOP_STDERR="Target JVM is not responding")"

  assert_eq "a failed detach still exits 0" "0" "$exit_code"

  local output
  output="$(cat "$OUTPUT")"
  assert_contains "a failed detach is logged" "$output" "event=jfr_detach_failed"
  assert_contains "a failed detach reports what asprof actually said" "$output" "Target JVM is not responding"

  local state
  state="$(cat "$PROFILE_DIR/effective-state.json")"
  assert_contains "a session that refused to stop is still reported as running" "$state" '"running": true'
  assert_eq "effective state records the arguments still applied, not the requested ones" \
    "-e cpu" "$(state_field '.args | join(" ")')"
  teardown
}

test_a_failed_switch_retries_on_the_next_pass() {
  setup
  write_config true '["-e", "wall"]'
  write_effective_state 4242 '["-e", "cpu"]'
  # Pass 1: the detach fails, so the old session is still live with the old arguments.
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true STUB_ASPROF_STOP_EXIT=1 >/dev/null

  # Pass 2 with a healthy asprof must converge. Recording the *requested* arguments in pass 1 would
  # make this pass read its own wish back, believe it had converged, and profile the old event
  # forever while `profile status` claimed otherwise.
  : >"$STUB_LOG"
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true >/dev/null

  local log
  log="$(stub_log)"
  assert_contains "the next pass stops the stale session" "$log" "asprof stop"
  assert_contains "the next pass starts the requested one" "$log" "asprof start"
  assert_contains "the new session carries the requested arguments" "$(tr '\0' ' ' <"$ASPROF_ARGV")" "start -e wall"

  local state
  state="$(cat "$PROFILE_DIR/effective-state.json")"
  assert_eq "effective state now records the applied arguments" "-e wall" "$(state_field '.args | join(" ")')"
  assert_contains "effective state reports the session running" "$state" '"running": true'
  teardown
}

test_a_failed_detach_does_not_start_a_second_session() {
  setup
  write_config true '["-e", "wall"]'
  write_effective_state 4242 '["-e", "cpu"]'
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true STUB_ASPROF_STOP_EXIT=1 >/dev/null

  # Anchored on the detach actually having been attempted and refused, so this cannot pass against
  # a script that does nothing at all.
  assert_contains "the detach is attempted" "$(stub_log)" "asprof stop 4242"
  assert_contains "and its refusal is reported" "$(cat "$OUTPUT")" "event=jfr_detach_failed"
  assert_not_contains "nothing is started on top of a session that would not detach" "$(stub_log)" "asprof start"
  teardown
}

# --- argv fidelity -----------------------------------------------------------

test_argv_fidelity() {
  setup
  # Spaces, single and double quotes, and a newline in one argument.
  write_config true '["-e", "cpu", "--include", "a b '\''q'\'' \"d\"\nsecond"]'
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=false >/dev/null

  # Read back the NUL-delimited argv the stub recorded and take the element after --include.
  local recorded="" element take=false
  while IFS= read -r -d '' element; do
    if [[ "$take" == "true" ]]; then
      recorded="$element"
      break
    fi
    [[ "$element" == "--include" ]] && take=true
  done <"$ASPROF_ARGV"

  assert_eq "a hostile argument survives as exactly one argv element" \
    "$(printf 'a b '\''q'\'' "d"\nsecond')" "$recorded"
  teardown
}

# --- shipping ----------------------------------------------------------------

test_newest_chunk_is_never_shipped() {
  setup
  write_config true '["-e", "cpu"]'
  make_chunk "cassandra-1-100.jfr" 600
  make_chunk "cassandra-1-200.jfr" 500
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true >/dev/null

  assert_file "the older chunk is shipped" "$PROFILE_DIR/cassandra-1-100-shipped.jfr"
  assert_no_file "the newest chunk is never shipped" "$PROFILE_DIR/cassandra-1-200-shipped.jfr"
  teardown
}

test_chunk_inside_the_grace_window_is_not_shipped() {
  setup
  write_config true '["-e", "cpu"]' "1m"
  # 5s old: inside the loop interval (60s) plus the 10s guard.
  make_chunk "cassandra-1-100.jfr" 5
  make_chunk "cassandra-1-200.jfr" 1
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true >/dev/null

  assert_no_file "a chunk inside the grace window is not shipped" "$PROFILE_DIR/cassandra-1-100-shipped.jfr"
  teardown
}

test_successful_upload_is_marked_shipped() {
  setup
  write_config true '["-e", "cpu"]'
  make_chunk "cassandra-1-100.jfr" 600
  make_chunk "cassandra-1-200.jfr" 500
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true STUB_HTTP_STATUS=200 >/dev/null

  assert_file "a 2xx marks the chunk shipped" "$PROFILE_DIR/cassandra-1-100-shipped.jfr"
  local log
  log="$(curl_log)"
  assert_contains "the upload targets the ingest endpoint" "$log" "http://10.0.1.5:4040/ingest"
  assert_contains "the upload declares JFR format" "$log" "format=jfr"
  assert_contains "the upload is labelled with hostname and cluster" "$log" "name=cassandra%7Bhostname%3Ddb0%2Ccluster%3Dtest-cluster%7D"
  assert_contains "the upload sends raw octet-stream bytes" "$log" "application/octet-stream"
  assert_not_contains "the upload is not gzipped" "$log" "gzip"

  # Where the profile lands on Grafana's timeline. The chunk closed at NOW-600 and covers the loop
  # interval before that, so the window is [mtime - loop, mtime] — not [now - loop, now]. Getting
  # this wrong is silent: the upload still returns 2xx and every counter stays clean.
  assert_contains "the upload window ends when the chunk was closed" "$log" "until=$((NOW - 600))"
  assert_contains "the upload window starts one rotation earlier" "$log" "from=$((NOW - 600 - 60))"
  teardown
}

test_upload_window_tracks_the_configured_loop_interval() {
  setup
  # Half the default rotation, so a hard-coded 60 would place every profile at the wrong start.
  write_config true '["-e", "cpu"]' "30s"
  make_chunk "cassandra-1-100.jfr" 600
  make_chunk "cassandra-1-200.jfr" 500
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true STUB_HTTP_STATUS=200 >/dev/null

  assert_contains "the upload window spans the configured rotation interval" \
    "$(curl_log)" "from=$((NOW - 600 - 30))&until=$((NOW - 600))"
  teardown
}

test_shipped_chunks_stay_retrievable_and_are_never_reshipped() {
  setup
  write_config true '["-e", "cpu"]'
  make_chunk "cassandra-1-100.jfr" 600
  make_chunk "cassandra-1-200.jfr" 500
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true STUB_HTTP_STATUS=200 >/dev/null

  # `fetch` and `flamegraph` list the node with `ls -1t <dir>/*.jfr`, so a shipped chunk that no
  # longer answers to that glob is invisible to the CLI — and in steady state nearly every chunk on
  # the node is a shipped one.
  local listing
  listing="$(cd "$PROFILE_DIR" && ls -1 ./*.jfr 2>/dev/null)"
  assert_contains "a shipped chunk still answers to the *.jfr glob" "$listing" "cassandra-1-100-shipped.jfr"

  # ...while still being excluded from the shipping queue, so ship-once is preserved.
  : >"$CURL_LOG"
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true STUB_HTTP_STATUS=200 >/dev/null
  assert_not_contains "a shipped chunk is never uploaded twice" "$(curl_log)" "cassandra-1-100-shipped.jfr"

  local state
  state="$(cat "$PROFILE_DIR/effective-state.json")"
  assert_contains "shipped chunks are counted as shipped" "$state" '"chunksShipped": 1'
  # The one remaining unshipped chunk is the open one, which a live session is still writing. It is
  # not "pending": no pass will ever ship it until it rotates, so counting it left
  # edl_jfr_chunks_pending stuck at 1 on a perfectly healthy node.
  assert_eq "the chunk still being written is not counted as pending" "0" "$(state_field '.chunksPending')"
  teardown
}

# edl_jfr_chunks_pending is what the shipper could have shipped and did not, so it must apply the
# shipper's whole rule — including the grace window, not just the open-chunk exclusion.
#
# With the default `--loop 1m` against a 60s timer, the chunk that rotated most recently is *always*
# inside the grace window when metrics are written. Counting it left the gauge at 1 on a perfectly
# healthy node, and the docs tell an operator that any non-zero value is a real backlog — so an alert
# written from that table fires permanently, everywhere, forever. The earlier open-chunk fix moved
# that defect one file along rather than removing it.
#
# The fixture is a realistic steady state rather than the convenient one: chunks well past the window
# ship, and the freshly rotated one has not had its chance yet.
test_a_freshly_rotated_chunk_is_not_yet_a_backlog() {
  setup
  write_config true '["-e", "cpu"]'
  make_chunk "cassandra-1-100.jfr" 130
  # 40s old: closed, but inside the 70s completion window, so no pass would have shipped it yet.
  make_chunk "cassandra-1-200.jfr" 40
  make_chunk "cassandra-1-300.jfr" 0
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true >/dev/null

  assert_file "the eligible chunk ships" "$PROFILE_DIR/cassandra-1-100-shipped.jfr"
  assert_eq "a chunk still inside the completion window is not a backlog" "0" \
    "$(state_field '.chunksPending')"
  assert_contains "and the gauge agrees" "$(cat "$SANDBOX/metrics.prom")" "edl_jfr_chunks_pending 0"
  teardown
}

# The other direction, so the fix above cannot be "always report 0". These chunks are past the
# completion window and the shipper tried and failed, which is the backlog the gauge exists to show.
test_chunks_the_shipper_failed_to_send_are_counted_as_pending() {
  setup
  write_config true '["-e", "cpu"]'
  make_chunk "cassandra-1-100.jfr" 600
  make_chunk "cassandra-1-200.jfr" 500
  make_chunk "cassandra-1-300.jfr" 0
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true STUB_HTTP_STATUS=503 >/dev/null

  assert_eq "chunks that could have shipped and did not are the backlog" "2" \
    "$(state_field '.chunksPending')"
  teardown
}

test_server_error_leaves_the_chunk_for_retry() {
  setup
  write_config true '["-e", "cpu"]'
  make_chunk "cassandra-1-100.jfr" 600
  make_chunk "cassandra-1-200.jfr" 500
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true STUB_HTTP_STATUS=500 >/dev/null

  assert_file "a 5xx leaves the chunk in place" "$PROFILE_DIR/cassandra-1-100.jfr"
  assert_no_file "a 5xx does not mark the chunk shipped" "$PROFILE_DIR/cassandra-1-100-shipped.jfr"
  assert_contains "a 5xx is logged as a shipping failure" "$(cat "$OUTPUT")" "event=jfr_ship_failed"
  assert_contains "a 5xx increments the failure counter" "$(cat "$SANDBOX/metrics.prom")" "edl_jfr_ship_failures_total 1"
  teardown
}

test_network_failure_reports_what_curl_said() {
  setup
  write_config true '["-e", "cpu"]'
  make_chunk "cassandra-1-100.jfr" 600
  make_chunk "cassandra-1-200.jfr" 500
  # status 000 is curl's "no HTTP response at all", which on its own says nothing about why.
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true STUB_HTTP_STATUS=000 \
    STUB_CURL_EXIT=7 STUB_CURL_STDERR="curl: (7) Failed to connect to 10.0.1.5 port 4040" >/dev/null

  local output
  output="$(cat "$OUTPUT")"
  assert_contains "a network failure carries curl's exit code" "$output" "curl_exit=7"
  assert_contains "a network failure carries curl's own message" "$output" "Failed to connect to 10.0.1.5 port 4040"
  teardown
}

test_client_error_is_rejected_and_never_retried() {
  setup
  write_config true '["-e", "cpu"]'
  make_chunk "cassandra-1-100.jfr" 600
  make_chunk "cassandra-1-200.jfr" 500
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true STUB_HTTP_STATUS=400 >/dev/null

  assert_file "a 4xx marks the chunk rejected" "$PROFILE_DIR/cassandra-1-100.jfr.rejected"
  assert_contains "a 4xx increments the rejection counter" "$(cat "$SANDBOX/metrics.prom")" "edl_jfr_ship_rejected_total 1"
  assert_contains "rejections are counted apart from failures" "$(cat "$SANDBOX/metrics.prom")" "edl_jfr_ship_failures_total 0"

  # A rejected chunk must never be uploaded again, so a later good chunk keeps flowing.
  : >"$CURL_LOG"
  make_chunk "cassandra-1-300.jfr" 400
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true STUB_HTTP_STATUS=200 >/dev/null
  assert_not_contains "a rejected chunk is never retried" "$(curl_log)" "cassandra-1-100.jfr"
  assert_file "later chunks keep shipping" "$PROFILE_DIR/cassandra-1-200-shipped.jfr"
  teardown
}

# The series label is percent-encoded a character at a time. In a UTF-8 locale that yields code
# points rather than bytes, so `café` encodes as `caf%E9` — Latin-1, not what those bytes are — and
# an emoji as `%1F600`, which is not a well-formed escape at all. Pyroscope answers 4xx, a 4xx is
# permanent, and every chunk that cluster ever produces is renamed .rejected and never retried:
# total loss on a 60s timer, needing nothing more hostile than an accented cluster name.
test_a_non_ascii_cluster_name_encodes_as_utf8_bytes() {
  setup
  cat >"$CONFIG" <<'CFG'
{
  "enabled": true,
  "asprofArgs": ["-e", "cpu"],
  "loopInterval": "1m",
  "retentionMinutes": 60,
  "maxBytes": 2147483648,
  "pyroscopeUrl": "http://10.0.1.5:4040",
  "clusterName": "café-😀",
  "updatedAt": "2026-08-24T10:15:30Z"
}
CFG
  make_chunk "cassandra-1-100.jfr" 600
  make_chunk "cassandra-1-200.jfr" 5
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8 >/dev/null

  assert_contains "a non-ASCII cluster name percent-encodes as UTF-8 bytes" \
    "$(curl_log)" "cluster%3Dcaf%C3%A9-%F0%9F%98%80"
  assert_file "so the chunk ships rather than being rejected forever" "$PROFILE_DIR/cassandra-1-100-shipped.jfr"
  teardown
}

# The exact boundary of the completion grace window: one rotation plus GRACE_EXTRA_SECONDS. A chunk
# at exactly that age is complete and must ship; one second younger must not. Without both, deleting
# GRACE_EXTRA_SECONDS entirely leaves the whole suite green.
test_a_chunk_exactly_on_the_grace_boundary_ships() {
  setup
  write_config true '["-e", "cpu"]' "1m"
  make_chunk "cassandra-1-100.jfr" 70
  make_chunk "cassandra-1-200.jfr" 5
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true >/dev/null

  assert_file "a chunk exactly one rotation plus the grace old is complete" \
    "$PROFILE_DIR/cassandra-1-100-shipped.jfr"
  teardown
}

test_a_chunk_one_second_inside_the_grace_boundary_does_not_ship() {
  setup
  write_config true '["-e", "cpu"]' "1m"
  # 69s: past the 60s rotation, but inside the extra guard that covers two rotations landing in the
  # same second. Shipping it would upload a file async-profiler may still be flushing.
  make_chunk "cassandra-1-100.jfr" 69
  make_chunk "cassandra-1-200.jfr" 5
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true >/dev/null

  assert_no_file "the grace guard is more than the rotation interval alone" \
    "$PROFILE_DIR/cassandra-1-100-shipped.jfr"
  teardown
}

# TimeoutStartSec is 300s and each upload waits up to 30. An unbounded queue against a merely slow
# Pyroscope therefore runs past the timeout, systemd SIGTERMs the pass, and everything it learned is
# lost — including LAST_SUCCESS, so edl_jfr_ship_last_success_timestamp_seconds reports a stale value
# while shipping is in fact partly working.
test_a_backlog_is_shipped_a_bounded_number_of_chunks_per_pass() {
  setup
  write_config true '["-e", "cpu"]'
  local age
  for age in 1000 900 800 700 600 500 400 300; do
    make_chunk "cassandra-1-${age}.jfr" "$age"
  done
  make_chunk "cassandra-1-005.jfr" 5
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true >/dev/null

  local shipped
  shipped="$(cd "$PROFILE_DIR" && ls -1 ./*-shipped.jfr 2>/dev/null | wc -l | tr -d ' ')"
  assert_eq "one pass ships at most its chunk budget" "6" "$shipped"
  assert_contains "and says the pass was truncated rather than failing silently" \
    "$(cat "$OUTPUT")" "event=jfr_ship_truncated"
  assert_contains "a truncated pass is its own gauge, not a dead reconciler" \
    "$(cat "$SANDBOX/metrics.prom")" "edl_jfr_ship_truncated 1"

  # The rest drains on the next pass rather than being lost.
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true >/dev/null
  shipped="$(cd "$PROFILE_DIR" && ls -1 ./*-shipped.jfr 2>/dev/null | wc -l | tr -d ' ')"
  assert_eq "the backlog drains on later passes" "8" "$shipped"
  teardown
}

# --- pruning -----------------------------------------------------------------

test_age_pruning() {
  setup
  write_config true '["-e", "cpu"]' "1m" "60"
  make_chunk "cassandra-1-100-shipped.jfr" 7200
  make_chunk "cassandra-1-200-shipped.jfr" 300
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true >/dev/null

  assert_no_file "a chunk past the retention window is deleted" "$PROFILE_DIR/cassandra-1-100-shipped.jfr"
  assert_file "a younger chunk is spared" "$PROFILE_DIR/cassandra-1-200-shipped.jfr"
  teardown
}

test_unshipped_chunks_are_age_pruned() {
  setup
  write_config true '["-e", "cpu"]' "1m" "60"
  # Pyroscope has been refusing connections; nothing has shipped.
  make_chunk "cassandra-1-100.jfr" 7200
  make_chunk "cassandra-1-200.jfr" 100
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true STUB_HTTP_STATUS=000 >/dev/null

  assert_no_file "an unshipped chunk past the window is pruned too" "$PROFILE_DIR/cassandra-1-100.jfr"
  teardown
}

# The age bound is the mechanism that destroys an operator's profiles when shipping has been
# failing, and it did it with a bare `rm -f`: no line, no counter. "My profiles never showed up in
# Pyroscope" then has no answer after the fact, because the evidence deleted itself silently.
test_age_pruning_is_logged_and_counted() {
  setup
  write_config true '["-e", "cpu"]' "1m" "60"
  # Pyroscope has been refusing connections, so this one dies unshipped: real data loss.
  make_chunk "cassandra-1-050.jfr" 7300
  make_chunk "cassandra-1-100-shipped.jfr" 7200
  make_chunk "cassandra-1-200.jfr" 100
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true STUB_HTTP_STATUS=000 >/dev/null

  local output metrics
  output="$(cat "$OUTPUT")"
  metrics="$(cat "$SANDBOX/metrics.prom")"

  assert_contains "discarding a chunk that never shipped is a warning, because it is data loss" \
    "$output" "<4>level=warn event=jfr_pruned_for_age chunk=cassandra-1-050.jfr class=pending"
  assert_contains "discarding an already-shipped chunk is routine, and says so" \
    "$output" "<6>level=info event=jfr_pruned_for_age chunk=cassandra-1-100-shipped.jfr class=shipped"
  assert_contains "the age of what was discarded is on the line" "$output" "age_seconds=7300"
  assert_contains "age pruning is counted" "$metrics" "edl_jfr_pruned_for_age_total 2"
  assert_contains "data loss is counted apart from routine pruning" "$metrics" "edl_jfr_pruned_unshipped_total 1"
  teardown
}

test_pruning_a_rejected_chunk_is_not_counted_as_lost_data() {
  setup
  # A chunk Pyroscope refused can never ship, and is already counted by the rejection counter.
  # Counting its deletion as lost data too would fire the "profiles are being destroyed before they
  # reach Pyroscope" signal every time an unclean shutdown produced a truncated chunk.
  write_config true '["-e", "cpu"]' "1m" "60"
  make_chunk "cassandra-1-050.jfr.rejected" 7300
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true >/dev/null

  local metrics
  metrics="$(cat "$SANDBOX/metrics.prom")"
  assert_no_file "a rejected chunk is still age-pruned" "$PROFILE_DIR/cassandra-1-050.jfr.rejected"
  assert_contains "and counted as pruned" "$metrics" "edl_jfr_pruned_for_age_total 1"
  assert_contains "but not as unshipped data loss" "$metrics" "edl_jfr_pruned_unshipped_total 0"
  assert_contains "its class is named on the line" "$(cat "$OUTPUT")" \
    "event=jfr_pruned_for_age chunk=cassandra-1-050.jfr.rejected class=rejected"
  teardown
}

test_byte_ceiling_prunes_oldest_first() {
  setup
  # All chunks are well within the age window, so only the byte ceiling can prune them.
  write_config true '["-e", "cpu"]' "1m" "60" "3000"
  make_chunk "cassandra-1-100-shipped.jfr" 300 2000
  make_chunk "cassandra-1-200-shipped.jfr" 200 2000
  make_chunk "cassandra-1-300.jfr" 100 2000
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true >/dev/null

  assert_no_file "the byte ceiling prunes the oldest chunk" "$PROFILE_DIR/cassandra-1-100-shipped.jfr"
  assert_file "the byte ceiling spares the newest chunk" "$PROFILE_DIR/cassandra-1-300.jfr"
  assert_contains "size pruning is counted too" \
    "$(cat "$SANDBOX/metrics.prom")" "edl_jfr_pruned_for_size_total 2"
  assert_contains "giving up shipped bytes first costs no unshipped data" \
    "$(cat "$SANDBOX/metrics.prom")" "edl_jfr_pruned_unshipped_total 0"
  teardown
}

# The age cutoff is `mtime < NOW - retention*60`, so a chunk at exactly the cutoff is inside the
# window the operator asked for and is kept. Flipping the comparison to `<=` deletes it, and without
# a fixture on the boundary that mutant survives the whole suite.
test_a_chunk_exactly_on_the_age_cutoff_is_kept() {
  setup
  write_config true '["-e", "cpu"]' "1m" "60"
  make_chunk "cassandra-1-100-shipped.jfr" 3600
  make_chunk "cassandra-1-050-shipped.jfr" 3601
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true >/dev/null

  assert_file "a chunk exactly at the retention window is inside it" "$PROFILE_DIR/cassandra-1-100-shipped.jfr"
  assert_no_file "a chunk one second past it is not" "$PROFILE_DIR/cassandra-1-050-shipped.jfr"
  teardown
}

# The byte ceiling stops at `total <= MAX_BYTES`, so a directory sitting exactly at the ceiling is
# already within it. Flipping that to `<` deletes one more chunk on every pass forever at exactly
# the ceiling — a permanent, silent leak of profiles that no counter distinguishes from a node
# genuinely producing too fast.
test_a_directory_exactly_at_the_byte_ceiling_is_left_alone() {
  setup
  write_config true '["-e", "cpu"]' "1m" "60" "2048"
  make_chunk "cassandra-1-100-shipped.jfr" 300 1024
  make_chunk "cassandra-1-200-shipped.jfr" 200 1024
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true >/dev/null

  assert_file "exactly at the ceiling is within the ceiling" "$PROFILE_DIR/cassandra-1-100-shipped.jfr"
  assert_contains "so nothing is pruned for size" \
    "$(cat "$SANDBOX/metrics.prom")" "edl_jfr_pruned_for_size_total 0"

  # One byte over, and exactly one chunk goes.
  make_chunk "cassandra-1-300-shipped.jfr" 100 1
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true >/dev/null
  assert_no_file "one byte over the ceiling prunes the oldest" "$PROFILE_DIR/cassandra-1-100-shipped.jfr"
  assert_file "and nothing more than that" "$PROFILE_DIR/cassandra-1-200-shipped.jfr"
  teardown
}

# The persistence path must not be able to block on the condition it exists to survive.
#
# `finish` is what the TERM handler calls, and it runs write_metrics before write_effective_state.
# write_metrics shells to `asprof metrics <pid>`, a jattach round-trip into the target JVM. A pass is
# SIGTERMed precisely when something is wedged and an unresponsive JVM is a leading cause — so
# without a time box that call hangs, systemd escalates to SIGKILL at TimeoutStopSec, and the state
# document that runs after it is never written. Every other external call in this path is bounded.
test_a_wedged_jvm_cannot_block_the_persistence_path() {
  setup
  write_config true '["-e", "cpu"]'
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true STUB_ASPROF_METRICS_HANG=20 >/dev/null

  assert_file "a wedged asprof still leaves the metrics file" "$SANDBOX/metrics.prom"
  assert_contains "the reconciler's own counters are written regardless" \
    "$(cat "$SANDBOX/metrics.prom")" "edl_jfr_session_attached 1"
  assert_file "and the state document still lands" "$PROFILE_DIR/effective-state.json"
  assert_eq "with this pass's own clock, not a stale one" "$NOW" "$(state_field '.updatedAt')"
  # The profiler-side numbers are the part that is given up. They are a convenience on the textfile;
  # the state document is what `profile status` reads.
  assert_not_contains "the profiler's own numbers are what a timeout gives up" \
    "$(cat "$SANDBOX/metrics.prom")" "asprof_sample_count"
  teardown
}

# prunedUnshipped is the only counter that means irreversible loss rather than reclaimed disk, and
# the user guide calls it the answer to "my profiles never showed up". It lived only in journald and
# the metrics push, so `profile status` — where an operator actually asks that question — could
# not render it and no typed event could carry it.
test_lost_chunks_are_recorded_where_the_operator_looks() {
  setup
  write_config true '["-e", "cpu"]' "1m" "60"
  make_chunk "cassandra-1-050.jfr" 7300
  make_chunk "cassandra-1-100-shipped.jfr" 7200
  make_chunk "cassandra-1-200.jfr" 100
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true STUB_HTTP_STATUS=000 >/dev/null

  assert_eq "chunks destroyed before they ever shipped reach effective state" "1" \
    "$(state_field '.prunedUnshipped')"
  assert_eq "as do routine age prunes" "2" "$(state_field '.prunedForAge')"
  assert_eq "and size prunes" "0" "$(state_field '.prunedForSize')"
  teardown
}

# --- observability -----------------------------------------------------------

test_metrics_and_effective_state_are_written() {
  setup
  write_config true '["-e", "cpu"]'
  make_chunk "cassandra-1-100.jfr" 600
  make_chunk "cassandra-1-200.jfr" 500
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true >/dev/null

  local metrics
  metrics="$(cat "$SANDBOX/metrics.prom")"
  assert_contains "chunks pending is exported" "$metrics" "edl_jfr_chunks_pending"
  assert_contains "bytes on disk is exported" "$metrics" "edl_jfr_bytes_on_disk"
  assert_contains "last ship success is exported" "$metrics" "edl_jfr_ship_last_success_timestamp_seconds $NOW"
  assert_contains "asprof's own metrics are folded in" "$metrics" "asprof_sample_count"

  assert_file "effective state is rewritten every pass" "$PROFILE_DIR/effective-state.json"
  local state
  state="$(cat "$PROFILE_DIR/effective-state.json")"
  assert_contains "effective state records the pid" "$state" '"pid": 4242'
  assert_eq "effective state records the arguments" "-e cpu" "$(state_field '.args | join(" ")')"
  teardown
}

# A textfile nothing collects is not observability. Nothing on the node runs node_exporter or a
# textfile collector, so these counters reach VictoriaMetrics only by being pushed to the node-local
# OTel collector, whose metrics/otlp pipeline stamps cluster and host.name and remote-writes them.
test_counters_are_exported_to_the_node_otel_collector() {
  setup
  write_config true '["-e", "cpu"]'
  make_chunk "cassandra-1-100.jfr" 600
  make_chunk "cassandra-1-200.jfr" 500
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true >/dev/null

  assert_contains "counters are pushed to the node-local OTLP metrics endpoint" \
    "$(curl_log)" "http://localhost:4318/v1/metrics"

  local body
  body="$(cat "$CURL_BODY")"
  assert_eq "the payload is one OTLP resource" "1" "$(jq -r '.resourceMetrics | length' <<<"$body")"
  assert_contains "the node is identified" "$body" '"host.name"'
  assert_contains "the cluster is identified" "$body" "test-cluster"

  local names
  names="$(jq -r '.resourceMetrics[0].scopeMetrics[0].metrics[].name' <<<"$body" | tr '\n' ' ')"
  assert_contains "ship failures are exported" "$names" "edl_jfr_ship_failures_total"
  assert_contains "rejections are exported" "$names" "edl_jfr_ship_rejected_total"
  assert_contains "attach failures are exported" "$names" "edl_jfr_attach_failures_total"
  assert_contains "session starts are exported" "$names" "edl_jfr_session_starts_total"
  assert_contains "unshipped chunks lost to pruning are exported" "$names" "edl_jfr_pruned_unshipped_total"
  assert_contains "pending chunks are exported" "$names" "edl_jfr_chunks_pending"
  assert_contains "bytes on disk are exported" "$names" "edl_jfr_bytes_on_disk"
  assert_contains "last ship success is exported" "$names" "edl_jfr_ship_last_success_timestamp_seconds"

  # Counters have to arrive as cumulative monotonic sums, or rate() over them means nothing.
  assert_eq "cumulative counters are monotonic sums" "true" \
    "$(jq -r '.resourceMetrics[0].scopeMetrics[0].metrics[]
       | select(.name == "edl_jfr_ship_failures_total") | .sum.isMonotonic' <<<"$body")"
  assert_eq "point-in-time values are gauges" "true" \
    "$(jq -r '.resourceMetrics[0].scopeMetrics[0].metrics[]
       | select(.name == "edl_jfr_bytes_on_disk") | has("gauge")' <<<"$body")"
  teardown
}

# Attaching a profiler to a live database JVM is the most consequential thing this script does, and
# a node stuck in a restart loop re-attaches every 60s forever. Neither was visible anywhere: the
# lifecycle was logged only on failure and counted only on a failed attach, so a flapping node
# looked identical to a healthy one in both VictoriaLogs and VictoriaMetrics.
test_session_lifecycle_is_logged_and_counted() {
  setup
  write_config true '["-e", "cpu"]'
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=false >/dev/null

  assert_contains "a confirmed attach is logged with the pid and rotation interval" \
    "$(cat "$OUTPUT")" "event=jfr_session_started pid=4242 loop=1m action=start"

  # Which readiness answer let the attach happen, recorded once per attach. A cluster attaching on
  # `ready=uptime` is one whose socket check is broken and whose profiling starts ten minutes late.
  assert_contains "and names the readiness answer that allowed it" \
    "$(cat "$OUTPUT")" "ready=listening"
  assert_contains "attaches are counted" "$(cat "$SANDBOX/metrics.prom")" "edl_jfr_session_starts_total 1"

  # A converged pass must stay silent, or the log fills with one line a minute per node forever.
  run_pass STUB_CASSANDRA_PID=4242 >/dev/null
  assert_not_contains "a converged pass logs no transition" "$(cat "$OUTPUT")" "event=jfr_session_started"
  assert_contains "and does not move the counter" "$(cat "$SANDBOX/metrics.prom")" "edl_jfr_session_starts_total 1"

  # A mode switch: detach, then re-attach. Both transitions are what an operator needs to correlate
  # a gap in Pyroscope with, and the counter is what makes a restart loop alertable as a rate().
  write_config true '["-e", "wall"]'
  run_pass STUB_CASSANDRA_PID=4242 >/dev/null

  local output
  output="$(cat "$OUTPUT")"
  assert_contains "a confirmed detach is logged" "$output" "event=jfr_session_stopped pid=4242"
  assert_contains "the re-attach says which transition it came from" "$output" "action=restart"
  assert_contains "restarts move the counter, so flapping is a visible rate" \
    "$(cat "$SANDBOX/metrics.prom")" "edl_jfr_session_starts_total 2"
  teardown
}

# "Profiling is enabled and nothing is attached" was the one outcome a pass left no trace of: no
# line, no counter, indistinguishable from a healthy converged pass in both VictoriaLogs and
# VictoriaMetrics. A node whose database died hours ago looked exactly like one profiling normally.
test_wanting_to_profile_with_no_database_process_is_reported() {
  setup
  write_config true '["-e", "cpu"]'
  run_pass STUB_CASSANDRA_PID=0 STUB_ASPROF_RUNNING=false >/dev/null

  local output metrics
  output="$(cat "$OUTPUT")"
  metrics="$(cat "$SANDBOX/metrics.prom")"

  assert_contains "a pass that could not attach says so" "$output" \
    "event=jfr_attach_skipped reason=no_database_process"
  assert_contains "and names where the pid came from, since a wrong user looks identical" \
    "$output" "pid_source="
  assert_contains "the skipped attach is counted" "$metrics" "edl_jfr_attach_skipped_total 1"

  # Kept apart from a refused jattach, which is a PrivateTmp / java.io.tmpdir / perf_event_paranoid
  # problem on this node. Folding the two together makes both signals unactionable.
  assert_contains "an absent database is not counted as a refused attach" \
    "$metrics" "edl_jfr_attach_failures_total 0"
  teardown
}

# The gate itself, exercised directly rather than through a stub.
#
# The reconciler tests above pin the decision the gate drives; this one pins the answer it gives.
# Both matter: a probe that always says "ready" restores the bug the reconciler tests can no longer
# see, and one that always says "not ready" silently stops the node ever profiling.
test_the_readiness_probe_answers_for_the_native_transport() {
  local probe="${SCRIPT_DIR}/cassandra-ready" port listener answer

  # Picked per run, so two checkouts testing at the same time do not collide on one port.
  port=$((19042 + RANDOM % 4000))

  answer="$(env EDL_DB_READY_PORT="$port" bash "$probe" 0 2>&1)"
  assert_eq "no database process is not ready" "1" "$?"
  assert_contains "and says which answer that was" "$answer" "no-process:"

  answer="$(env EDL_DB_READY_PORT="$port" bash "$probe" $$ 2>&1)"
  assert_eq "a database with nothing listening yet is not ready" "1" "$?"
  assert_contains "and names itself as a database still starting" "$answer" "starting:"

  # The backstop: a process old enough to have had its signal handlers for minutes is ready even
  # with nothing listening, so a node whose native transport is deliberately off — or on a port this
  # probe was not told about — is still profiled instead of being excluded forever. It reads /proc,
  # which the nodes and CI have and a macOS developer machine does not; there the correct answer is
  # to refuse rather than to guess, which is the safe direction.
  answer="$(env EDL_DB_READY_PORT="$port" EDL_DB_READY_AFTER_SECONDS=0 bash "$probe" $$ 2>&1)"
  local backstop=$?
  if [[ -r "/proc/$$/stat" ]]; then
    assert_eq "a long-lived process with nothing listening is ready" "0" "$backstop"
    assert_contains "and says the backstop is what answered, not the socket" "$answer" "uptime:"
  else
    assert_eq "with no /proc the backstop refuses rather than guessing" "1" "$backstop"
  fi
  # The configuration these nodes actually run, and the one the first version of this probe could
  # never see: Cassandra binds the native transport to `rpc_address`, the node's private IP, so
  # 127.0.0.1 refuses while the socket is plainly listening. A listener on a real interface address
  # is the only fixture that tells the two implementations apart — bind to 0.0.0.0 and a loopback
  # connect succeeds, which would pass against the bug.
  local address
  address="$(first_non_loopback_address)"
  if [[ -z "$address" ]]; then
    fail "a native transport on a non-loopback address is ready" \
      "this machine has no non-loopback IPv4 address to bind a listener to"
    return
  fi

  # -k keeps the listener up after a connection completes, so the check below does not consume it.
  nc -k -l "$address" "$port" >/dev/null 2>&1 &
  listener=$!

  # Waited for by connecting to the address it is bound to, which shares no code with the socket-table
  # reading under test: a fixture verified by the implementation it exists to check verifies nothing.
  local waited=0
  until timeout 1 bash -c "exec 3<>/dev/tcp/${address}/${port}" 2>/dev/null; do
    waited=$((waited + 1))
    if ((waited > 20)); then
      fail "a native transport on a non-loopback address is ready" \
        "could not open a listener on ${address}:${port}; is nc installed?"
      kill "$listener" 2>/dev/null
      wait "$listener" 2>/dev/null
      return
    fi
    sleep 0.1
  done

  # The premise, asserted rather than assumed: if a loopback connect could reach this listener, the
  # test below would pass against a probe that connects to 127.0.0.1 and prove nothing.
  if timeout 1 bash -c "exec 3<>/dev/tcp/127.0.0.1/${port}" 2>/dev/null; then
    fail "the listener fixture is not reachable on loopback" \
      "127.0.0.1:${port} accepted a connection, so this fixture cannot detect the bug"
  fi

  answer="$(env EDL_DB_READY_PORT="$port" bash "$probe" $$ 2>&1)"
  assert_eq "a native transport on a non-loopback address is ready" "0" "$?"
  assert_contains "and says the socket answered, not the backstop" "$answer" "listening:"

  kill "$listener" 2>/dev/null
  wait "$listener" 2>/dev/null
}

# The bug this gate exists for killed a database on a live cluster.
#
# jattach signals SIGQUIT to make the JVM start its attach listener, and a process that has not
# installed a handler for SIGQUIT yet takes the signal's default disposition: terminate and dump
# core. A pass that attached to a node 17 seconds into its startup killed it, then reported
# "Process 5419 not found" — the process it had just signalled.
#
# This tier stubs asprof, so no signal is ever sent here and the kill itself is out of reach. What
# IS reachable, and what was actually wrong, is the decision: the pass must not reach for asprof at
# all. So these assertions are about the absence of every asprof call, not about the target's fate.
test_a_database_that_is_not_ready_is_never_signalled() {
  setup
  write_config true '["-e", "cpu"]'
  make_chunk "cassandra-1-050.jfr" 7300
  make_chunk "cassandra-1-100.jfr" 600
  local exit_code
  exit_code="$(run_pass STUB_CASSANDRA_PID=4242 STUB_DB_READY=false STUB_ASPROF_RUNNING=false)"

  local output metrics
  output="$(cat "$OUTPUT")"
  metrics="$(cat "$SANDBOX/metrics.prom")"

  # Every asprof subcommand is a jattach round-trip, `status` and `metrics` included, so the
  # assertion is that the tool was not invoked at all — not merely that it was not asked to start.
  assert_not_contains "a database that is not ready is never signalled" "$(stub_log)" "asprof"
  assert_contains "and the pass says why it did not attach" "$output" \
    "event=jfr_attach_deferred reason=database_not_ready"
  assert_contains "naming what it would have done, so a deferred stop is not read as a slow start" \
    "$output" "wanted=start"
  assert_contains "a deferral is a warning, not an error: every node restart passes through it" \
    "$output" "<4>level=warn event=jfr_attach_deferred"
  assert_contains "carrying the probe's own answer, so a broken probe is not read as a slow database" \
    "$output" "says=starting"
  assert_eq "and the pass still exits 0" "0" "$exit_code"

  assert_contains "the deferral is counted" "$metrics" "edl_jfr_attach_deferred_total 1"
  assert_contains "and is exported as a gauge, so the desired-but-not-attached alert can exclude it" \
    "$metrics" "edl_jfr_attach_deferred 1"

  # The three cases are diagnosed in different places: a refused jattach is a node configuration
  # problem, no process means the database is stopped, and this one means it is still starting.
  assert_contains "a database that is starting is not a refused attach" \
    "$metrics" "edl_jfr_attach_failures_total 0"
  assert_contains "nor an absent database" "$metrics" "edl_jfr_attach_skipped_total 0"

  # A deferral suspends the attach decision and nothing else. The profiler on a node that has been
  # profiling keeps rotating chunks across a restart, so a pass that stopped here would leave them
  # unshipped and the directory unpruned on the database's own volume.
  assert_file "a deferred pass still ships completed chunks" "$PROFILE_DIR/cassandra-1-100-shipped.jfr"
  assert_no_file "and still enforces retention" "$PROFILE_DIR/cassandra-1-050.jfr"
  assert_eq "and records the wait where status can read it" "true" "$(state_field '.attachDeferred')"

  # The gate has to open again, or it is just a slower way of never profiling.
  run_pass STUB_CASSANDRA_PID=4242 STUB_DB_READY=true STUB_ASPROF_RUNNING=false >/dev/null
  assert_contains "a ready database is attached to on the next pass" "$(stub_log)" "asprof start"
  assert_eq "and the wait is no longer reported" "false" "$(state_field '.attachDeferred')"
  assert_contains "while the deferral stays counted, so the wait is visible after the fact" \
    "$(cat "$SANDBOX/metrics.prom")" "edl_jfr_attach_deferred_total 1"

  # The counters reach a backend only by the OTLP push; the textfile is a node-local convenience.
  local names
  names="$(jq -r '.resourceMetrics[0].scopeMetrics[0].metrics[].name' <"$CURL_BODY" | tr '\n' ' ')"
  assert_contains "deferrals are pushed to the collector" "$names" "edl_jfr_attach_deferred_total"
  teardown
}

# The gate depends on a second node tool, so a node image carrying only one of the two is now a
# reachable state — and it defers on every pass forever. Reported as "the database is not ready" it
# would send whoever reads it to look at a database that is perfectly healthy.
test_a_missing_readiness_probe_is_not_reported_as_a_starting_database() {
  setup
  write_config true '["-e", "cpu"]'
  run_pass STUB_CASSANDRA_PID=4242 EDL_CASSANDRA_READY="$SANDBOX/absent-probe" >/dev/null

  local output
  output="$(cat "$OUTPUT")"
  assert_not_contains "a node with no probe is still never signalled" "$(stub_log)" "asprof"
  assert_contains "and the missing probe is named as the reason" "$output" \
    "event=jfr_attach_deferred reason=readiness_probe_missing"
  assert_contains "with a hint pointing at the node image, not at the database" "$output" \
    "hint=node_image_is_stale"
  teardown
}

# A session already attached to this very process is not detached by the readiness probe going
# quiet. Reporting it as gone would rewrite the record with no arguments, and the next readable pass
# would read that erasure as a spec change and tear down a healthy session — the flapping
# edl_jfr_session_starts_total exists to expose, manufactured by the reporting path itself.
test_a_deferred_pass_does_not_erase_a_running_session() {
  setup
  write_config true '["-e", "cpu"]'
  write_effective_state 4242 '["-e", "cpu"]'
  run_pass STUB_CASSANDRA_PID=4242 STUB_DB_READY=false STUB_ASPROF_RUNNING=true >/dev/null

  assert_not_contains "a converged session is not signalled either" "$(stub_log)" "asprof"
  assert_eq "the running session is carried forward, not erased" "true" "$(state_field '.running')"
  assert_eq "with the arguments it is actually running" "-e cpu" "$(state_field '.args | join(" ")')"
  assert_eq "and nothing is deferred, because nothing needed doing" "false" \
    "$(state_field '.attachDeferred')"

  # Now something does need doing, and still cannot be done.
  write_config false '["-e", "cpu"]'
  run_pass STUB_CASSANDRA_PID=4242 STUB_DB_READY=false STUB_ASPROF_RUNNING=true >/dev/null

  assert_not_contains "a stop that cannot be signalled is not attempted" "$(stub_log)" "asprof stop"
  assert_contains "the deferred stop names itself as a stop" "$(cat "$OUTPUT")" "wanted=stop"
  assert_eq "and the session is still reported as running, since no detach happened" \
    "true" "$(state_field '.running')"
  teardown
}

# Three states have to be tellable apart from a metrics backend alone, with no CLI and no SSH:
# profiling deliberately off, profiling on and attached, profiling on and dead. Without these gauges
# all three show the same flat counters.
test_desired_and_attached_state_are_exported_as_metrics() {
  setup
  write_config true '["-e", "cpu"]'
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=false >/dev/null
  local attached
  attached="$(cat "$SANDBOX/metrics.prom")"
  assert_contains "an attached session reports desired" "$attached" "edl_jfr_profiling_desired 1"
  assert_contains "an attached session reports attached" "$attached" "edl_jfr_session_attached 1"

  # Same desired state, no process: the case that used to be invisible.
  run_pass STUB_CASSANDRA_PID=0 >/dev/null
  local dead
  dead="$(cat "$SANDBOX/metrics.prom")"
  assert_contains "a node that wants profiling still reports desired" "$dead" "edl_jfr_profiling_desired 1"
  assert_contains "but reports nothing attached" "$dead" "edl_jfr_session_attached 0"

  # And a deliberate stop, which must not look like the failure above.
  write_config false '["-e", "cpu"]'
  run_pass STUB_CASSANDRA_PID=4242 >/dev/null
  local off
  off="$(cat "$SANDBOX/metrics.prom")"
  assert_contains "a deliberate stop reports profiling not desired" "$off" "edl_jfr_profiling_desired 0"
  assert_contains "and nothing attached" "$off" "edl_jfr_session_attached 0"

  # The gauges are only useful if they reach a backend, and the textfile is not a collection path.
  local names
  names="$(jq -r '.resourceMetrics[0].scopeMetrics[0].metrics[].name' <"$CURL_BODY" | tr '\n' ' ')"
  assert_contains "desired state is pushed to the collector" "$names" "edl_jfr_profiling_desired"
  assert_contains "attached state is pushed to the collector" "$names" "edl_jfr_session_attached"
  assert_contains "skipped attaches are pushed to the collector" "$names" "edl_jfr_attach_skipped_total"
  teardown
}

# Severity in VictoriaLogs comes from journald's PRIORITY and nothing else — Fluent Bit's mapper
# reads PRIORITY and ships the line itself as an opaque body. Without a syslog level prefix systemd
# stamps every line PRIORITY=6, so a failed attach arrives as severity=INFO and no Grafana filter or
# alert on severity can ever see it.
test_log_lines_carry_a_syslog_priority_for_journald() {
  setup
  write_config true '["-e", "cpu"]'
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=false STUB_ASPROF_START_EXIT=1 >/dev/null
  assert_contains "an error line is priority 3, so journald records it as err" \
    "$(cat "$OUTPUT")" "<3>level=error event=jfr_attach_failed"
  teardown

  setup
  write_config true '["-e", "cpu"]'
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true STUB_HTTP_STATUS=503 >/dev/null
  assert_contains "a warning line is priority 4" \
    "$(cat "$OUTPUT")" "<4>level=warn event=jfr_metrics_export_failed"
  teardown

  setup
  run_pass STUB_CASSANDRA_PID=4242 >/dev/null
  assert_contains "an informational line is priority 6" \
    "$(cat "$OUTPUT")" "<6>level=info event=profiling_idle"
  teardown
}

# The OTLP document is the only path these counters have to VictoriaMetrics, and both of its label
# values are strings the operator chose: the node's hostname and the cluster name. Concatenating them
# into JSON would let a quote in either one produce a body the collector rejects, which surfaces only
# as jfr_metrics_export_failed once a minute forever.
#
# The document is built with yq rather than jq for the reason the reconciler's own header gives: yq
# already reads the desired-state document, so the script needs one JSON tool rather than two. Both
# are on the base image and jq's --arg carries the same guarantee.
test_hostile_labels_cannot_corrupt_the_otlp_payload() {
  setup
  write_config true '["-e", "cpu"]'
  local hostile_cluster='he said "no" \ {"resourceMetrics": []}'
  # env applies assignments left to right, so this one wins over run_pass's own EDL_HOSTNAME.
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true EDL_HOSTNAME='db0" ,"x":"' >/dev/null

  local body
  body="$(cat "$CURL_BODY")"
  assert_eq "a hostile hostname still produces one OTLP resource" "1" \
    "$(jq -r '.resourceMetrics | length' <<<"$body" 2>/dev/null)"
  assert_eq "the hostname arrives as one opaque string value, not as structure" 'db0" ,"x":"' \
    "$(jq -r '.resourceMetrics[0].resource.attributes[]
       | select(.key == "host.name") | .value.stringValue' <<<"$body")"

  # The cluster name comes from the desired-state document rather than the environment, so it is
  # checked on its own pass.
  teardown

  setup
  cat >"$CONFIG" <<EOF
{
  "enabled": true,
  "asprofArgs": ["-e", "cpu"],
  "loopInterval": "1m",
  "retentionMinutes": 60,
  "maxBytes": 2147483648,
  "pyroscopeUrl": "http://10.0.1.5:4040",
  "clusterName": $(jq -Rn --arg c "$hostile_cluster" '$c'),
  "updatedAt": "2026-08-24T10:15:30Z"
}
EOF
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true >/dev/null

  local cluster_body
  cluster_body="$(cat "$CURL_BODY")"
  assert_eq "a hostile cluster name still produces parseable JSON" "1" \
    "$(jq -r '.resourceMetrics | length' <<<"$cluster_body" 2>/dev/null)"
  assert_eq "the cluster name arrives verbatim" "$hostile_cluster" \
    "$(jq -r '.resourceMetrics[0].resource.attributes[]
       | select(.key == "cluster") | .value.stringValue' <<<"$cluster_body")"
  assert_not_contains "a hostile label never turns into an export failure" \
    "$(cat "$OUTPUT")" "event=jfr_metrics_export_failed"
  teardown
}

test_a_failed_metrics_export_does_not_fail_the_pass() {
  setup
  write_config true '["-e", "cpu"]'
  local exit_code
  exit_code="$(run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true STUB_HTTP_STATUS=503)"

  assert_eq "an unreachable collector still exits 0" "0" "$exit_code"
  assert_contains "an unreachable collector is reported" "$(cat "$OUTPUT")" "event=jfr_metrics_export_failed"
  teardown
}

# The cross-tier field contract, pinned from this side.
#
# Kotlin's ProfilingEffectiveState parses this document with ignoreUnknownKeys = true and a default
# on every field, so renaming any key here is silent at runtime: the reader simply gets the default.
# Renaming `updatedAt` alone returns Unknown freshness for every node, which disables the STALE
# banner and the StateStale event entirely. Both tiers therefore assert the SAME literal key list;
# the mirror lives in ProfilingEffectiveStateTest.
test_the_effective_state_key_set_is_the_contract() {
  setup
  write_config true '["-e", "cpu"]'
  run_pass STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true >/dev/null

  assert_eq "the document carries exactly the keys the Kotlin reader models" \
    "running,desiredEnabled,pid,args,loopInterval,retentionMinutes,maxBytes,pyroscopeUrl,clusterName,startedAt,chunksPending,chunksShipped,chunksRejected,shipFailures,prunedForAge,prunedForSize,prunedUnshipped,bytesOnDisk,lastError,attachFailures,lastAttachError,attachDeferred,configError,updatedAt" \
    "$(jq -r 'keys_unsorted | join(",")' <"$PROFILE_DIR/effective-state.json")"
  teardown
}

# systemd kills a pass at TimeoutStartSec, and an untrapped SIGTERM kills the shell outright: the
# counters were never written, so edl_jfr_ship_last_success_timestamp_seconds kept reporting a stale
# value while shipping was in fact partly working, and effective-state.json was never rewritten, so
# `status` reported the node as STALE and blamed the reconcile timer for a slow Pyroscope.
test_a_killed_pass_still_persists_what_it_learned() {
  setup
  write_config true '["-e", "cpu"]'
  make_chunk "cassandra-1-100.jfr" 600
  make_chunk "cassandra-1-200.jfr" 500
  make_chunk "cassandra-1-300.jfr" 5
  rm -f "$PROFILE_DIR/effective-state.json"

  # Each upload takes a second, so the pass is reliably mid-shipping when the signal lands.
  run_pass_bg STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=true STUB_CURL_SLEEP=1
  local waited=0
  while [[ ! -s "$CURL_LOG" && $waited -lt 100 ]]; do
    sleep 0.1
    waited=$((waited + 1))
  done
  kill -TERM "$PASS_PID" 2>/dev/null
  wait "$PASS_PID" 2>/dev/null

  assert_file "a killed pass still leaves state behind" "$PROFILE_DIR/effective-state.json"
  assert_eq "state written on the way out is current, not stale" "$NOW" "$(state_field '.updatedAt')"
  assert_file "and still leaves its counters" "$SANDBOX/metrics.prom"
  assert_contains "so the desired/attached gauges keep reporting" \
    "$(cat "$SANDBOX/metrics.prom")" "edl_jfr_session_attached 1"
  # A killed pass is its own condition, distinct from a wedged one: it says so rather than simply
  # going quiet, which is what made "Pyroscope is slow" read as "the reconciler is dead".
  assert_contains "and the kill is reported rather than silent" \
    "$(cat "$OUTPUT")" "event=jfr_pass_terminated"
  teardown
}

# The sibling case, and the one the trap was least able to cover: killed during the attach rather
# than during the upload.
#
# The trap used to be installed below the liveness probe and the whole attach/detach block — the only
# calls in a pass that can actually consume TimeoutStartSec, since `asprof status`, `stop` and
# `start` are jattach round-trips to a JVM that may be in a long safepoint. Shipping, which the trap
# did cover, is already bounded by curl's --max-time. So the exact scenario the trap exists for was
# unprotected in the phase most likely to produce it.
#
# The cost is more than the lost counters. If start_session has just attached and the pass is then
# killed with no record written, the next pass finds no record for the PID, reads SESSION_ACTION as
# restart, and detaches and reattaches a healthy session — the flapping edl_jfr_session_starts_total
# exists to expose.
test_a_pass_killed_while_attaching_still_persists_what_it_learned() {
  setup
  write_config true '["-e", "cpu"]'
  rm -f "$PROFILE_DIR/effective-state.json"

  run_pass_bg STUB_CASSANDRA_PID=4242 STUB_ASPROF_RUNNING=false STUB_ASPROF_START_SLEEP=3
  # The stub logs its argv before sleeping, so this lands the signal inside the attach itself.
  local waited=0
  while ! grep -q 'asprof start' "$STUB_LOG" 2>/dev/null && [[ $waited -lt 100 ]]; do
    sleep 0.1
    waited=$((waited + 1))
  done
  kill -TERM "$PASS_PID" 2>/dev/null
  wait "$PASS_PID" 2>/dev/null

  assert_file "a pass killed mid-attach still leaves state behind" "$PROFILE_DIR/effective-state.json"
  assert_eq "recorded against this pass's own clock" "$NOW" "$(state_field '.updatedAt')"
  assert_eq "and naming the pid, so the next pass does not read it as an unknown session" "4242" \
    "$(state_field '.pid')"
  assert_file "its counters are written too" "$SANDBOX/metrics.prom"
  assert_contains "and the kill is reported rather than silent" \
    "$(cat "$OUTPUT")" "event=jfr_pass_terminated"
  teardown
}

# --- JVM options guard -------------------------------------------------------

# This change's headline requirement is that Cassandra starts with no profiling agent in its JVM
# options at all. Moving capture out of the startup path is what makes the event set a runtime
# property, and it is also what removes a whole failure class: a bad -agentpath string aborts JVM
# startup, while a failed attach leaves the database running.
#
# Nothing else asserts it. cassandra.in.sh still injects the AxonOps and MAAC agents, so it is a file
# that gets edited, and a reintroduced -javaagent:pyroscope.jar would be close to invisible — the
# reconciler would still attach, chunks would still ship, and the only symptom would be two profilers
# competing inside one JVM for a session async-profiler allows only one of.
#
# Statically checkable, so it is checked here: no cluster, no JVM, no AMI.
CASSANDRA_IN_SH="${SCRIPT_DIR}/../cassandra.in.sh"

# Executable lines only. The file carries a comment block explaining why profiling was removed, and
# that comment names the very flags this guard forbids. There are no trailing comments on code lines
# in this file, so dropping whole-line comments is exact rather than approximate.
cassandra_in_sh_code() {
  grep -v '^[[:space:]]*#' "$CASSANDRA_IN_SH"
}

test_cassandra_in_sh_injects_no_profiling_agent() {
  local code
  code="$(cassandra_in_sh_code)"

  # Vacuity check first: if the extraction ever stops yielding code, every assertion below passes
  # while checking nothing. The MAAC agent is the file's own proof that agents are still injected
  # here, which is precisely why this guard has to exist.
  assert_contains "the file still injects the agents it is supposed to" "$code" "-javaagent:\${MAAC_AGENT_JAR}"

  assert_not_contains "no -agentpath: the profiler is attached at runtime, never at JVM startup" \
    "$code" "-agentpath"
  assert_not_contains "no Pyroscope agent jar or -Dpyroscope.* property" "$code" "pyroscope"
  assert_not_contains "no PYROSCOPE_* variable gating an agent" "$code" "PYROSCOPE"
  assert_not_contains "no async-profiler library loaded at startup" "$code" "libasyncProfiler"
}

# --- runner ------------------------------------------------------------------

test_starts_when_not_running
test_matching_session_is_left_alone
test_empty_args_session_is_left_alone
test_differing_spec_stops_then_starts
test_interval_only_change_also_switches
test_disabled_and_running_stops
test_pid_mismatched_record_is_discarded
test_unknown_running_session_converges
test_corrupt_config_leaves_session_untouched
test_corrupt_config_still_ships_prunes_and_reports
test_corrupt_config_prunes_under_last_known_good_bounds
test_a_missing_ingest_url_is_refused_wholesale
test_a_negative_retention_window_is_rejected_wholesale
test_a_rejected_bound_is_never_used_on_a_node_with_no_prior_state
test_a_malformed_loop_interval_is_refused_wholesale
test_a_time_of_day_rotation_is_refused
test_missing_config_is_idle
test_cassandra_down_still_ships_and_prunes
test_final_chunk_ships_after_the_session_stops
test_failed_attach_is_recorded_without_failing_the_pass
test_a_hostile_tool_message_cannot_corrupt_the_state_document
test_failed_detach_is_never_recorded_as_a_clean_stop
test_a_failed_switch_retries_on_the_next_pass
test_a_failed_detach_does_not_start_a_second_session
test_argv_fidelity
test_newest_chunk_is_never_shipped
test_chunk_inside_the_grace_window_is_not_shipped
test_successful_upload_is_marked_shipped
test_upload_window_tracks_the_configured_loop_interval
test_shipped_chunks_stay_retrievable_and_are_never_reshipped
test_a_freshly_rotated_chunk_is_not_yet_a_backlog
test_chunks_the_shipper_failed_to_send_are_counted_as_pending
test_server_error_leaves_the_chunk_for_retry
test_network_failure_reports_what_curl_said
test_client_error_is_rejected_and_never_retried
test_a_non_ascii_cluster_name_encodes_as_utf8_bytes
test_a_chunk_exactly_on_the_grace_boundary_ships
test_a_chunk_one_second_inside_the_grace_boundary_does_not_ship
test_a_backlog_is_shipped_a_bounded_number_of_chunks_per_pass
test_age_pruning
test_unshipped_chunks_are_age_pruned
test_age_pruning_is_logged_and_counted
test_pruning_a_rejected_chunk_is_not_counted_as_lost_data
test_byte_ceiling_prunes_oldest_first
test_a_chunk_exactly_on_the_age_cutoff_is_kept
test_a_directory_exactly_at_the_byte_ceiling_is_left_alone
test_a_wedged_jvm_cannot_block_the_persistence_path
test_lost_chunks_are_recorded_where_the_operator_looks
test_metrics_and_effective_state_are_written
test_counters_are_exported_to_the_node_otel_collector
test_hostile_labels_cannot_corrupt_the_otlp_payload
test_session_lifecycle_is_logged_and_counted
test_wanting_to_profile_with_no_database_process_is_reported
test_the_readiness_probe_answers_for_the_native_transport
test_a_database_that_is_not_ready_is_never_signalled
test_a_deferred_pass_does_not_erase_a_running_session
test_a_missing_readiness_probe_is_not_reported_as_a_starting_database
test_desired_and_attached_state_are_exported_as_metrics
test_log_lines_carry_a_syslog_priority_for_journald
test_a_failed_metrics_export_does_not_fail_the_pass
test_the_effective_state_key_set_is_the_contract
test_a_killed_pass_still_persists_what_it_learned
test_a_pass_killed_while_attaching_still_persists_what_it_learned
test_cassandra_in_sh_injects_no_profiling_agent

echo
echo "${tests_run} tests, ${tests_failed} failed"
[[ $tests_failed -eq 0 ]] || exit 1
