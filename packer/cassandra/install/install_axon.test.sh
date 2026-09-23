#!/usr/bin/env bash
#
# Tests for the /etc/sudoers.d/axonops rules that install_axon.sh bakes into the image.
#
# The bug these exist for: the image shipped `axonops ALL=NOPASSWD: /sbin/service cassandra *`.
# Ubuntu 26.04's sudo (sudo-rs) rejects wildcards in command arguments, so every sudo call on
# every node printed `/etc/sudoers.d/axonops:1:50: wildcards are not allowed in command
# arguments`. The rules must pass the image's own `visudo -cf`, and the install step must refuse
# to install a file that does not.
#
# visudo has to be the one the AMI runs. macOS and older Ubuntu ship classic sudo, which accepts
# the wildcard, so this runs in the packer test image (Ubuntu 26.04), as the ubuntu user.
#
# install_axon.sh returns right after its function definitions when sourced, so this installs
# nothing from the network.
#
# Run via gradle: ./gradlew testAxonSudoers

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# shellcheck source=/dev/null
source "${SCRIPT_DIR}/install_axon.sh"

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

WORK="$(mktemp -d)"
trap 'sudo rm -rf "$WORK"' EXIT

# --- the baked rules ---------------------------------------------------------
dest="${WORK}/axonops"
if axonops_sudoers_rules | install_sudoers_file "$dest"; then
  pass "the axonops rules install"
else
  fail "the axonops rules were rejected"
fi

if sudo visudo -cf "$dest" >/dev/null 2>&1; then
  pass "the installed file passes visudo -cf"
else
  fail "the installed file fails visudo -cf: $(sudo visudo -cf "$dest" 2>&1)"
fi

# The file is 0440 root:root and this runs as ubuntu, so it must be read with sudo. grep exits 2
# on an unreadable file, which a plain if/else would count as "no wildcard".
wildcards="$(sudo grep -n '\*' "$dest")"
case $? in
  0) fail "the installed file contains a wildcard: ${wildcards}" ;;
  1) pass "the installed file has no wildcard arguments" ;;
  *) fail "could not read ${dest} to check it for wildcards" ;;
esac

perms="$(stat -c '%a %U:%G' "$dest")"
if [[ "$perms" == "440 root:root" ]]; then
  pass "the installed file is 0440 root:root"
else
  fail "the installed file should be 0440 root:root, got ${perms}"
fi

# --- an invalid rule must fail the build, not land in sudoers.d --------------
# The exact line the image used to ship. sudo-rs tolerates a lone trailing `*`; it is the
# `systemctl * cassandra*` rule (column 50) that it rejects.
bad="${WORK}/bad"
shipped='axonops ALL=NOPASSWD: /sbin/service cassandra *, /usr/bin/systemctl * cassandra*'
if echo "$shipped" | install_sudoers_file "$bad" 2>/dev/null; then
  fail "a wildcard rule was installed; the bake would have shipped it"
else
  pass "a wildcard rule is refused"
fi

if [[ -e "$bad" ]]; then
  fail "a refused rule still left ${bad} behind"
else
  pass "a refused rule leaves nothing behind"
fi

echo
echo "${tests_run} assertions, ${tests_failed} failed"
[[ "$tests_failed" -eq 0 ]]
