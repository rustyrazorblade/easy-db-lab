#!/bin/sh
# Starts a push of the JFR profile at $BODY to $URL every $PERIOD_MS milliseconds, each in the
# background, so a new push arrives on schedule whatever the earlier ones are doing, and prints one
# line per answer, `STATUS <code> <refusal|other>`, where `refusal` means the body says "service is
# unavailable" (Pyroscope's shutdown refusal). curl prints 000 for a push that could not connect or
# timed out.
#
# Pushes are not sent one after another: a push the server is handling when SIGTERM arrives is held
# until the server exits, and a pusher waiting on it would send nothing for the whole refusal window.
# Started on a clock instead, pushes keep arriving through the window, and every one that arrives
# after the server stopped its distributor is answered with the refusal.
#
# The first answer that stored the profile (200) also prints READY, which the test waits for before
# it sends SIGTERM, so the pusher is known to be reaching the server. The test sends this script
# TERM once the server has exited; the script then stops starting pushes, waits for those still
# running and prints DONE. It also stops by itself after $MAX_SECONDS. Runs in the curlimages/curl
# image, whose sh, awk, date, grep and sleep are busybox.
set -u
period=$(awk "BEGIN { print $PERIOD_MS / 1000 }")
deadline=$(($(date +%s) + MAX_SECONDS))
stop=0
trap 'stop=1' TERM
mkdir -p /tmp/answers
push() {
    now=$(date +%s)
    code=$(curl -s -o "/tmp/answers/$1" -w '%{http_code}' -m "$PUSH_TIMEOUT_SECONDS" \
        -H "X-Scope-OrgID: $TENANT" -H 'Content-Type: application/octet-stream' \
        --data-binary "@$BODY" "$URL&from=$((now - 1))&until=$now")
    if grep -q 'service is unavailable' "/tmp/answers/$1" 2>/dev/null; then kind=refusal; else kind=other; fi
    rm -f "/tmp/answers/$1"
    if [ "$code" = 200 ] && mkdir /tmp/ready 2>/dev/null; then
        echo "STATUS $code $kind
READY"
    else
        echo "STATUS $code $kind"
    fi
}
n=0
while [ "$stop" = 0 ] && [ "$(date +%s)" -lt "$deadline" ]; do
    n=$((n + 1))
    push "$n" &
    sleep "$period"
done
wait
echo DONE
