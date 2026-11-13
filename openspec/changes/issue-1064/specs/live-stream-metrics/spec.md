## MODIFIED Requirements

### Requirement: Per-host system metrics

Each `node.*` name SHALL publish one sample per reporting host, for every host that reports: db, app, control and EMR hosts.  The `entity` SHALL be the host's alias, its OS hostname.  A series with no host name SHALL be skipped.

`node.disk_read` and `node.disk_write` SHALL sum the host's whole disks.  Loop devices and partitions SHALL be left out (the dashboard filter `device!~"loop.*|.*p[0-9]+"`).

`node.network_receive` and `node.network_transmit` SHALL sum the host's `en*` and `eth*` interfaces, so the primary ENI counts under any name the instance family gives it (`ens5`, `ens52`, `enp39s0`).  `lo` and the Cilium interfaces SHALL be left out (the dashboard filter `device=~"en.*|eth.*"`).

`node.filesystem_used` SHALL cover only the data mount, `/mnt/db1`.

#### Scenario: Several disks give one sample
- **WHEN** a host has several whole disks, some partitions and a loop device
- **THEN** `node.disk_read` and `node.disk_write` each publish one sample for that host
- **AND** each value is the sum across the whole disks only

#### Scenario: Several interfaces give one sample
- **WHEN** a host has `ens5`, `enp39s0`, `lo` and Cilium interfaces
- **THEN** `node.network_receive` and `node.network_transmit` each publish one sample for that host
- **AND** each value is the sum across `ens5` and `enp39s0` only

#### Scenario: Filesystem use covers only the data mount
- **WHEN** a host has `/mnt/db1` and other mounts
- **THEN** `node.filesystem_used` publishes one sample for that host, for `/mnt/db1` only

#### Scenario: Host without the data mount
- **WHEN** a host has no `/mnt/db1`
- **THEN** no `node.filesystem_used` sample is published for that host
