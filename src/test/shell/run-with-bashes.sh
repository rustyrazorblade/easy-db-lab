#!/bin/sh
#
# Runs the shell test $1 under `bash` from PATH and, when /bin/bash is bash 3.x (macOS), under
# /bin/bash too. Kit scripts run on the operator's machine, where `#!/usr/bin/env bash` can be the
# macOS bash 3.2, so a test that passes only under a newer bash proves nothing there. A test runs
# the scripts it checks with "$BASH", the bash running it.
#
# Usage: sh src/test/shell/run-with-bashes.sh src/test/shell/<name>.test.sh

set -eu

bash "$1"

if [ -x /bin/bash ] && /bin/bash -c '[ "${BASH_VERSINFO[0]}" -eq 3 ]' &&
  [ "$(bash -c 'echo "$BASH"')" != /bin/bash ]; then
  echo "Again under /bin/bash $(/bin/bash -c 'echo "$BASH_VERSION"')"
  /bin/bash "$1"
fi
