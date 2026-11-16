#!/bin/bash
# Poll the Amazon Time Sync Service every 8 seconds instead of every 16.
#
# WHY: Ubuntu's EC2 image ships /etc/chrony/conf.d/00-cpc.conf with `minpoll 4 maxpoll 4` (16 s) on
# the Amazon Time Sync source. Between polls the kernel's clock error bound (`maxerror` from
# adjtimex) grows at 500 ppm, so it reaches about 8 ms just before each update. Databases that use
# that bound for correctness (hybrid logical clocks with bounded uncertainty, commit-wait designs)
# can then refuse work or report the clock as unhealthy. An 8 s poll halves that growth. chrony
# poll values are log2 seconds, so 8 s is `minpoll 3 maxpoll 3`.
#
# HOW: every non-comment server/pool/peer line for the Time Sync source (169.254.169.123, or its
# IPv6 address fd00:ec2::123) in chrony.conf, conf.d/*.conf and sources.d/*.sources loses its own
# minpoll/maxpoll and gets ours. The file content is not hardcoded, so a change to the image's other
# options (prefer, iburst) is kept. The build fails if no such line exists, because then the image
# would keep its default poll without anyone noticing.
#
# CHRONY_DIR exists for the unit test (configure_chrony_poll.test.sh); the bake uses /etc/chrony.
set -euo pipefail

echo "=== Running: configure_chrony_poll.sh ==="

CHRONY_DIR="${CHRONY_DIR:-/etc/chrony}"
POLL=3
TIME_SYNC_SOURCE='^[[:space:]]*(server|pool|peer)[[:space:]]+(169\.254\.169\.123|fd00:ec2::123)([[:space:]]|$)'

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

files=()
for f in "${CHRONY_DIR}/chrony.conf" "${CHRONY_DIR}"/conf.d/*.conf "${CHRONY_DIR}"/sources.d/*.sources; do
    [[ -f "$f" ]] && files+=("$f")
done

if [[ "${#files[@]}" -eq 0 ]]; then
    echo "ERROR: no chrony config (chrony.conf, conf.d/*.conf, sources.d/*.sources) in ${CHRONY_DIR}"
    exit 1
fi

# Sets MATCHES to the Time Sync lines of $1 and succeeds when it has any. The files can be
# root-only, so they are read through sudo, and a read error fails the bake: a skipped file would
# leave its source at the image's poll.
read_time_sync_lines() {
    local status=0
    MATCHES="$(sudo grep -E "$TIME_SYNC_SOURCE" "$1")" || status=$?
    if [[ "$status" -gt 1 ]]; then
        echo "ERROR: cannot read $1"
        exit 1
    fi
    [[ "$status" -eq 0 ]]
}

updated=0
for f in "${files[@]}"; do
    read_time_sync_lines "$f" || continue
    # The regex goes through the environment, not -v, which would process its backslashes.
    sudo env SOURCE="$TIME_SYNC_SOURCE" awk -v poll="$POLL" '
        $0 ~ ENVIRON["SOURCE"] {
            gsub(/[[:space:]]+(minpoll|maxpoll)[[:space:]]+-?[0-9]+/, "")
            sub(/[[:space:]]+$/, "")
            $0 = $0 " minpoll " poll " maxpoll " poll
        }
        { print }
    ' "$f" >"${WORK}/conf"
    # cp onto the existing file keeps its owner and mode.
    sudo cp "${WORK}/conf" "$f"
    echo "✓ ${f}: Amazon Time Sync polled every $((1 << POLL)) s"
    updated=$((updated + 1))
done

if [[ "$updated" -eq 0 ]]; then
    echo "ERROR: no Amazon Time Sync source (169.254.169.123 or fd00:ec2::123) in ${CHRONY_DIR}"
    exit 1
fi

# Self-verify: every Time Sync line now carries exactly our poll, once.
for f in "${files[@]}"; do
    read_time_sync_lines "$f" || continue
    while IFS= read -r line; do
        if [[ "$(grep -Eo '(minpoll|maxpoll) -?[0-9]+' <<<"$line" | sort | tr '\n' ' ')" != "maxpoll ${POLL} minpoll ${POLL} " ]]; then
            echo "ERROR: ${f} still has a Time Sync line without minpoll ${POLL} maxpoll ${POLL}: ${line}"
            exit 1
        fi
    done <<<"$MATCHES"
done

echo "✓ configure_chrony_poll.sh completed successfully"
