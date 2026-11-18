## Why

easy-db-lab clusters exist so that test results transfer to real, production database clusters.  Today every node runs close to kernel and AMI defaults: `setup_instance.sh` sets only `vm.max_map_count`, `zone_reclaim_mode`, swap off, a 4 KiB readahead and a PAM `limits.d` file.  Two of those probably do not do what they claim.  The `limits.d` file reaches PAM sessions only, so `cassandra.service` and every other systemd-run database get systemd's default limits (memlock 8 MiB, so `mlockall` fails).  The `kernel.*` perf sysctls and the readahead are set at run time and lost on a reboot.  The owner chose the full production tuning set (#1199, option A), and it absorbs #1087 (inotify limits; its kind-cluster criterion is dropped by owner decision).  Network values inside kit pod namespaces are follow-up #1202.

## What Changes

- **Packaged node-tuning files.**  The distribution carries a small set of tuning files as classpath resources: one sysctl drop-in, one systemd service limits drop-in, one systemd manager drop-in (`DefaultLimit*`), one PAM limits file, one udev rule, and a boot-time tuning script with its oneshot unit.  `up` and `setup-instances` stage them on every node (db, app, control) before `setup_instance.sh` runs; `setup_instance.sh` installs and applies them.  No AMI bake.
- **Limits.**  `LimitNOFILE=1048576`, `LimitMEMLOCK=infinity`, `LimitNPROC=infinity`, `LimitAS=infinity` for `cassandra.service`, `k3s.service` and `k3s-agent.service` (every kit database and every stress run, a K8s Job, runs in a pod under K3s's containerd), and the same values as systemd's `DefaultLimit*`.  The PAM file gives interactive sessions the same values.
- **inotify** (closes #1087): `max_user_instances = 8192`, `max_user_watches = 1048576`.
- **Memory:** THP `madvise`, defrag `never`; `vm.max_map_count` kept; `vm.swappiness = 1`; fixed `vm.dirty_background_bytes` / `vm.dirty_bytes`; `vm.zone_reclaim_mode = 0` and the perf `kernel.*` sysctls persisted.
- **Network:** `somaxconn` and `tcp_max_syn_backlog` 65535, `netdev_max_backlog` 250000, 16 MiB socket buffers with matching `tcp_rmem`/`tcp_wmem`, keepalive 60 s / 10 s / 3 probes, `tcp_slow_start_after_idle = 0`, `tcp_tw_reuse = 1`, `ip_local_port_range = 1024 65535` with the NodePort range and the cluster's fixed listener ports reserved in `ip_local_reserved_ports`.
- **Disk:** the data disk is mounted `noatime`; NVMe scheduler `none` and 4 KiB readahead through the udev rule (replaces the run-time `blockdev --setra`); `fs.aio-max-nr = 1048576`.
- **CPU:** `kernel.numa_balancing = 0`; `performance` governor and shallow C-states where the instance exposes them, otherwise a logged skip.
- **IRQ:** `irqbalance` kept and enabled; RPS/RFS on every physical interface, including the secondary ENIs Cilium attaches at run time.
- Replaces `/etc/sysctl.d/60-cassandra.conf` and `/etc/security/limits.d/cassandra.conf`.
- Every value survives a reboot.  A tuning step that fails fails the node's setup.
- **Docs:** a new user-guide page lists every value and why.

## Capabilities

### New Capabilities
- `node-os-tuning`: the kernel, limits, memory, network, disk, CPU and IRQ settings every cluster node runs with, how they persist across a reboot, and how a setting the instance cannot take is reported.

### Modified Capabilities
None.  The data-disk mount requirement (pending in `openspec/changes/issue-969`) is not changed; the `noatime` mount option is stated in `node-os-tuning` so the two changes do not collide.

## Impact

- `src/main/resources/com/rustyrazorblade/easydblab/commands/setup_instance.sh`: installs and applies the tuning files; the mount gets `noatime`; the old heredocs, the unsudoed echo and `blockdev --setra` go.
- New classpath resources under `src/main/resources/com/rustyrazorblade/easydblab/commands/node-tuning/`.
- `services/NodeSetupService.kt`: stages the tuning resources on the node before the setup script runs.
- New shell unit tests and their Gradle tasks in the `testScripts` aggregate; a Kotlin test that every fixed listener port below 32768 is reserved.
- Docs: `docs/user-guide/node-tuning.md`, `docs/SUMMARY.md`; package `CLAUDE.md` notes.
- Real AWS test (`requires-aws`).  No `packer/` change, no AMI bake.
