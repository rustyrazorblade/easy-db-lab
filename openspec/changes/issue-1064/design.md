## Context

The base AMI bakes three files for Cilium ENI mode (`configure_cilium_eni_networkd.sh`):

- `/etc/systemd/network/05-cilium-eni-primary.network`: `Name=ens5`, `DHCP=ipv4`.
- `/etc/systemd/network/06-cilium-eni-unmanaged.network`: `Driver=ena`, `Unmanaged=yes`.
- `/etc/cloud/cloud.cfg.d/90-easydblab-no-network-hotplug.cfg`: no netplan re-render on a NIC hotplug.

networkd picks the lexically first matching `.network` file per link across `/etc`, `/run` and `/usr/lib`, so 05 and 06 win over cloud-init's `10-netplan-*`.  The primary NIC's name depends on its PCI slot: `ens5` on c7gd and c6id, `ens52` on c8gd and m8gd, `enp39s0` on i7i.  On the last three the primary falls to 06 and gets no network.

The image is stacked: the cassandra AMI is baked on a real instance launched from the base AMI.  Anything the base AMI writes about the bake instance's own hardware ends up in the cassandra AMI.

## Goals / Non-Goals

**Goals:**
- The primary ENI is managed on every instance family, under any name, with any number of ENIs attached.
- Every other ENA link stays unmanaged, at first boot, at runtime attach and at reboot.
- No MAC from a bake instance reaches a derived AMI.
- A missing MAC fails loudly and never guesses.
- Cilium, Tailscale, the metrics stream and the dashboards match the primary under any `en*` name.

**Non-Goals:**
- Disabling Cilium, ENI mode, or the networkd and cloud-init drop-ins on any type.
- An instance-type allowlist or denylist.

## Decisions

### D1. Match the primary by the IMDS primary MAC, read from cloud-init's data

The mechanism is the owner's Decision.  `cloud-init-local` runs before the network.  On a new instance ID it reads IMDS over a short-lived lease; on the same instance it uses its cache.  It writes `/run/cloud-init/instance-data.json`.  The boot script reads the primary MAC from `.ds."meta-data".mac` in that file with `jq`.  It uses one path and no fallback.  This adds no second IMDS client before the network is up.

The script validates the MAC against six colon-separated hex pairs, lowercases it, and writes:

```
[Match]
PermanentMACAddress=<mac>

[Network]
DHCP=ipv4
```

`PermanentMACAddress=` is used, not `MACAddress=`, so the match does not depend on a MAC that something sets at runtime.

`06-cilium-eni-unmanaged.network` and the no-hotplug cloud.cfg drop-in stay as they are.  Every ENA link whose MAC is not the primary's falls to 06.

Precedent: EKS on AL2023 (nodeadm's udev net manager) and amazon-ec2-net-utils identify the primary the same way.

### D2. The unit runs before networkd, after cloud-init-local

`/etc/systemd/system/edl-primary-eni-network.service`:

- `Type=oneshot`, `DefaultDependencies=no`
- `Wants=cloud-init-local.service`, `After=cloud-init-local.service`
- `Before=network-pre.target systemd-networkd.service`
- `StandardOutput=journal+console`, `StandardError=journal+console`
- `ExecStart=/usr/local/sbin/edl-primary-eni-network`
- `[Install] WantedBy=sysinit.target`

`Wants=` makes the order hold even if cloud-init-local is not otherwise pulled into the transaction on cloud-init 25.x (Ubuntu 26.04).  An early AWS step reboots a node and checks the order on a cached boot.

### D3. The 05 file goes to `/run`, not `/etc` (deviation from the Decision text)

The Decision text names `/etc/systemd/network/05-cilium-eni-primary.network`.  This design writes `/run/systemd/network/05-cilium-eni-primary.network` instead, on purpose.

- networkd sorts by file name across `/etc`, `/run` and `/usr/lib`, so a 05 file in `/run` still wins over 06 in `/etc` and `10-netplan-*` in `/run`.
- `/run` is a tmpfs.  The file is written fresh on every boot and can never be captured into an AMI.  The cassandra-AMI leak is gone by construction, not by a cleanup step.
- A file of the same name in `/etc` overrides the one in `/run`.  The bake therefore removes any `/etc` 05 file, and the script exits 1 with a clear message if one exists.

### D4. Failure is loud and never guesses

On a missing or unreadable `instance-data.json`, a null or empty `.ds."meta-data".mac`, a malformed MAC, or a shadowing `/etc` 05 file, the script prints one line naming the cause to the console (`journal+console`) and exits 1.  It writes no 05 file, so it never leaves a stale or wrong one in place.  It never picks "the only ENA link" or any other heuristic.  A stale `/run` 05 file from earlier in the same boot is replaced atomically (write to a temp file, then rename).

### D5. The bake proves the path on a real instance

`configure_cilium_eni_networkd.sh` installs the script, writes and enables the unit, and removes any `/etc` 05 file.  On the bake instance it then runs the script once, runs `networkctl reload`, and checks that `networkctl status` for the primary link names `/run/systemd/network/05-cilium-eni-primary.network` as its network file.  This keeps the bake on the network after reload and proves the `jq` path against real cloud-init data.  In Docker (`testPackerBase`) there is no ENA link and no cloud-init data, so this step is skipped there; the static self-checks still run.

### D6. Name filters become `en*`

`ens+` / `ens*` / `ens.*` become `en+` / `en*` / `en.*` in Cilium `devices` and `egressMasqueradeInterfaces`, the Tailscale nft rule, `StreamMetric`'s network devices, and the system-overview and system-ab-comparison dashboards.  No other interface on these hosts starts with `en`: Cilium's are `cilium_*` and `lxc*`, Tailscale's is `tailscale0`, Flannel's are `flannel.1` and `cni0`.

### D7. Image check and tests

`Constants.Cilium.NODE_FIX_FILES` lists the unit, the script, the 06 file and the cloud.cfg drop-in.  `CiliumNodeImageCheck` checks them unchanged; only its KDoc changes.

`edl-primary-eni-network.test.sh` is Docker-free.  The script takes its input file, output directory and `/etc` directory from environment variables with the real paths as defaults, so the test points them at a temp dir.  It is registered as a Gradle task in the `testScripts` aggregate.  `testPackerBase` runs `configure_cilium_eni_networkd.sh`, whose self-checks fail on a missing or wrong unit or 06 file.

## Alternatives Considered

- **Rely on cloud-init's netplan for the primary, with `apply_full_imds_network_config: false` and the unmanaged file sorted after `10-netplan-*`.**  Rejected.  In reduced mode cloud-init renders a "fallback NIC", not device index 0.  With Cilium secondaries attached at reboot, that NIC can be a secondary.
- **Count ENA links at first boot and record the only one.**  Rejected.  The cassandra AMI is baked on a real instance from the base AMI, so the recorded MAC leaks into it and every node has no network.  Counting also races with the ENA probe.
- **PCI `Path=` match, or a wider name list (`ens5 ens52 enp39s0`).**  Rejected.  Both encode the slot number per family, so the next family breaks the same way.
- **Write the 05 file to `/etc/systemd/network/`, as the Decision text said.**  Rejected for `/run`; the owner's Decision text is deviated from on purpose.  A file in `/etc` persists into any AMI baked from the instance, so the bake instance's MAC would reach the cassandra AMI unless a cleanup step ran at exactly the right moment.  `/run` is a tmpfs, so the leak cannot happen, and the file still sorts ahead of 06 and `10-netplan-*`.
- **Cross-check the instance ID against DMI `board_asset_tag` before trusting cloud-init's data.**  Rejected.  It breaks Xen ENA types such as i3, which do not carry the instance ID there.  Staleness is already covered: the 05 file lives on the `/run` tmpfs, and cloud-init refreshes its own data per instance ID.

## Risks / Trade-offs

- **Blast radius is every node.**  The unit runs on every node of every cluster, both CNIs.  A fault takes the node off the network.  Mitigation: the bake runs the script on a real instance, and the AWS proof covers five families, Flannel and Cilium, runtime secondaries, reboots, and the failure path.
- **cloud-init ordering on 26.04.**  If `instance-data.json` is not in `/run` before `network-pre.target`, the script fails and the node has no primary network.  Mitigation: `Wants=` and `After=cloud-init-local.service`; an early AWS step reboots a node and checks the order.
- **`/etc` shadowing `/run`.**  An `/etc` 05 file overrides the `/run` one.  Mitigation: the bake removes it and the script refuses to run while it exists.
- **Xen ENA types.**  No DMI check is used, so Xen types such as i3 are not broken by one.
- **Merge conflict with open PR 1031**, which also edits `CiliumService.kt`.

## AWS verification

These are confirmed on real instances, not in Docker:

- **Ordering on a cached reboot:** `systemd-analyze critical-chain` / `journalctl -b` show `cloud-init-local.service` finished before `edl-primary-eni-network.service`, and that before `systemd-networkd.service`, on a reboot that uses cloud-init's cache.
- **Which MAC key exists:** verified at bake time on 26.04 (cloud-init 26.2), arm64 and x86: the key is `.ds."meta-data".mac`.  `.ds` holds only `_doc`, `_metadata_api_version`, `dynamic` and `meta-data`; there is no `meta_data` key.
- **The MAC matches the link:** `ethtool -P <primary>` equals IMDS `meta-data/mac` on each family.
- **05 survives `netplan generate`:** after `netplan generate` and `networkctl reload`, the primary still uses `/run/systemd/network/05-cilium-eni-primary.network`.
- The names secondaries get on 8th-gen and i7i, and whether the arm64 bake type (`c8g.2xlarge`) is itself affected.

## Migration Plan

Rebake the base AMI, then the cassandra AMI on it.  Nodes on an older AMI fail `CiliumNodeImageCheck` on a Cilium cluster and must be relaunched from the new AMI.

## Open Questions

None.
