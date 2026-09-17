# ebpf_exporter program overrides, built into the base image

`install_ebpf_programs.sh` compiles every `*.bpf.c` here into
`/usr/local/lib/ebpf_exporter/<name>.bpf.o` at AMI build time.  `EbpfExporterManifestBuilder`
mounts each object over the image's `/examples/<name>.bpf.o`, so `--config.names` and the yaml
stay as the pinned `ghcr.io/cloudflare/ebpf_exporter` release ships them.  The object is never
checked in; the build instance's own kernel BTF supplies `vmlinux.h`, and the two helper headers
come from the pinned exporter release.

## cachestat

Upstream v2.5.1 probes `mark_page_accessed` and `add_to_page_cache_lru`.  On a folio page cache
(kernel 6.x and later; Ubuntu 26.04 ships 7.0) those symbols still exist and attach, but the hot
paths call `folio_mark_accessed` and `filemap_add_folio` directly, so the access and miss counters
never move and only `cache_writes` and `page_mark_dirties` appear.  `cachestat.bpf.c` probes the
folio entry points with the same map and operation ids as upstream, so the upstream yaml still
applies and the metric keeps its name:

```
ebpf_exporter_page_cache_ops_total{operation="cache_access"|"cache_writes"|"page_add_lru"|"page_mark_dirties"}
```

Page cache hit ratio: `(cache_access - page_add_lru) / cache_access`.

The build script checks the probed symbols exist in the build kernel's `/proc/kallsyms` and that
the object carries the expected sections, and fails the AMI build otherwise.  If a kernel moves
them again, this is where it shows up.

## syscalls

Upstream v2.5.1 counts every negative syscall return as an errno.  On kernel 7.0 that records
pointer-sized values (`unknown:9223372036854775808` and thousands of others), one series each,
with no upper bound.  `syscalls.bpf.c` keeps the same maps and metric names but only counts a
return in `[-MAX_ERRNO, -1]` (the kernel's own `IS_ERR_VALUE` rule) as an error:

```
ebpf_exporter_syscalls_total{syscall}
ebpf_exporter_syscall_errors_total{errno}
```

The syscall names come from the exporter's own amd64 table, which has a few wrong entries
(`202` is shown as `futex_time64`; it is `futex` on x86_64).  Counts are per number and correct.
