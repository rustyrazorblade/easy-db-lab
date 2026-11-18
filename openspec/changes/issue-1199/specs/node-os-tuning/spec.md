## ADDED Requirements

### Requirement: Every node runs with production OS tuning

Every cluster node (db, app and control) SHALL run with the OS tuning in this capability, so that a database under test behaves as it would on a production host.  Node setup during `up` and `setup-instances` SHALL apply the tuning before K3s or any database starts on the node.  The tuning SHALL come entirely from files packaged in the distribution; it SHALL NOT need an AMI rebuild, a source checkout, or network access from the node.

Every value SHALL persist across a reboot: after a node reboots, it reports the same values with no easy-db-lab command run again.

When node setup cannot apply a setting the node supports, setup on that node SHALL exit non-zero and name the setting, and `up` SHALL treat it as a failed node setup step.  Setup SHALL report every value that does not match, one line each, not only the first.  A sysctl key the kernel rejects SHALL fail setup with the kernel's message, which names the key.  A setting the instance does not expose (see "CPU frequency and idle states where the instance exposes them") is not a failure.

At every boot the node SHALL check every value again after it applies the settings.  A value that does not match SHALL NOT fail the boot; it SHALL be logged to the node's journal under the `edl-node-tuning` tag, with the same line setup prints.  Every skip and mismatch line SHALL also go to the journal when the check is run by hand or by setup.  The check SHALL fail when the boot unit is not enabled, and setup SHALL fail when it cannot enable the boot unit or reload the udev rules, since either leaves the node untuned after a reboot with no line in its journal.

#### Scenario: Fresh cluster reports the tuned values on every node
- **WHEN** `up` completes on a cluster with db, app and control nodes
- **THEN** every node reports each value listed in this capability

#### Scenario: Values survive a reboot
- **GIVEN** a node of any type that `up` set up
- **WHEN** the node reboots and comes back
- **THEN** it reports every value listed in this capability, and its K3s node returns to Ready

#### Scenario: A setting the node supports cannot be applied
- **WHEN** node setup applies a kernel setting and the value the kernel reports afterwards differs from the one requested
- **THEN** setup on that node exits non-zero with an error that names the setting, the requested value and the value read back
- **AND** `up` reports the node's setup step as failed

#### Scenario: Drift after a reboot is reported
- **GIVEN** a node that setup tuned
- **WHEN** the node reboots and a tuned value comes back different
- **THEN** the boot completes, and the node's journal has a line naming the setting, the requested value and the value read back

#### Scenario: Setup is re-runnable
- **GIVEN** a node that setup already tuned
- **WHEN** `setup-instances` runs again
- **THEN** setup succeeds and the node reports the same values, with no duplicated configuration

### Requirement: Process limits for databases and their services

Every database and workload process on a node SHALL run with these limits: open files 1048576 (soft and hard), locked memory unlimited, processes unlimited, address space unlimited.  This SHALL hold for a database that runs as a systemd service (Cassandra on db nodes), for every process in a pod (kits and stress runs, which are K8s Jobs, run under K3s's container runtime), and for any other service systemd starts.  The kubelet inside K3s SHALL NOT lower the K3s process's open-file limit.  Interactive login sessions on the node SHALL get the same limits.

The kernel-wide limits SHALL be at least as high as the per-process ones: `fs.nr_open` and `fs.file-max` SHALL each be at least 1048576.  Setup SHALL NOT lower either value below what the system had.

#### Scenario: Cassandra gets the limits
- **GIVEN** a db node with Cassandra started through its systemd service
- **WHEN** the operator reads the Cassandra process's limits
- **THEN** max open files is 1048576 (soft and hard) and max locked memory is unlimited

#### Scenario: A kit database in a pod gets the limits
- **GIVEN** a kit database running in a pod on a db node
- **WHEN** the operator reads the database process's limits
- **THEN** max open files is 1048576 and max locked memory is unlimited

#### Scenario: A running service has old limits after a re-run of setup
- **GIVEN** Cassandra or K3s running with limits from before setup installed them
- **WHEN** `setup-instances` runs again
- **THEN** setup does not restart the service, and fails with a line naming the unit, the limit, the wanted and actual value, and saying to restart the unit to apply them

#### Scenario: A service with no limit settings of its own gets the limits
- **WHEN** systemd starts a service that declares no limits of its own
- **THEN** that service runs with open files 1048576 and locked memory unlimited

### Requirement: Kernel memory and file-system settings

Every node SHALL report:

| Setting | Value |
|---|---|
| Transparent huge pages, enabled | `madvise` |
| Transparent huge pages, defrag | `never` |
| `vm.max_map_count` | 1048575 |
| `vm.swappiness` | 1 |
| `vm.dirty_background_bytes` | 67108864 (64 MiB) |
| `vm.dirty_bytes` | 268435456 (256 MiB) |
| `vm.zone_reclaim_mode` | 0 |
| `kernel.numa_balancing` | 0 |
| `kernel.perf_event_paranoid` | 1 |
| `kernel.kptr_restrict` | 0 |
| `fs.inotify.max_user_instances` | 8192 |
| `fs.inotify.max_user_watches` | 1048576 |
| `fs.aio-max-nr` | 1048576 |

Swap SHALL be off.

#### Scenario: inotify limits allow many watchers
- **WHEN** the operator reads the inotify limits on any node
- **THEN** `fs.inotify.max_user_instances` is 8192 and `fs.inotify.max_user_watches` is 1048576

#### Scenario: Huge pages are opt-in
- **WHEN** the operator reads the transparent huge page settings on any node
- **THEN** enabled is `madvise` and defrag is `never`

### Requirement: Network settings that keep Kubernetes ports free

Every node SHALL report:

| Setting | Value |
|---|---|
| `net.core.somaxconn` | 65535 |
| `net.ipv4.tcp_max_syn_backlog` | 65535 |
| `net.core.netdev_max_backlog` | 250000 |
| `net.core.rmem_max`, `net.core.wmem_max` | 16777216 |
| `net.ipv4.tcp_rmem` | `4096 131072 16777216` |
| `net.ipv4.tcp_wmem` | `4096 16384 16777216` |
| `net.ipv4.tcp_keepalive_time` | 60 |
| `net.ipv4.tcp_keepalive_intvl` | 10 |
| `net.ipv4.tcp_keepalive_probes` | 3 |
| `net.ipv4.tcp_slow_start_after_idle` | 0 |
| `net.ipv4.tcp_tw_reuse` | 1 |
| `net.ipv4.ip_local_port_range` | `1024 65535` |

Because the ephemeral port range then covers the Kubernetes NodePort range and the fixed ports that host-network services listen on, `net.ipv4.ip_local_reserved_ports` SHALL reserve the NodePort range 30000-32767 and every other fixed port of 1024 or above that a host-network listener in the cluster binds (the K3s, etcd, kubelet, kube-proxy, Cilium and Hubble ports, the observability backends on the control node, the database and sidecar ports on db nodes, the stress metrics window on app nodes, flannel's VXLAN UDP port 8472 when the CNI is flannel, and Tailscale's UDP port 41641).  The packaged list SHALL be sorted with adjacent ports merged, the form the kernel reports it in.  An outbound connection SHALL never take one of those ports as its source port.  Connection tracking limits SHALL stay as kube-proxy sets them.

#### Scenario: Outbound connections never take a reserved port
- **GIVEN** a node with the tuned port range
- **WHEN** the node opens many outbound connections
- **THEN** no connection uses a source port in 30000-32767 or any reserved listener port

#### Scenario: NodePorts and host-network services still bind
- **WHEN** a kit publishes a NodePort and the observability stack starts its host-network listeners
- **THEN** every listener binds its port and every NodePort answers

#### Scenario: A new fixed host port is reserved
- **WHEN** a developer adds a fixed port of 1024 or above for a host-network listener
- **THEN** a unit test fails until the port is in the reserved list

### Requirement: Stress metrics ports stay in a reserved window

Each `cassandra stress start` SHALL bind its metrics endpoint to a port in the window 9500-9599 on the app node.  The Nth run SHALL take port 9500 + (N mod 100), so the port wraps after 100 runs and never leaves the window.  A run whose port is still bound on that node by the run 100 before it cannot bind its metrics endpoint.  Node tuning SHALL reserve the whole window in `net.ipv4.ip_local_reserved_ports`, so no outbound connection takes a stress metrics port as its source port.

#### Scenario: Ports wrap inside the window
- **WHEN** the 99th, 100th and 101st stress runs start
- **THEN** they bind 9599, 9500 and 9501

#### Scenario: Every stress metrics port is reserved
- **WHEN** the operator reads `net.ipv4.ip_local_reserved_ports` on an app node
- **THEN** it covers 9500-9599

### Requirement: Data disk mount and block-device settings

The data disk mounted at `/mnt/db1` SHALL be mounted with `noatime`, at setup time and after every reboot; a node whose data disk was already mounted without it SHALL be remounted with it by a re-run of setup.  Every NVMe and Xen block device on the node SHALL use the I/O scheduler `none` and a readahead of 4 KiB, applied when the device appears, so a device that appears after setup or after a reboot gets the same values.  The boot unit SHALL also apply them before its verify, so the boot verify does not depend on when udev finishes processing the boot's device events.  The readahead SHALL NOT be set only at run time.

#### Scenario: Data disk mounted noatime
- **WHEN** the operator reads the mount options of `/mnt/db1` on any node
- **THEN** they include `noatime`

#### Scenario: Scheduler and readahead survive a reboot
- **GIVEN** a node that rebooted
- **WHEN** the operator reads the data disk's queue settings
- **THEN** the scheduler is `none` and the readahead is 4 KiB

### Requirement: CPU frequency and idle states where the instance exposes them

On a node whose instance exposes CPU frequency scaling, every CPU SHALL use the `performance` governor.  On a node whose instance exposes CPU idle states, every idle state deeper than C1 SHALL be disabled.  C1 is the state named `C1`, `C1_ACPI` or `C1-*` (such as `C1-SKX`); where no state has that name, it is the first state that is not `POLL` (`HALT` on older x86 kernels, `WFI` on arm64).  The instance type the skip line names SHALL come from the cluster state, not from a cloud provider lookup on the node.  On a node whose instance exposes neither, setup SHALL change nothing for that part and SHALL log one line naming the node, the instance type and the setting it skipped.  A skip SHALL NOT fail setup.

#### Scenario: Instance with frequency scaling
- **GIVEN** an instance type that exposes CPU frequency scaling
- **WHEN** setup completes
- **THEN** every CPU's governor is `performance`

#### Scenario: Arm64 metal instance
- **GIVEN** an arm64 instance whose idle states are `WFI` and `cpu-sleep`
- **WHEN** setup completes
- **THEN** `cpu-sleep` is disabled and `WFI` is not

#### Scenario: Instance without frequency or idle-state control
- **GIVEN** an instance type that exposes neither CPU frequency scaling nor idle states
- **WHEN** setup completes
- **THEN** setup succeeds and the node's log has one line for each skipped setting, the governor and the C-states, each naming the instance type

### Requirement: Network interrupt and packet steering

`irqbalance` SHALL run on every node with more than one CPU (its unit does not start on one CPU, where there is nothing to balance, and `verify` logs a skip line instead).  Every physical network interface (one backed by a device, so not loopback, veth, CNI or VPN interfaces) SHALL have receive packet steering and receive flow steering enabled: each receive queue steers to every CPU of the node, `net.core.rps_sock_flow_entries` is 32768, and each queue's flow count is 32768 divided by the number of receive queues, rounded up to a power of two (the kernel stores and reports it rounded).  This SHALL also hold for a physical interface that appears after setup, such as a secondary ENI the CNI attaches for pod IPs, and for an interface whose receive queue count changes after setup.  There SHALL be no manual IRQ affinity pinning.

#### Scenario: Primary interface steering
- **WHEN** the operator reads the RPS mask of each receive queue of the primary interface
- **THEN** every queue's mask covers every CPU of the node

#### Scenario: A secondary ENI attached at run time
- **GIVEN** a node whose CNI attaches a secondary interface after setup
- **WHEN** the interface appears
- **THEN** its receive queues get the same RPS mask and flow count as the primary
