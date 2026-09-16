// Page cache hit/miss counters for kernels with the folio page cache (6.x and later).
//
// The upstream ebpf_exporter example probes mark_page_accessed and add_to_page_cache_lru.
// On a folio kernel those symbols still exist but the hot paths call folio_mark_accessed
// and filemap_add_folio directly, so the upstream counters never move and only the write
// side counts.  This version probes the folio entry points.  The map, the operation ids
// and the yaml are the same as upstream, so the metric keeps its name:
// ebpf_exporter_page_cache_ops_total{operation=cache_access|cache_writes|page_add_lru|page_mark_dirties}.
//
// Hit ratio = (cache_access - page_add_lru) / cache_access.

#include <vmlinux.h>
#include <bpf/bpf_tracing.h>
#include <bpf/bpf_core_read.h>
#include "maps.bpf.h"

enum pache_cache_op {
    OP_CACHE_ACCESS,
    OP_CACHE_WRITES,
    OP_PAGE_ADD_LRU,
    OP_PAGE_MARK_DIRTIES,
};

struct {
    __uint(type, BPF_MAP_TYPE_HASH);
    __uint(max_entries, 4);
    __type(key, u8);
    __type(value, u64);
} page_cache_ops_total SEC(".maps");

static int trace_event(enum pache_cache_op op)
{
    increment_map(&page_cache_ops_total, &op, 1);
    return 0;
}

// Every page cache access that marks a folio referenced.
SEC("fentry/folio_mark_accessed")
int folio_mark_accessed()
{
    return trace_event(OP_CACHE_ACCESS);
}

// A folio being inserted into the page cache: a miss that is now being filled.
SEC("fentry/filemap_add_folio")
int filemap_add_folio()
{
    return trace_event(OP_PAGE_ADD_LRU);
}

SEC("fentry/mark_buffer_dirty")
int mark_buffer_dirty()
{
    return trace_event(OP_CACHE_WRITES);
}

SEC("raw_tp/writeback_dirty_folio")
int writeback_dirty_folio()
{
    return trace_event(OP_PAGE_MARK_DIRTIES);
}

char LICENSE[] SEC("license") = "GPL";
