## Overrides existing behavior

### networking: Pod-network datapath (Cilium ENI native routing, selectable)
**Currently:** Scenario "OS leaves Cilium's secondary ENIs unmanaged": the interface appears "first as `eth0`, then renamed to `ens6`"; 06 "matches the interface by `Driver=ena` under either name"; "the `05-cilium-eni-primary.network` drop-in keeps the primary ENI (`ens5`) OS-managed via DHCP"; "Cilium's devices are pinned to `ens+`".
**This change:** No fixed names.  The secondary is matched by `Driver=ena` once udev has renamed it.  The primary is kept managed by `/run/systemd/network/05-cilium-eni-primary.network`, which matches its IMDS MAC by `PermanentMACAddress=`.  Cilium's `devices` and `egressMasqueradeInterfaces` are `en+` (stated in the requirement text).  A new scenario, "Cilium devices match a primary not named ens5", covers i7i.

### networking: Tailscale subnet routing to db nodes works on a Cilium cluster
**Currently:** The chain masquerades Tailscale-forwarded packets "leaving an `ens*` interface"; scenario "db nodes reachable over the tailnet": "the forwarded packet left `ens5`".
**This change:** `en*`; the scenario says the control node's primary ENI.  A new scenario covers i7i (`enp39s0`).

### live-stream-metrics: Per-host system metrics
**Currently:** "`node.network_receive` and `node.network_transmit` SHALL sum the host's `ens*` and `eth*` interfaces ... (the dashboard filter `device=~"ens.*|eth.*"`)"; scenario "Several interfaces give one sample" uses `ens5` and `ens6`.
**This change:** `en*` and `eth*`, filter `device=~"en.*|eth.*"`; the scenario uses `ens5` and `enp39s0`.

### observability: Grafana Dashboards
**Currently:** Scenario "Disk and network panels exclude virtual devices": "Network panels match only `ens.*` and `eth.*` interfaces".
**This change:** `en.*` and `eth.*`.  The rest of the requirement is unchanged.

### networking (new): Base image manages the primary ENI by its IMDS MAC
**Currently:** Not a requirement.  The base AMI writes `/etc/systemd/network/05-cilium-eni-primary.network` with `Name=ens5`.
**This change:** The `/etc` 05 file is removed.  A boot unit writes the 05 file under `/run` from cloud-init's IMDS MAC, and fails loudly if it cannot.

### networking (new): Cilium nodes must come from an AMI carrying the node fixes
**Currently:** Not a requirement; `CiliumNodeImageCheck` checks the `/etc` 05 file, 06 and the cloud.cfg drop-in.
**This change:** The check covers the unit, the script, 06 and the cloud.cfg drop-in.

## Conflicts with other in-flight changes

None found.  The only other open change, `issue-1048`, modifies the networking requirement "Cluster rule changes are reported once" and adds "The K3s pod and service ranges hold 1,000+ nodes and never overlap the VPC".  Neither is touched here.  It does not modify the `observability` or `live-stream-metrics` capabilities.  Open PR 1031 edits `CiliumService.kt`; that is a possible code conflict, not a spec conflict.
