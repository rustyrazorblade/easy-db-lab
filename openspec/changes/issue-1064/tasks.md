## 1. Boot script and its Docker-free test

- [x] 1.1 Write `packer/base/install/edl-primary-eni-network.test.sh` (POSIX, Docker-free, temp dir per case, input file, `/run` and `/etc` network dirs passed by environment variable) with cases: valid MAC writes `[Match] PermanentMACAddress=<mac>` and `[Network] DHCP=ipv4`; uppercase MAC is lowercased; missing `instance-data.json` exits 1 with one console line and no 05 file; `.ds."meta-data".mac` null or empty exits 1 with no 05 file; malformed MAC exits 1 with no 05 file; an `/etc` 05 file present exits 1 naming it; a stale `/run` 05 file with another MAC is replaced by the new one
- [x] 1.2 Write `packer/base/install/edl-primary-eni-network`: read `.ds."meta-data".mac` with `jq` (one path, no fallback), validate six hex pairs, lowercase, refuse while an `/etc` 05 file exists, write the `/run` 05 file through a temp file and rename; on any failure print one line naming the cause and exit 1; make the test pass
- [x] 1.3 Register `testBasePrimaryEniNetwork` in `build.gradle.kts` (`Exec`, `bash packer/base/install/edl-primary-eni-network.test.sh`) and add it to the `testScripts` aggregate

## 2. Base image setup

- [x] 2.1 `packer/base/install/configure_cilium_eni_networkd.sh`: stop writing `/etc/systemd/network/05-cilium-eni-primary.network` and remove it if present; install the script to `/usr/local/sbin/edl-primary-eni-network` (0755); write `/etc/systemd/system/edl-primary-eni-network.service` (`Type=oneshot`, `DefaultDependencies=no`, `Wants=` and `After=cloud-init-local.service`, `Before=network-pre.target systemd-networkd.service`, `StandardOutput=journal+console`, `StandardError=journal+console`, `WantedBy=sysinit.target`) and enable it; keep 06 and the cloud.cfg drop-in unchanged
- [x] 2.2 Same script: on a host with an ENA link, run the script once, run `networkctl reload`, and fail unless `networkctl status` for the primary link names `/run/systemd/network/05-cilium-eni-primary.network`; skip this where there is no ENA link (Docker)
- [x] 2.3 Same script: self-checks fail if the unit is missing, not enabled, or lacks its ordering lines; if 06 lacks `Driver=ena` or `Unmanaged=yes`; if the script is missing or not executable; or if an `/etc` 05 file exists
- [x] 2.4 Same script: rewrite the header without fixed `ens5`/`ens6` names, describe the MAC match and the `/run` location, and correct the claim that networkd configures a hotplugged link under its pre-rename name (networkd waits for the rename; `Driver=ena` is still the right match)
- [x] 2.5 `packer/base/base.pkr.hcl`: add a `file` provisioner that uploads `install/edl-primary-eni-network` before the networkd provisioner; update that provisioner's comment
- [x] 2.6 `packer/docker-compose.yml`: make the script available to `configure_cilium_eni_networkd.sh` in the `test-base` service the same way the bake does
- [x] 2.7 Run `./gradlew testPackerBase` and `./gradlew testScripts` (in a subagent) and confirm both pass, and that removing the unit or the `Driver=ena` line makes `testPackerBase` fail

## 3. CLI changes

- [x] 3.1 `Constants.Cilium.NODE_FIX_FILES`: the unit, the script, the 06 file and the cloud.cfg drop-in; drop the `/etc` 05 file
- [x] 3.2 `services/CiliumNodeImageCheck.kt`: update the KDoc for the new file set
- [x] 3.3 `services/CiliumService.kt`: `devices=en+` and `egressMasqueradeInterfaces=en+`; comments no longer name `ens5`/`ens6` as fixed names; update the test that asserts the flags
- [x] 3.4 `services/install-tailscale-masquerade.sh`: `oifname "en*"`; update comments; update any test that asserts the rule
- [x] 3.5 `services/metrics/StreamMetric.kt`: `NETWORK_DEVICES = device=~"en.*|eth.*"`; update its test to cover `ens5` and `enp39s0`
- [x] 3.6 Run `./gradlew test` and `detekt` (in a subagent) and confirm both pass

## 4. Dashboards

- [ ] 4.1 Through the `dashboard-editor` agent: change the network panel filters in `dashboards/infrastructure/system-overview.json` and `dashboards/infrastructure/system-ab-comparison.json` from `ens.*` to `en.*`, deploy, and read back from Grafana

## 5. Docs

- [x] 5.1 `docs/user-guide/networking.md`: Cilium devices `en+`; the primary ENI is matched by its IMDS MAC
- [x] 5.2 `docs/integrations/metrics-stream.md`: `en*` and `eth*`
- [x] 5.3 `packer/README.md`: MAC match, the 05 file under `/run`, no fixed `ens5`/`ens6` names
- [x] 5.4 `packer/CLAUDE.md`: the new Docker-free test and where the 05 file lives

## 6. AWS proof

- [ ] 6.1 `./gradlew installDist`; bake the base AMI; confirm the bake's reload check passed in the build log
- [ ] 6.2 Bake the cassandra AMI on the new base AMI
- [ ] 6.3 Early ordering check: boot one node, reboot it, and confirm from `journalctl -b` / `systemd-analyze critical-chain` that `cloud-init-local` finished before the unit and the unit before `systemd-networkd` on the cached boot; confirm `.ds."meta-data".mac` exists, `ethtool -P` on the primary equals IMDS `meta-data/mac`, and the 05 file survives `netplan generate` plus `networkctl reload`
- [ ] 6.4 Cilium clusters on c8gd, m8gd, c7gd, i7i and c6id: every node gets DHCP on its primary, one default route, reaches IMDS, `up` completes; `CiliumNodeImageCheck` passes
- [ ] 6.5 Flannel clusters on an 8th-gen type and on i7i: the primary is managed and no primary ENA link is unmanaged
- [ ] 6.6 Force a runtime secondary ENI (more pods than one ENI holds) on c8gd, i7i and c7gd: the secondary is `unmanaged` in `networkctl`, has no OS address and no default route; the node stays `Ready` with IMDS, egress and kubelet-to-apiserver working
- [ ] 6.7 Reboot a node with a secondary attached: the primary is managed again and the secondary stays unmanaged
- [ ] 6.8 Cilium on i7i: `devices` and `egressMasqueradeInterfaces` match `enp39s0` and pod egress works; Tailscale on that cluster reaches db nodes over the tailnet
- [ ] 6.9 Failure path: on a test node, make `.ds."meta-data".mac` unavailable to the script (for example, run it against a copy without the key) and confirm the one-line console message, exit 1 and no 05 file
- [ ] 6.10 `CiliumNodeImageCheck` fails on a node from the previous base AMI, naming the unit
- [ ] 6.11 Tear down every cluster created for the proof
