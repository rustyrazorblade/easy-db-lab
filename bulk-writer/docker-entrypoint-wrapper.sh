#!/usr/bin/env bash
set -e

# first arg is `-f` or `--some-option`
# or there are no args
if [ "$#" -eq 0 ] || [ "${1#-}" != "$1" ]; then
	set -- cassandra -f -R "$@"
fi

# REMOVED: chown block that caused permission issues with bind mounts
# REMOVED: _sed-in-place function and all calls - config is pre-baked

exec "$@"
