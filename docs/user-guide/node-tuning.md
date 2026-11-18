# Node OS Tuning

Every node (db, app and control) runs with the OS tuning a production database host would have.
`up` and `setup-instances` apply it during node setup, before K3s or any database starts. Every
value persists across a reboot, and no AMI rebuild is needed: the settings are files packaged in
easy-db-lab, uploaded to each node by setup.

When a value the node supports does not take, setup fails on that node with a line naming the
setting, the value it wanted and the value it read back, for example
`ERROR: vm.swappiness: wanted 1, got 60`. `up` reports it as the node's failed setup step.

To check a node by hand, run `sudo edl-node-tuning verify` on it. It prints nothing and exits 0
when every value matches.

## Process limits

Every database and workload process runs with:

| Limit | Value |
|---|---|
| Open files (soft and hard) | 1048576 |
| Locked memory | unlimited |
| Processes | unlimited |
| Address space | unlimited |

These limits come from systemd, not from `/etc/security/limits.d`, which only applies to login
sessions:

- A drop-in, `/etc/systemd/system/<unit>.service.d/50-easydblab-limits.conf`, sets them for
  `cassandra`, `cassandra-easy-stress`, `k3s` and `k3s-agent`.
- Every pod is a child of the container runtime inside `k3s` or `k3s-agent`, so every kit
  database gets the same limits.
- `/etc/systemd/system.conf.d/50-easydblab-limits.conf` makes them the default for any other
  service.
- `/etc/security/limits.d/50-easydblab.conf` gives SSH sessions the same limits.

`fs.nr_open` and `fs.file-max` are checked to be at least 1048576. systemd already sets both far
higher at boot, so setup never writes them.

A service that is already running gets new limits only at its next restart. A re-run of
`setup-instances` against a live cluster does not restart Cassandra or K3s.

## Kernel memory and file-system settings

| Setting | Value | Why |
|---|---|---|
| Transparent huge pages | `madvise`, defrag `never` | No huge pages unless a process asks for them, and never a compaction stall. Suits every supported database. |
| `vm.max_map_count` | 1048575 | Cassandra and other databases map many files. |
| `vm.swappiness` | 1 | Swap is off; this keeps the kernel from preferring it if it comes back. |
| `vm.dirty_background_bytes` | 64 MiB | Writeback starts early and in small amounts, whatever the RAM size. |
| `vm.dirty_bytes` | 256 MiB | Caps the dirty pages a writer can build up before it blocks. |
| `vm.zone_reclaim_mode` | 0 | No NUMA-local reclaim stalls. |
| `kernel.numa_balancing` | 0 | No page migration under the database. |
| `kernel.perf_event_paranoid` | 1 | Profilers can read kernel stacks. |
| `kernel.kptr_restrict` | 0 | Profilers can resolve kernel symbols. |
| `fs.inotify.max_user_instances` | 8192 | Many watchers: kubelet, containerd and the log collectors all use inotify. |
| `fs.inotify.max_user_watches` | 1048576 | |
| `fs.aio-max-nr` | 1048576 | Databases that use Linux AIO. |

Swap is off, and any swap line in `/etc/fstab` is commented out so it stays off after a reboot.

The data disk at `/mnt/db1` is mounted with `noatime`. Every NVMe and Xen disk uses the I/O scheduler
`none` and a 4 KiB readahead. A udev rule applies both when a disk appears, so they also hold after
a reboot.

## Network settings

| Setting | Value |
|---|---|
| `net.core.somaxconn` | 65535 |
| `net.ipv4.tcp_max_syn_backlog` | 65535 |
| `net.core.netdev_max_backlog` | 250000 |
| `net.core.rmem_max`, `net.core.wmem_max` | 16777216 |
| `net.ipv4.tcp_rmem` | `4096 131072 16777216` |
| `net.ipv4.tcp_wmem` | `4096 16384 16777216` |
| `net.ipv4.tcp_keepalive_time` / `_intvl` / `_probes` | 60 / 10 / 3 |
| `net.ipv4.tcp_slow_start_after_idle` | 0 |
| `net.ipv4.tcp_tw_reuse` | 1 |
| `net.ipv4.ip_local_port_range` | `1024 65535` |

The wide ephemeral range also covers the Kubernetes NodePort range and the ports that host-network
services listen on. If those ports were not reserved, an outbound connection could take one as its
source port, and the listener would then fail to bind it when it restarts. So
`net.ipv4.ip_local_reserved_ports` reserves:

- The NodePort range 30000-32767.
- Every fixed port below that which a host listener uses: K3s, etcd, containerd, the kubelet,
  kube-proxy, Cilium and Hubble, the observability stack on the control node, and Cassandra and its
  sidecar on db nodes.
- The stress metrics ports 9500-9599 on app nodes. Each `cassandra stress start` takes the next
  port in that window, and wraps after 100 runs.

A reserved port can still be bound explicitly. Only the kernel's automatic source-port choice
skips it.

The settings deliberately leave a few keys alone:

- Cilium owns `rp_filter` and the other keys it writes in `99-zzz-override_cilium.conf`.
- kube-proxy sizes connection tracking (`nf_conntrack_*`).
- The kubelet sets `vm.overcommit_memory` and the `kernel.panic*` keys.

Most `net.*` values are per network namespace. They apply to Cassandra, the stress server and every
host-network pod, which is the whole observability stack. They do not reach a kit database in the
pod network, which starts at kernel defaults. Tuning pod network namespaces is follow-up
[#1202](https://github.com/rustyrazorblade/easy-db-lab/issues/1202). The global keys
(`netdev_max_backlog`, `rmem_max`, `wmem_max`, `rps_sock_flow_entries`) and the packet steering
below do reach pods, because pod traffic arrives on the node's ENA interfaces.

## Packet steering and interrupts

- `irqbalance` runs on every node with more than one CPU. There is no manual IRQ pinning.
- Every ENA interface has receive packet steering (RPS) and receive flow steering (RFS) on:
  - every receive queue steers to every CPU;
  - `net.core.rps_sock_flow_entries` is 32768;
  - each queue's flow count is 32768 divided by the number of queues, rounded up to a power of
    two as the kernel stores it (3 queues get 16384 each).
- A udev rule applies the same settings to an ENA interface that appears later, such as a
  secondary ENI that Cilium attaches for pod IPs. The rule file is `90-easydblab.rules`: it runs
  after udev gives the new interface its final name (`eth0` becomes `ens6`).

## CPU frequency and idle states

Where the instance exposes them:

- Every CPU uses the `performance` governor.
- Every idle state deeper than C1 is disabled.

Most EC2 instance types expose neither, including the defaults (`i4i.xlarge`, `c6id.2xlarge`,
`m5d.xlarge`) and all Graviton types. Only some whole-socket and `.metal` sizes have processor
state control. On a node without them, setup logs one line per skipped setting, naming the node
and the instance type, and carries on:

```
edl-node-tuning: skipped CPU governor on db0: i4i.xlarge exposes no frequency scaling
edl-node-tuning: skipped CPU idle states on db0: i4i.xlarge exposes no idle states
```

The same lines go to the journal under the `edl-node-tuning` tag.

## Persistence

| What | How it persists |
|---|---|
| Kernel settings | `/etc/sysctl.d/60-easydblab.conf`, applied by systemd at every boot |
| Process limits | systemd drop-ins and `system.conf.d` |
| Disk scheduler and readahead, ENA packet steering | `/etc/udev/rules.d/90-easydblab.rules`, applied whenever a device appears |
| Transparent huge pages, CPU governor and idle states | `edl-node-tuning.service`, a oneshot that runs before K3s and the database at every boot |
| `noatime` | the `/mnt/db1` line in `/etc/fstab` |
