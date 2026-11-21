#!/bin/sh
# Pushes the JFR profile at $BODY to $URL every $PERIOD_MS milliseconds and prints one line per
# answer, `STATUS <code> <refusal|other>`, where `refusal` means the body says "service is
# unavailable" (Pyroscope's shutdown refusal). curl prints 000 for a push that could not connect or
# timed out. The pusher stops after $GONE pushes in a row could not connect (the server has exited
# and its Service name no longer resolves), or after $MAX_SECONDS, and then prints DONE. Runs in
# the curlimages/curl image, whose sh, awk, date, grep and sleep are busybox.
set -u
period=$(awk "BEGIN { print $PERIOD_MS / 1000 }")
deadline=$(($(date +%s) + MAX_SECONDS))
failed=0
while [ "$failed" -lt "$GONE" ] && [ "$(date +%s)" -lt "$deadline" ]; do
    now=$(date +%s)
    code=$(curl -s -o /tmp/answer -w '%{http_code}' -m "$PUSH_TIMEOUT_SECONDS" \
        -H "X-Scope-OrgID: $TENANT" -H 'Content-Type: application/octet-stream' \
        --data-binary "@$BODY" "$URL&from=$((now - 1))&until=$now")
    if grep -q 'service is unavailable' /tmp/answer 2>/dev/null; then kind=refusal; else kind=other; fi
    rm -f /tmp/answer
    echo "STATUS $code $kind"
    if [ "$code" = 000 ]; then failed=$((failed + 1)); else failed=0; fi
    sleep "$period"
done
echo DONE
