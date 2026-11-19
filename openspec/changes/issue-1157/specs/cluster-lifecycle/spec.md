## ADDED Requirements

### Requirement: K3s server runs on embedded etcd on the data disk

The K3s server on the control node SHALL use embedded etcd, with one member, as its datastore, and SHALL NOT use kine or sqlite.  It MUST be one server; this is not high availability.

`start-k3s-server.sh` SHALL write `/etc/rancher/k3s/config.yaml.d/20-datastore.yaml` on every run, before it starts `k3s.service`.  The file SHALL set `cluster-init: true` and the etcd argument `quota-backend-bytes=8589934592` (8 GiB).  The K3s agent SHALL NOT be given any datastore setting.

etcd data SHALL be on the control node's data disk, at `/mnt/db1/k3s/server/db/etcd` (K3s's data dir `/var/lib/rancher/k3s` links to `/mnt/db1/k3s`).  When `/mnt/db1` is not a mount point, `start-k3s-server.sh` MUST fail before it starts K3s, with a message that names `/mnt/db1` and says K3s's datastore needs the data disk, and MUST NOT put K3s's data on the root volume.

K3s's scheduled etcd snapshots SHALL keep K3s's defaults: twice a day, into `/mnt/db1/k3s/server/db/snapshots`, the five newest scheduled snapshots kept.  These snapshots are K3s's internal recovery files, not owner data; no owner data is written to etcd or to the snapshot directory.  Scheduled snapshots MUST NOT be disabled.

#### Scenario: up creates an etcd-backed server

- **GIVEN** a cluster being provisioned with `up`
- **WHEN** the K3s server has started
- **THEN** `kubectl get --raw '/readyz?verbose'` lists `[+]etcd ok`
- **AND** the control node carries the label `node-role.kubernetes.io/etcd=true`
- **AND** `/mnt/db1/k3s/server/db/etcd/member` exists on the control node and `/mnt/db1/k3s/server/db/state.db` does not.

#### Scenario: The datastore settings are written on every run

- **GIVEN** a control node whose data disk is mounted at `/mnt/db1`
- **WHEN** `start-k3s-server.sh` runs, once or again on a re-run of `up`
- **THEN** `/etc/rancher/k3s/config.yaml.d/20-datastore.yaml` holds `cluster-init: true` and the etcd argument `quota-backend-bytes=8589934592`, once each
- **AND** the kubelet eviction drop-in `10-kubelet-eviction.yaml` is unchanged.

#### Scenario: The agent gets no datastore setting

- **GIVEN** a db or app node
- **WHEN** `start-k3s-agent.sh` runs
- **THEN** no `20-datastore.yaml` drop-in is written and the agent's K3s configuration names no `cluster-init` or `etcd-arg`.

#### Scenario: A control node without a data disk refuses to start K3s

- **GIVEN** a control node where `/mnt/db1` is not a mount point
- **WHEN** `start-k3s-server.sh` runs
- **THEN** it exits non-zero with a message that names `/mnt/db1` and the datastore, before it installs or starts K3s
- **AND** `/var/lib/rancher/k3s` is not linked or moved, and no datastore drop-in is written.

#### Scenario: Existing commands keep working on an etcd-backed cluster

- **GIVEN** a small cluster provisioned with an etcd-backed server
- **WHEN** the operator installs and runs a kit, opens the observability dashboards, backs up and restores the cluster state, restarts the K3s server, and runs `down`
- **THEN** each works as on a sqlite-backed cluster
- **AND** after a K3s restart every node returns to Ready with the cluster's objects intact
- **AND** `down` flushes every backend and tears down as before; the etcd data and snapshots go with the control node.
