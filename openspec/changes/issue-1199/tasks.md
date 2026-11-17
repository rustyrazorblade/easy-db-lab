## 1. Confirm the current behaviour on a real cluster (before any code)

- [ ] 1.1 From `main`, provision `clusters/os-tuning-before` (`init` with `--tag embark_offhours=off`, 1 db, 1 app, 1 control, default AWS profile `easy-db-lab` through the default easy-db-lab profile, never `EASY_DB_LAB_PROFILE`), and record the cluster name and workspace in the state file.
- [ ] 1.2 Bug 1: start Cassandra; read `/proc/$(cassandra-pid)/limits` and Cassandra's log for the `mlockall` / "Unable to lock JVM memory" line.  Record nofile and memlock.
- [ ] 1.3 Bug 2: read `vm.zone_reclaim_mode`, `kernel.perf_event_paranoid` and `kernel.kptr_restrict`; reboot the db node; read them again.  Record whether the echo applied and whether the values persisted.
- [ ] 1.4 Read the limits of a process in a kit pod (install and start one db kit, e.g. ClickHouse); read the `k3s-agent` unit's limits (`systemctl show k3s-agent -p LimitNOFILE -p LimitMEMLOCK`) and containerd's.  Record whether pods inherit `RLIMIT_NOFILE` (design D2).
- [ ] 1.5 Check `irqbalance` is installed and active; record `fs.nr_open`, `fs.file-max`, THP, the governor and cpuidle presence on each node type.
- [ ] 1.6 `down` the cluster; record the results in the state file and on the issue.  If 1.4 shows pods do not inherit the limit, add the containerd template file to section 2 before you start it.

## 2. Packaged tuning files

- [ ] 2.1 Add `commands/node-tuning/60-easydblab.conf` (every sysctl in the spec, `ip_local_reserved_ports` per design D5, no `nr_open` / `file-max` / `rp_filter` / `ip_forward` / `nf_conntrack_*` key).
- [ ] 2.2 Add `easydblab-limits.conf` (service drop-in), `system.conf` (`DefaultLimit*`), `limits.conf` (PAM).
- [ ] 2.3 Add `60-easydblab.rules` (block scheduler and readahead; ENA `add` runs `edl-node-tuning rps`).
- [ ] 2.4 Add `edl-node-tuning` (`apply`, `rps`, `verify`) and `edl-node-tuning.service`.
- [ ] 2.5 Shell unit test `edl-node-tuning.test.sh` (Docker-free; sysfs root and IMDS stubbed through environment variables): RPS mask for 2, 8, 33 and 96 CPUs; flow count per queue; governor applied when exposed and skip line otherwise; C-states deeper than C1 disabled; `verify` exits 1 naming the setting on each kind of mismatch.  Gradle task in `testScripts`.

## 3. Setup wiring

- [ ] 3.1 `NodeSetupService`: copy `node-tuning/` from the classpath to a temp dir and upload it beside `setup_instance.sh` in one `uploadDirectory`; unit test that a missing resource fails setup naming it.
- [ ] 3.2 `setup_instance.sh`: install the files, remove the old `60-cassandra.conf` and `limits.d/cassandra.conf`, `sysctl --system`, udev reload and trigger, `daemon-reexec`, `daemon-reload`, enable `edl-node-tuning.service` and `irqbalance`, comment out fstab swap, run `verify`; drop the heredocs, the echo, `READAHEAD` and `blockdev --setra`.
- [ ] 3.3 `mount_data_disk`: `noatime` on mount and in fstab; remount on a re-run.  Extend `setup-instance-data-disk.test.sh` for both paths.
- [ ] 3.4 Unit test: every fixed listener port below 32768 that `Constants` or a host-network manifest builder names is in the packaged `ip_local_reserved_ports`, and 30000-32767 is reserved.

## 4. Docs

- [ ] 4.1 New `docs/user-guide/node-tuning.md`: every value and why, the units the limits drop-in covers, the reboot persistence, the governor and C-state skip, the pod-namespace limit (design D8), and that a running service picks up new limits only at its next restart.  Link it in `docs/SUMMARY.md`.
- [ ] 4.2 Update `services/CLAUDE.md` (node setup stages `node-tuning/`) and `configuration/CLAUDE.md` (a new fixed host port must be added to the reserved list).

## 5. Verify on a real cluster after the fix

- [ ] 5.1 `./gradlew installDist`; provision `clusters/os-tuning-after` (same shape and tags as 1.1); record it in the state file.
- [ ] 5.2 On every db, app and control node: `edl-node-tuning verify` passes, and the evidence is captured with `sysctl` for every key, THP sysfs, `cat /sys/block/*/queue/{scheduler,read_ahead_kb}`, `findmnt -no OPTIONS /mnt/db1`, every ENA `rps_cpus` / `rps_flow_cnt`, `systemctl is-active irqbalance`, and the governor or the skip line.
- [ ] 5.3 `/proc/<cassandra pid>/limits` shows nofile 1048576 and memlock unlimited, and Cassandra logs no `mlockall` failure; a kit pod process shows the same (bugs 1 and 2 gone).
- [ ] 5.4 NodePorts and host-network listeners: Hubble UI on 31234, a kit NodePort, Grafana, Mimir, Loki all answer; under load from a stress run, no outbound source port falls in the reserved set (`ss -tn`).
- [ ] 5.5 Reboot one db, one app and the control node; after each returns, `verify` passes, the K3s node is Ready, and 5.2's evidence is unchanged.  Confirm a secondary ENI that Cilium attached has the RPS mask.
- [ ] 5.6 Re-run `setup-instances` on the live cluster; it succeeds with no duplicated config.
- [ ] 5.7 `down` the cluster; put the commands, output and results in the PR.
