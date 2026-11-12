## Why

The base AMI tells systemd-networkd that the primary NIC is the interface named `ens5`.  The NIC name comes from the PCI slot, and the slot differs by instance family: c7gd, m7gd, r7gd and c6id name it `ens5`, c8gd and m8gd (Graviton4) name it `ens52`, and i7i names it `enp39s0`.  When the primary is not `ens5`, it misses `05-cilium-eni-primary.network` (`Name=ens5`) and falls to `06-cilium-eni-unmanaged.network` (`Driver=ena`, `Unmanaged=yes`).  It gets no DHCP, cannot reach IMDS, the instance status check is impaired, and `up` fails with "SSH did not come up within 600s".  A stop and start does not fix it.  Flannel and Cilium clusters are both affected.

A second fault follows.  Cilium's `devices=ens+` and `egressMasqueradeInterfaces=ens+`, and the Tailscale masquerade rule `oifname "ens*"`, do not match `enp39s0`.  Once i7i has network, Cilium has no device and egress is not masqueraded.

The owner decided to identify the primary ENI by the MAC that IMDS reports as the primary (`meta-data/mac`), taken from cloud-init's own IMDS data, never by name or PCI path.

## What Changes

- **Boot-time primary match.**  A new script `packer/base/install/edl-primary-eni-network`, installed as `/usr/local/sbin/edl-primary-eni-network`, reads `.ds.meta_data.mac` from `/run/cloud-init/instance-data.json` with `jq`, validates and lowercases it, and writes `/run/systemd/network/05-cilium-eni-primary.network` with `[Match] PermanentMACAddress=<mac>` and `[Network] DHCP=ipv4`.  On any failure it prints one clear line to the console and exits 1; it never guesses.  It refuses to run while `/etc/systemd/network/05-cilium-eni-primary.network` exists, because that file would shadow the `/run` one.
- **New unit** `edl-primary-eni-network.service` (`Type=oneshot`, `DefaultDependencies=no`, `Wants=` and `After=cloud-init-local.service`, `Before=network-pre.target systemd-networkd.service`, `StandardOutput=journal+console`, `WantedBy=sysinit.target`).
- **`packer/base/install/configure_cilium_eni_networkd.sh`**: stops writing the `/etc` 05 file and removes any that exists; writes and enables the unit; keeps `06-cilium-eni-unmanaged.network` and `90-easydblab-no-network-hotplug.cfg` unchanged; runs the script once on the bake instance and, after `networkctl reload`, checks that networkd selected the `/run` 05 file for the primary link (skipped where there is no ENA link, as in the Docker test).  Its self-checks cover the unit and the `Driver=ena` rule.  The header drops the fixed `ens5`/`ens6` names and corrects the claim that networkd configures a hotplugged link under its pre-rename name: networkd waits for the rename, and `Driver=ena` is still the right match.
- **New test** `packer/base/install/edl-primary-eni-network.test.sh`: Docker-free, stubs the paths through environment variables.
- **`packer/base/base.pkr.hcl`**: a `file` provisioner uploads the script; the comment on the networkd provisioner describes the MAC match.
- **`packer/docker-compose.yml`**: the `test-base` service makes the script available to `configure_cilium_eni_networkd.sh` so `testPackerBase` exercises the install path.
- **`build.gradle.kts`**: a `testBasePrimaryEniNetwork` task runs the new test and joins the `testScripts` aggregate.
- **`Constants.Cilium.NODE_FIX_FILES`**: the unit, the script, the 06 file and the cloud.cfg drop-in (the `/etc` 05 file is gone).  **`CiliumNodeImageCheck`** KDoc updated to match.
- **`services/CiliumService.kt`**: `devices=en+` and `egressMasqueradeInterfaces=en+`; comments no longer name `ens5`/`ens6` as fixed names.
- **`services/install-tailscale-masquerade.sh`**: `oifname "en*"`; comments updated.
- **`services/metrics/StreamMetric.kt`**: `NETWORK_DEVICES` becomes `device=~"en.*|eth.*"`.
- **Dashboards** `dashboards/infrastructure/system-overview.json` and `dashboards/infrastructure/system-ab-comparison.json`: network panel filters `ens.*` become `en.*` (through the `dashboard-editor` agent).
- **Specs**: networking, live-stream-metrics and observability deltas below.
- **Docs**: `docs/user-guide/networking.md` (`en+`, MAC match), `docs/integrations/metrics-stream.md` (`en*`), `packer/README.md` (MAC match, no fixed names), `packer/CLAUDE.md` (the new test and where the 05 file lives).
- **Rebake** the base AMI and the cassandra AMI on top of it, and prove both on real instances.

## Capabilities

### New Capabilities
None.

### Modified Capabilities
- `networking`: the base image manages the primary ENI by its IMDS MAC; the secondary-ENI scenario and Tailscale rule no longer use fixed names; the Cilium node image check names the new unit.
- `live-stream-metrics`: network samples sum `en*` and `eth*`.
- `observability`: network panels match `en.*` and `eth.*`.

## Out of scope

- Disabling Cilium, ENI mode, or the networkd and cloud-init drop-ins on any type.
- An instance-type allowlist or denylist.

## Impact

- Every node of every cluster boots through the new unit.  A fault in it takes every node off the network, so the AWS proof covers five families, both CNIs, runtime secondaries, reboots and the failure path.
- Both AMIs must be rebaked; nodes on an older AMI fail `CiliumNodeImageCheck` on a Cilium cluster.
- Open PR 1031 also edits `CiliumService.kt`; a merge conflict is possible.
