#!/usr/bin/env bash
#
# Compiles the ebpf_exporter program overrides in install/ebpf/ (uploaded to /tmp/ebpf) against
# this kernel's BTF and installs the objects under /usr/local/lib/ebpf_exporter/.  See
# install/ebpf/README.md for why the objects are built here and not checked in.
set -euo pipefail

echo "=== Running: install_ebpf_programs.sh ==="

EBPF_EXPORTER_VERSION="v2.5.1"   # must match EbpfExporterManifestBuilder.IMAGE
SRC=/tmp/ebpf
OUT=/usr/local/lib/ebpf_exporter
BUILD=$(mktemp -d)

sudo DEBIAN_FRONTEND=noninteractive apt install -y --no-install-recommends clang llvm libbpf-dev

if [[ -f /usr/local/lib/edl-cache.sh ]]; then
    # shellcheck disable=SC1091
    source /usr/local/lib/edl-cache.sh
else
    cached_fetch() { echo "no S3 cache; downloading $1"; curl -fsSL --retry 3 "$1" -o "$3"; }
fi

# The sources include maps.bpf.h, which in turn includes bits.bpf.h.
for h in maps.bpf.h bits.bpf.h; do
    cached_fetch \
        "https://raw.githubusercontent.com/cloudflare/ebpf_exporter/${EBPF_EXPORTER_VERSION}/examples/${h}" \
        "ebpf_exporter/${EBPF_EXPORTER_VERSION}/${h}" \
        "${BUILD}/${h}"
done

# shellcheck disable=SC2024  # sudo is for reading the BTF; BUILD is owned by this user
sudo bpftool btf dump file /sys/kernel/btf/vmlinux format c > "${BUILD}/vmlinux.h"

# Every attach point is checked against this kernel before the object is installed; a probe on a
# missing symbol otherwise fails at attach time inside the exporter, where it is far harder to see.
# Functions (fentry, kprobe, kretprobe) are checked against BTF, not kallsyms: fentry attaches to
# any function with BTF, static or not, so a `T`-only kallsyms check rejects symbols that attach
# fine.  Tracepoints (raw_tp, tp_btf) are checked against the kernel's __tracepoint_<name> symbols.
# Both lists are reduced to bare names once, here, so the per-program checks are a grep each.
sudo bpftool btf dump file /sys/kernel/btf/vmlinux | awk '/ FUNC /{print $3}' | tr -d "'" > "${BUILD}/btf-funcs"
# shellcheck disable=SC2024  # sudo is for reading kallsyms; BUILD is owned by this user
sudo awk '$3 ~ /^__tracepoint_/ {sub(/^__tracepoint_/, "", $3); print $3}' /proc/kallsyms > "${BUILD}/tracepoints"

ARCH=$(dpkg --print-architecture)
KERNEL=$(uname -r)
case "${ARCH}" in
    amd64) TARGET_ARCH=x86 ;;
    arm64) TARGET_ARCH=arm64 ;;
    *) echo "unsupported architecture ${ARCH}" >&2; exit 1 ;;
esac

sudo mkdir -p "${OUT}"
for src in "${SRC}"/*.bpf.c; do
    name=$(basename "${src}" .bpf.c)
    obj="${BUILD}/${name}.bpf.o"

    clang -g -O2 -target bpf "-D__TARGET_ARCH_${TARGET_ARCH}" -Wno-missing-declarations \
        -I"${BUILD}" -I/usr/include -c "${src}" -o "${obj}"
    llvm-strip -g "${obj}"

    # The attach points are the object's section names.  The object is what the exporter loads,
    # so it, not the source, is what gets checked.
    llvm-objdump -h "${obj}" | awk '$2 ~ /^(fentry|kprobe|kretprobe|raw_tp|tp_btf)\//{print $2}' | while read -r sec; do
        kind=${sec%%/*}
        sym=${sec#*/}
        case "${kind}" in
            raw_tp|tp_btf)
                if ! grep -q -x -F "${sym}" "${BUILD}/tracepoints"; then
                    echo "✗ ${name}: tracepoint ${sym} is not in this kernel (${KERNEL})" >&2
                    exit 1
                fi
                ;;
            *)
                if ! grep -q -x -F "${sym}" "${BUILD}/btf-funcs"; then
                    echo "✗ ${name}: symbol ${sym} is not in this kernel (${KERNEL})" >&2
                    exit 1
                fi
                ;;
        esac
    done

    sudo install -m 0644 "${obj}" "${OUT}/${name}.bpf.o"
    size=$(stat -c %s "${OUT}/${name}.bpf.o")
    echo "built ${OUT}/${name}.bpf.o (${size} bytes) for ${KERNEL}"
done

rm -rf "${BUILD}"

echo "✓ install_ebpf_programs.sh completed successfully"
