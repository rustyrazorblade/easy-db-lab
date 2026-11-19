# Node OS Tuning

Every node (db, app and control) runs with the OS tuning a production database host would have. `up` and `setup-instances` apply it during node setup, before K3s or any database starts. Every value persists across a reboot, and no AMI rebuild is needed: the settings are files packaged in easy-db-lab, uploaded to each node by setup.

When a value the node supports does not take, setup fails on that node with a line naming the setting, the value it wanted and the value it read back, for example `ERROR: vm.swappiness: wanted 1, got 60`. Every value that does not match gets its own line. `up` reports it as the node's failed setup step. A sysctl key the kernel rejects fails setup with the kernel's message, which names the key.

To check a node by hand, run `sudo edl-node-tuning verify` on it. It exits 0 when every value matches, and prints no `ERROR:` line. It can still print a `skipped` line for a setting the node does not have, such as irqbalance on a node with one CPU.

`edl-node-tuning.service` runs the same check at every boot, after it applies the settings. The check also fails when that service is not enabled, since it would then restore nothing after a reboot. A value that drifted after a reboot does not fail the boot. The service logs it to the node's journal under the `edl-node-tuning` tag:

```
journalctl -u edl-node-tuning -b --no-pager -o cat
```

## When a value does not take

- **The value drifted, or setup was interrupted:** run `setup-instances` again on that node.
- **A running process has old limits** (`restart <unit> to apply`): restart the named unit.
- **The node itself is bad:** replace the node.
- **The value cannot exist on that instance type:** open an issue naming the instance type and the line.

## Process limits

Every database and workload process runs with:

| Limit | Value |
|---|---|
| Open files (soft and hard) | 1048576 |
| Locked memory | unlimited |
| Processes | unlimited |
| Address space | unlimited |

These limits come from systemd, not from `/etc/security/limits.d`, which only applies to login sessions:

- A drop-in, `/etc/systemd/system/<unit>.service.d/50-easydblab-limits.conf`, sets them for `cassandra`, `k3s` and `k3s-agent`.
- Every pod is a child of the container runtime inside `k3s` or `k3s-agent`, so every kit database and every stress run (a Kubernetes Job) gets the same limits. The K3s start scripts also set the kubelet's `max-open-files` to 1048576, because the kubelet inside K3s otherwise lowers the K3s process's open-file limit to 1000000.
- `/etc/systemd/system.conf.d/50-easydblab-limits.conf` makes them the default for any other service.
- `/etc/security/limits.d/50-easydblab.conf` gives SSH sessions the same limits.

`fs.nr_open` and `fs.file-max` are checked to be at least 1048576. systemd already sets both far higher at boot, so setup never writes them.

A service that is already running gets new limits only at its next restart. A re-run of `setup-instances` against a live cluster does not restart Cassandra or K3s. Instead `verify` reads each running unit's own limits from `/proc/<pid>/limits`, and fails with a line that names the unit, for example:

```
ERROR: cassandra running process open files: wanted 1048576, got 65536; restart cassandra to apply
```

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

The data disk at `/mnt/db1` is mounted with `noatime`. Every NVMe and Xen disk uses the I/O scheduler `none` and a 4 KiB readahead. A udev rule applies both when a disk appears, and `edl-node-tuning.service` applies them again at every boot before it checks them, so they also hold after a reboot.

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

The wide ephemeral range also covers the Kubernetes NodePort range and the ports that host-network services listen on. If those ports were not reserved, an outbound connection could take one as its source port, and the listener would then fail to bind it when it restarts. So `net.ipv4.ip_local_reserved_ports` reserves:

- The NodePort range 30000-32767.
- Every fixed port below that which a host listener uses: K3s, etcd, containerd, the kubelet, kube-proxy, Cilium and Hubble, the observability stack on the control node, and Cassandra and its sidecar on db nodes.
- The stress metrics ports 9500-9599 on app nodes. Each `cassandra stress start` takes the next port in that window, and wraps after 100 runs. A run cannot bind its metrics port while the run 100 before it is still running on the same node.
- flannel's VXLAN UDP port 8472, used when the CNI is flannel.
- Tailscale's UDP port 41641.

A reserved port can still be bound explicitly. Only the kernel's automatic source-port choice skips it.

The settings deliberately leave a few keys alone:

- Cilium owns `rp_filter` and the other keys it writes in `99-zzz-override_cilium.conf`.
- kube-proxy sizes connection tracking (`nf_conntrack_*`).
- The kubelet sets `vm.overcommit_memory` and the `kernel.panic*` keys.

Most `net.*` values are per network namespace. They apply to Cassandra, the stress server and every host-network pod, which is the whole observability stack. They do not reach a kit database in the pod network, which starts at kernel defaults. Tuning pod network namespaces is follow-up [#1202](https://github.com/rustyrazorblade/easy-db-lab/issues/1202). The global keys (`netdev_max_backlog`, `rmem_max`, `wmem_max`, `rps_sock_flow_entries`) and the packet steering below do reach pods, because pod traffic arrives on the node's physical interfaces.

## Packet steering and interrupts

- `irqbalance` runs on every node with more than one CPU. There is no manual IRQ pinning.
- Every physical network interface has receive packet steering (RPS) and receive flow steering (RFS) on. A physical interface is one backed by a device; `lo`, `veth`, `cilium_*`, `lxc*` and `tailscale0` are not.
  - every receive queue steers to every CPU;
  - `net.core.rps_sock_flow_entries` is 32768;
  - each queue's flow count is 32768 divided by the number of queues, rounded up to a power of two as the kernel stores it (3 queues get 16384 each).
- A udev rule applies the same settings to a physical interface that appears later, such as a secondary ENI that Cilium attaches for pod IPs. The rule file is `90-easydblab.rules`: it runs after udev gives the new interface its final name (`eth0` becomes `ens6`).
- A second rule steers the interface again when its receive queues change, for example after `ethtool -L`, because each queue's flow count depends on the queue count.

## CPU frequency and idle states

Where the instance exposes them:

- Every CPU uses the `performance` governor.
- Every idle state deeper than C1 is disabled.

C1 is the idle state named `C1` (or `C1_ACPI`, or `C1-` followed by a CPU model, such as `C1-SKX`). Where no state has that name, C1 is the first state that is not `POLL`: `HALT` on older x86 kernels, and `WFI` on arm64. So on Graviton `.metal` `cpu-sleep` is disabled and `WFI` stays on.

Most EC2 instance types expose neither, including the defaults (`i7i.xlarge`, `c8id.2xlarge`, `m8id.xlarge`) and every Graviton type except `.metal`. Only some whole-socket and `.metal` sizes have processor state control; Graviton `.metal` exposes idle states but no frequency scaling. On a node without them, the tuning service logs one line per skipped setting, naming the node and the instance type (from the cluster state), and carries on:

```
edl-node-tuning: skipped CPU governor on db0: i7i.xlarge exposes no frequency scaling
edl-node-tuning: skipped CPU idle states on db0: i7i.xlarge exposes no idle states
```

These lines come from `edl-node-tuning.service`, so they are in the node's journal. Setup prints the service's lines from its own run after it starts the service, prefixed with `edl-node-tuning.service:`. Later boots log them only to the journal. The node's log collector does not ship journal lines from before it started, so lines from setup and early boot are only on the node itself. Read them with `journalctl -u edl-node-tuning -b --no-pager -o cat`.

## Persistence

| What | How it persists |
|---|---|
| Kernel settings | `/etc/sysctl.d/60-easydblab.conf`, applied by systemd at every boot |
| Process limits | systemd drop-ins and `system.conf.d` |
| Disk scheduler and readahead, packet steering | `/etc/udev/rules.d/90-easydblab.rules`, applied whenever a device or a receive queue appears, and `edl-node-tuning.service` at every boot |
| Transparent huge pages, CPU governor and idle states | `edl-node-tuning.service`, a oneshot that runs before K3s and the database at every boot, then checks every value |
| `noatime` | the `/mnt/db1` line in `/etc/fstab` |
