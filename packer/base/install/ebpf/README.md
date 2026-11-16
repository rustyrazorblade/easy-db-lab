# ebpf_exporter program overrides, built into the base image

Every `*.bpf.c` here replaces the same-named program shipped in the pinned
`ghcr.io/cloudflare/ebpf_exporter` image. Why each one exists, and what it changes against
upstream, is in the header comment of the `.bpf.c` file itself; this file covers only the
mechanism.

## Build

`install_ebpf_programs.sh` runs at AMI build time. For each `<name>.bpf.c` it:

1. Compiles it with `clang -target bpf` against the build kernel's own BTF (`vmlinux.h` is dumped
   from `/sys/kernel/btf/vmlinux`) plus `maps.bpf.h` and `bits.bpf.h` fetched from the pinned
   exporter release. The object is never checked in: it is specific to the kernel it was built
   against.
2. Reads the attach points from the compiled object's section names (`llvm-objdump -h`) and
   checks each against the build kernel. `fentry`/`kprobe`/`kretprobe` names must be a `FUNC` in
   the kernel's BTF (BTF, not kallsyms: fentry attaches to any function with BTF, static or not).
   `raw_tp`/`tp_btf` names must have a `__tracepoint_<name>` symbol in `/proc/kallsyms`. A miss
   prints `✗ <name>: ... is not in this kernel` and fails the AMI build, where it is easy to see,
   instead of failing at attach time inside the exporter.
3. Installs the object as `/usr/local/lib/ebpf_exporter/<name>.bpf.o`.

## Mount

`EbpfExporterManifestBuilder` lists the overridden programs in `OVERRIDDEN_PROGRAMS` and mounts
each object from the AMI over the image's `/examples/<name>.bpf.o` with a `File`-typed hostPath, so
an AMI missing the object fails the pod visibly rather than silently running the image's copy.
`--config.names` and the yaml stay exactly as the image ships them. A test holds
`OVERRIDDEN_PROGRAMS` equal to the set of `.bpf.c` files in this directory, so adding a program
means adding the source here and the name there.
