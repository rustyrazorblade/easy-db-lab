#!/bin/sh
# Runs in the Pyroscope server's network namespace, as a Java agent's push reaches the server: no
# Docker port proxy in between. The server waits at its start gate until RELEASE exists. This
# script first checks that the port refuses a connection, so the open it then waits for is the
# server's start and not a moment it missed, and exits 2 if it does not. It then creates RELEASE,
# waits until the port accepts a connection (polled every 5ms, for up to PORT_WAIT_SECONDS),
# prints "OPEN", pushes a one-line profile every PERIOD_MS for AFTER_MS, and prints
# "REFUSED <ms after open> <error>" for every push the server answered with an error. A push that
# cannot connect is not counted: the agent retries those.
# Inputs (environment): URL, TENANT, RELEASE, PORT_WAIT_SECONDS, AFTER_MS, PERIOD_MS,
# PUSH_TIMEOUT_SECONDS.
printf 'main;work;compute 1\n' > /tmp/profile
now() { cut -d' ' -f1 /proc/uptime; }
ms_since() { awk -v a="$1" -v b="$(now)" 'BEGIN { printf "%d", (b - a) * 1000 }'; }
refuses() { ! wget -q -T 2 -O /dev/null "$URL" 2>/tmp/err && grep -q 'refused' /tmp/err; }

if ! refuses; then
  echo "PORT-OPEN-BEFORE-RELEASE"
  exit 2
fi
touch "$RELEASE"
start=$(now)
while refuses; do
  if [ "$(ms_since "$start")" -gt $((PORT_WAIT_SECONDS * 1000)) ]; then
    echo "NEVER-OPENED"
    exit 1
  fi
  usleep 5000
done
open=$(now)
echo "OPEN"
while [ "$(ms_since "$open")" -lt "$AFTER_MS" ]; do
  at=$(ms_since "$open")
  if ! wget -q -T "$PUSH_TIMEOUT_SECONDS" -O /dev/null --header "X-Scope-OrgID: $TENANT" \
      --post-file /tmp/profile "$URL" 2>/tmp/err; then
    grep -q 'refused' /tmp/err || echo "REFUSED $at $(head -c 200 /tmp/err)"
  fi
  usleep $((PERIOD_MS * 1000))
done
echo "DONE"
