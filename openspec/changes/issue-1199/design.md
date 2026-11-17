## Context

`setup_instance.sh` is a classpath resource.  `init` extracts it into the workspace; `NodeSetupService.runSetupScript` uploads it to every node (db, app, control) and runs it as `sudo bash setup_instance.sh`, in the SETUP step of each node's bring-up pipeline, before K3s and any database start.  Its tuning block today:

- `sudo sysctl kernel.perf_event_paranoid=1`, `kernel.kptr_restrict=0`: run-time only, lost on reboot.  (`packer/base/install/install_async_profiler.sh` sets the same two at bake time, which does not persist into the AMI either.)
- `echo 0 > /proc/sys/vm/zone_reclaim_mode`: run-time only.
- `/etc/security/limits.d/cassandra.conf`: PAM only.
- `/etc/sysctl.d/60-cassandra.conf`: `vm.max_map_count = 1048575`, the only persisted value.
- `swapoff --all`, and `blockdev --setra 8` on the data disk (run-time only).

The systemd units a database or workload runs under:

| Unit | Where | Source | Limits today |
|---|---|---|---|
| `cassandra.service` | db (AMI) | `packer/cassandra/services/` | none, so systemd defaults |
| `cassandra-easy-stress.service` | app (AMI) | `packer/cassandra/services/` | none |
| `edl-profiling-reconcile.service` | db | `packer/cassandra/services/` | none (a short oneshot; needs no tuning, covered by `DefaultLimit*`) |
| `k3s.service` | control | K3s installer at `up` | installer sets `LimitNOFILE=1048576`, `LimitNPROC=infinity`, `LimitCORE=infinity`; no `LimitMEMLOCK` |
| `k3s-agent.service` | db, app | K3s installer at `up` | same as `k3s.service` |
| `edl-tailscale-masquerade.service` | control | `install-tailscale-masquerade.sh` | oneshot; no tuning needed |

Every kit database (ClickHouse, Kafka, TiDB, Postgres, FerrosaDB, ...) runs in a pod.  Pods are children of the containerd that K3s embeds, so their limits come from `k3s(-agent).service` plus whatever rlimits containerd's CRI writes into each container's OCI spec.

## Goals / Non-Goals

**Goals:**
- Every value in the issue, on every node type, persisted across a reboot, with no AMI bake.
- Limits that reach the database process, whether it runs under systemd or in a pod.
- Each setting checked against K3s, Cilium, the kubelet, kube-proxy and the supported databases, with each conflict resolved by configuration.
- A setup failure names the setting that did not take.

**Non-Goals:**
- Per-pod network sysctls inside pod network namespaces (follow-up #1202, owner decision A).
- Manual IRQ affinity pinning.
- Kernel command-line changes (they need a reboot and a GRUB edit).
- Any `packer/` change.

## Decisions

### D1. Packaged resource files, staged by Kotlin, installed by `setup_instance.sh`

New classpath directory `src/main/resources/com/rustyrazorblade/easydblab/commands/node-tuning/`:

- `60-easydblab.conf`: the sysctl drop-in, installed to `/etc/sysctl.d/`.
- `easydblab-limits.conf`: the service limits drop-in, installed as `/etc/systemd/system/<unit>.d/50-easydblab-limits.conf` for `cassandra.service`, `cassandra-easy-stress.service`, `k3s.service` and `k3s-agent.service`.  A drop-in for a unit that does not exist yet is harmless, so the K3s drop-ins can be written before the K3s installer creates the units.
- `system.conf`: `[Manager] DefaultLimitNOFILE=1048576` / `DefaultLimitMEMLOCK=infinity` / `DefaultLimitNPROC=infinity` / `DefaultLimitAS=infinity`, installed as `/etc/systemd/system.conf.d/50-easydblab-limits.conf`.
- `limits.conf`: the PAM limits for login sessions (`* - nofile 1048576`, `* - memlock unlimited`, `* - nproc unlimited`, `* - as unlimited`), installed as `/etc/security/limits.d/50-easydblab.conf`.  It replaces `cassandra.conf`.
- `60-easydblab.rules`: one udev rules file: block queue settings and the ENA RPS hook.
- `edl-node-tuning`: one POSIX script with subcommands `apply` (sysfs settings: THP, governor, C-states, RPS on every ENA link), `rps <iface>` (one link, called from udev) and `verify` (reads back every value; exit 1 naming the first mismatch).
- `edl-node-tuning.service`: oneshot, `ExecStart=/usr/local/sbin/edl-node-tuning apply`, `Before=k3s.service k3s-agent.service cassandra.service`, `WantedBy=multi-user.target`.  It restores the sysfs values that no config file persists (THP, governor, C-states).

`NodeSetupService.runSetupScript` copies the directory from the classpath to a temporary local directory and uploads it with `uploadDirectory` beside `setup_instance.sh` (one upload per node, not one per file), before it runs the script.  `setup_instance.sh` installs each file with `install -m`, then `sysctl --system`, `udevadm control --reload && udevadm trigger --subsystem-match=block --subsystem-match=net --action=change`, `systemctl daemon-reexec` (so PID 1 picks up `DefaultLimit*`), `systemctl daemon-reload`, `systemctl enable --now edl-node-tuning.service`, and finally `edl-node-tuning verify`.  A non-zero `verify` prints `ERROR: <setting>: wanted <x>, got <y>`, which `setupFailureReason` already reports.

*Why not heredocs in `setup_instance.sh`:* the codebase already ships node files as classpath resources (`start-k3s-agent.sh` via `installFile`, `install-tailscale-masquerade.sh`).  Separate files can be unit-tested, the sysctl file is parsed by the port-reservation test (D5), and the docs can quote them.
*Why from the classpath, not the workspace:* the files are product configuration, not per-cluster settings an operator edits; `init` stays as it is, and the backup list does not grow.  Nothing reads a repo path at run time.
*Why not `installFile` per file:* seven uploads and seven `sudo mv` round trips per node; one directory upload is one.

### D2. Limits reach the database through systemd, not PAM

PAM `limits.d` applies only to sessions opened through `pam_limits` (ssh, `su`).  systemd services never pass through PAM, so `cassandra.service` today gets systemd's defaults (`LimitNOFILE=1024:524288`, `LimitMEMLOCK=8M` on systemd ≥ 253).  The per-unit drop-in sets the values for each named unit; `DefaultLimit*` covers any service that is not named, including future AMI units.

Pods: containerd is a child of `k3s(-agent).service`, so `LimitMEMLOCK=infinity` on that unit reaches every container (containerd's CRI does not set `RLIMIT_MEMLOCK` in the OCI spec).  `RLIMIT_NOFILE` is the open point: depending on the containerd version K3s 1.35 embeds, CRI may write its own `RLIMIT_NOFILE` into each container's spec instead of inheriting.  Task 1 measures it on a live node before any code.  If pods do not inherit 1048576, the fix is configuration: K3s's containerd config template (`/var/lib/rancher/k3s/agent/etc/containerd/config.toml.tmpl`) with the CRI base runtime spec carrying the rlimits.  That would be one more packaged file; the design does not change.

### D3. `fs.nr_open` and `fs.file-max` are a floor, not a write

systemd (≥ 240) raises both at boot: `fs.nr_open` to 1073741816 and `fs.file-max` to `LONG_MAX`.  Writing `fs.nr_open = 1048576` would lower them by three orders of magnitude.  The sysctl file does not set them; `verify` checks each is ≥ 1048576 and fails the node otherwise.  This meets the issue's intent ("raise to match") without lowering anything.  Owner decision B (2026-11-17): the minimum check is approved.

### D4. `vm.zone_reclaim_mode` and the "missing sudo" bug

`NodeSetupService` runs the whole script as `sudo bash setup_instance.sh`, so the unsudoed `echo 0 > /proc/sys/vm/zone_reclaim_mode` does run as root and probably does apply.  The real defect is that it, and the two `kernel.*` values, are not persisted.  Task 1 confirms which on a live node.  Either way the fix is the same: all three move into the sysctl file.

### D5. Ephemeral port range and reserved ports

`ip_local_port_range = 1024 65535` lets an outbound connection take any port in 1024-65535 as its source port.  A listener that later binds that port (a restart of Mimir on 9009, Cassandra on 9042) then fails with `EADDRINUSE`, because the connecting socket has no `SO_REUSEADDR`.  The NodePort range 30000-32767 sits inside it too.  The setting stays, as the issue asks, and `ip_local_reserved_ports` reserves:

- `30000-32767` (NodePorts, including Hubble UI 31234 and every kit NodePort).
- Every fixed port below 32768 that a host-network listener binds: K3s and the control plane (6443, 6444, 2379-2381, 10248-10260), Cilium and Hubble (4240, 4244, 4245, 9234, 9962-9965), the observability stack on the control node (3000, 3080, 3081, 3100, 3200, 4040, 4317, 4318, 4320, 4321, 5000, 5001, 6831, 7946, 7947, 8080, 8888, 9009, 9095-9098, 9400, 9435, 11211, 13133), and the db-node host services (Cassandra 7000, 7001, 7199, 9042, 9142, the sidecar and the JMX exporter ports).  The implementation takes the exact list from `Constants` and the host-network manifest builders.

The packaged sysctl file holds the literal list.  A unit test parses it and fails when a port that a host-network builder or `Constants` names below 32768 is not reserved, so a new fixed host port cannot be forgotten.  Pod-network listeners are in their own network namespace with their own port range and need no reservation.  A reservation blocks only automatic source-port choice, so an explicit `bind` of a reserved port still works.

### D6. Disk: `noatime`, scheduler and readahead by udev

- `mount_data_disk` mounts with `-o noatime` and writes `defaults,noatime,nofail,x-systemd.device-timeout=10s` to `/etc/fstab`.  On a re-run where `/mnt/db1` is already mounted, it rewrites the fstab line and runs `mount -o remount,noatime /mnt/db1`.  The data-disk requirement pending in `openspec/changes/issue-969` is unchanged; `noatime` is stated in `node-os-tuning`.
- The udev rule sets `queue/scheduler=none` and `queue/read_ahead_kb=4` on `ACTION=="add|change"` for `nvme[0-9]*n[0-9]*` and `xvd[a-z]*` whole disks.  It replaces `blockdev --setra`; the `READAHEAD` variable goes.  The rule also matches the root EBS volume.  That is deliberate (one rule, the same values on every disk) and costs little: the root volume holds binaries and config, not data.

### D7. Memory and THP

THP is sysfs, not sysctl, so `edl-node-tuning apply` sets `enabled=madvise` and `defrag=never`, and the boot unit restores them.

Checked against each supported database:

| Database | Vendor guidance | With `madvise` + defrag `never` |
|---|---|---|
| Cassandra | THP off or `madvise`; `AlwaysPreTouch` in our jvm options | No huge pages unless the JVM asks (`-XX:+UseTransparentHugePages`); no compaction stalls |
| ClickHouse | `madvise` | Matches |
| TiDB / TiKV | `never` | jemalloc does not madvise huge pages by default, so in practice the same as `never` |
| PostgreSQL | `never` or `madvise`; explicit `huge_pages` uses hugetlbfs, not THP | Matches |
| Kafka, Neo4j, Ignite 3, Trino, Presto, Flink (JVM) | no THP requirement | Same as Cassandra |
| memcached, Redis | warn only on `always` | Fine |
| FerrosaDB | none published | Fine |

Defrag `never` also covers regions that call `madvise(MADV_HUGEPAGE)`: they get a huge page only when one is free without compaction, so no thread stalls in direct compaction.  This is the owner's choice from the issue.

Other values: `vm.swappiness = 1`, `vm.dirty_background_bytes = 64 MiB`, `vm.dirty_bytes = 256 MiB` (fixed bytes do not scale with RAM, so a 512 GiB host does not build up gigabytes of dirty pages; setting `*_bytes` zeroes the `*_ratio` pair, which is intended), `vm.max_map_count = 1048575` kept.  Swap: `swapoff --all` stays, and setup comments out any swap line in `/etc/fstab` so a reboot does not bring it back (the AMI has none today; this keeps it so).

Kubelet check: with K3s's default `--protect-kernel-defaults=false`, the kubelet sets `vm.overcommit_memory=1`, `vm.panic_on_oom=0`, `kernel.panic=10`, `kernel.panic_on_oops=1` and the `kernel.keys.*` limits at start.  None of them is in our file, so the two never fight.  The kubelet requires swap off, which matches.

### D8. Network sysctls

Checks:

- **Cilium** (`kubeProxyReplacement=false`, `bpf.hostLegacyRouting=true`, ENI native routing): the agent sets `net.ipv4.conf.*.rp_filter=0` on its interfaces, `net.core.bpf_jit_enable`, `kernel.unprivileged_bpf_disabled`, `kernel.timer_migration` and `net.ipv4.fib_multipath_use_neigh`, and writes `/etc/sysctl.d/99-zzz-override_cilium.conf`.  Our file sets none of these and sorts first (`60-`), so Cilium's file wins on any key it owns.  The file must not set `rp_filter` or `ip_forward`.
- **kube-proxy** (retained, iptables mode, per the networking spec) sets `nf_conntrack_max` (max of 131072 and 32768 per core) and the conntrack TCP timeouts at start.  Conntrack is left to it; the file sets no `nf_conntrack_*` key.  Cilium's own BPF conntrack maps are sized by its `bpf-map-dynamic-size-ratio` and are not touched.
- **`tcp_tw_reuse = 1`** affects only outbound connections in TIME_WAIT and is safe behind NAT and masquerade (unlike `tcp_tw_recycle`, which no longer exists).
- **NodePorts**: D5.
- **`tcp_rmem` / `tcp_wmem`**: only the maximum is raised to 16 MiB; the minimum and default stay at the kernel's values (`4096 131072` / `4096 16384`), so autotuning keeps small sockets small.

**Scope limit (follow-up #1202):** most `net.ipv4.*` and `net.core.somaxconn` are per network namespace, and a new pod namespace starts at kernel defaults, not the host's values.  These settings reach the host network namespace: Cassandra and the stress server on systemd, every host-network pod (the observability backends, Cilium, the OTel collector).  They do not reach a kit database in the pod network.  The global keys (`netdev_max_backlog`, `rmem_max`, `wmem_max`, `rps_sock_flow_entries`) and the RPS masks do reach pods: in ENI native routing, pod traffic arrives on host ENA interfaces.

### D9. CPU governor and C-states: detect, apply or skip

`edl-node-tuning apply`:
- If `/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor` exists and `performance` is in `scaling_available_governors`, write `performance` to every CPU.  Otherwise print and `logger -t edl-node-tuning` one line: `skipped CPU governor: <instance type> exposes no frequency scaling`.
- If `/sys/devices/system/cpu/cpu0/cpuidle/` has states, set `disable=1` on every state whose `latency` is greater than C1's.  Otherwise one skip line in the same form.
- The instance type comes from IMDSv2 when it is reachable; the line says `unknown instance type` when it is not.  A skip never fails setup, and `verify` checks the governor and C-states only where they are exposed.

On EC2 these are exposed only on instance sizes with processor state control (whole-socket and `.metal` sizes of some Intel families).  The defaults (`i4i.xlarge`, `c6id.2xlarge`, `m5d.xlarge`) and all Graviton types expose neither, so the common case is the logged skip.

### D10. IRQ: `irqbalance` and RPS/RFS

- `irqbalance` stays; setup runs `systemctl enable --now irqbalance` and `verify` checks it is active.  If the AMI does not have the package, the change needs an AMI bake (Task 1 checks; see Risks).
- RPS/RFS: `net.core.rps_sock_flow_entries = 32768` in the sysctl file.  `edl-node-tuning rps <iface>` writes the all-CPU mask (built from `/sys/devices/system/cpu/online`, comma-grouped for more than 32 CPUs) to every `queues/rx-*/rps_cpus` and `32768 / <rx queues>` to every `rps_flow_cnt`.  The udev rule runs it on `ACTION=="add", SUBSYSTEM=="net", DRIVERS=="ena"`, so a secondary ENI the Cilium operator attaches later gets it too.  A rename (`eth0` to `ens6`) does not reset queue settings.  `apply` also runs it on every ENA link at setup and at boot.
- Cilium compatibility: RPS runs in `netif_receive_skb` before the tc ingress hook, so Cilium's tc BPF programs run on the steered CPU.  Cilium XDP is not enabled (kube-proxy replacement is off), so nothing runs before RPS.  MTU is not touched (the networking spec requires 9001 on ENA).

### D11. Setup order and re-runs

Setup is the SETUP step of each node's pipeline (`StepPolicy.SETUP`: two attempts, replaceable).  Tuning runs before `mount_data_disk`, so the udev rule is already loaded when the disk is handled, and it all finishes before K3s or Cassandra start, so their units start with the drop-ins in place.  On a re-run of `setup-instances` against a running node, the files are rewritten in place (same names, no duplicates) and sysctl, udev and sysfs values apply at once.  Limits of an already-running Cassandra or K3s change only at their next restart; the docs say so.  `daemon-reexec` does not restart services.

## Risks / Trade-offs

- [Pods may not inherit `RLIMIT_NOFILE` from `k3s-agent.service`] → Task 1 measures it first; the fix is K3s's containerd config template (D2).
- [Pod-network kit databases do not get the per-namespace network values] → stated in the docs, which point to follow-up #1202.
- [`irqbalance` might be missing from the AMI] → Task 1 checks.  If it is missing, installing it at setup would need network access from the node; the right fix is the base AMI, which adds `requires-ami-bake` to the issue.
- [`ip_local_reserved_ports` drifts from the real host ports] → the unit test in D5.
- [`daemon-reexec` on a re-run against a live node] → it keeps every unit running; it is the documented way to load `system.conf.d`.
- [The udev readahead also applies to the root volume] → accepted (D6).
- [THP `defrag=never` means fewer huge pages for a JVM that asks for them] → owner's choice; documented.
- [The `verify` step can fail a node whose kernel lacks a key] → the Ubuntu 26.04 kernel has every key used; an absent key fails setup and names it, which is the fail-fast the issue wants.

## Migration Plan

None.  Clusters are ephemeral; a new `up` picks it up.  Old file names (`60-cassandra.conf`, `limits.d/cassandra.conf`) are removed by setup when present.

## Owner Decisions (2026-11-17, spec approved)

- **A.** The per-namespace network values for kit databases in the pod network (kubelet `--allowed-unsafe-sysctls` plus a `securityContext.sysctls` block per kit) are out of this change.  They are follow-up #1202.
- **B.** `fs.nr_open` / `fs.file-max` get the minimum check of D3, not a write.
- **C.** #1087's second criterion (a 6-node kind cluster on one host) is dropped.  #1087 closes with this change on the inotify values alone.
- **D.** The tuning files are pushed from the classpath at setup time (D1), not extracted into the workspace at `init`.
