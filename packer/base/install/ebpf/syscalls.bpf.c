// Syscall counters, with the error side bounded to the real errno range.
//
// The upstream ebpf_exporter example counts every negative syscall return as an errno. On
// kernel 7.0 that records values such as 9223372036854775808 and other pointer-sized numbers,
// one series each, with no upper bound: a few hours of a stress run put 3,900 distinct errno
// labels into the metrics store. The kernel's own rule (IS_ERR_VALUE) is that an error return
// lies in [-MAX_ERRNO, -1]; anything else is a value, not an error. This version applies that
// rule. The maps, keys, and yaml are the same as upstream, so the metrics keep their names:
// ebpf_exporter_syscalls_total{syscall} and ebpf_exporter_syscall_errors_total{errno}.

#include <vmlinux.h>
#include <bpf/bpf_tracing.h>
#include "maps.bpf.h"

#define MAX_ERRNO 4095

struct {
    __uint(type, BPF_MAP_TYPE_HASH);
    __uint(max_entries, 1024);
    __type(key, u64);
    __type(value, u64);
} syscalls_total SEC(".maps");

struct {
    __uint(type, BPF_MAP_TYPE_HASH);
    __uint(max_entries, 1024);
    __type(key, u64);
    __type(value, u64);
} syscall_errors_total SEC(".maps");

SEC("tp_btf/sys_enter")
int BPF_PROG(sys_enter, struct pt_regs *regs, long id)
{
    increment_map(&syscalls_total, &id, 1);
    return 0;
}

SEC("tp_btf/sys_exit")
int BPF_PROG(sys_exit, struct pt_regs *regs, long ret)
{
    if (ret < 0 && ret >= -MAX_ERRNO) {
        ret = -ret; // negative return in the errno range is an error
        increment_map(&syscall_errors_total, &ret, 1);
    }

    return 0;
}

char LICENSE[] SEC("license") = "GPL";
