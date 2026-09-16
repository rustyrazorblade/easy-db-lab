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

for h in maps.bpf.h bits.bpf.h; do
    cached_fetch \
        "https://raw.githubusercontent.com/cloudflare/ebpf_exporter/${EBPF_EXPORTER_VERSION}/examples/${h}" \
        "ebpf_exporter/${EBPF_EXPORTER_VERSION}/${h}" \
        "${BUILD}/${h}"
done

sudo bpftool btf dump file /sys/kernel/btf/vmlinux format c | tee "${BUILD}/vmlinux.h" > /dev/null

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

    # Every probed symbol must exist in this kernel; a probe on a missing symbol fails at attach
    # time inside the exporter, where it is far harder to see than here.
    grep -o -E 'SEC\("(fentry|kprobe|kretprobe)/[a-zA-Z0-9_]+"\)' "${src}" | sed -E 's/.*\/([a-zA-Z0-9_]+)"\)/\1/' | while read -r sym; do
        if ! sudo grep -q -E " T ${sym}$" /proc/kallsyms; then
            echo "✗ ${name}: symbol ${sym} is not in this kernel (${KERNEL})" >&2
            exit 1
        fi
    done

    clang -g -O2 -target bpf "-D__TARGET_ARCH_${TARGET_ARCH}" -Wno-missing-declarations \
        -I"${BUILD}" -I/usr/include -c "${src}" -o "${obj}"
    llvm-strip -g "${obj}"

    # The section names are the attach points; confirm the object carries every one the source
    # declares.
    grep -o -E 'SEC\("[a-z_]+/[a-zA-Z0-9_]+"\)' "${src}" | sed -E 's/SEC\("(.*)"\)/\1/' | while read -r sec; do
        if ! llvm-objdump -h "${obj}" | grep -q -F " ${sec} "; then
            echo "✗ ${name}: section ${sec} missing from the compiled object" >&2
            exit 1
        fi
    done

    sudo install -m 0644 "${obj}" "${OUT}/${name}.bpf.o"
    size=$(stat -c %s "${OUT}/${name}.bpf.o")
    echo "built ${OUT}/${name}.bpf.o (${size} bytes) for ${KERNEL}"
done

rm -rf "${BUILD}"

echo "✓ install_ebpf_programs.sh completed successfully"
