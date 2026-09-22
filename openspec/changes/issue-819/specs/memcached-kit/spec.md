## ADDED Requirements

### Requirement: memcached kit runs one memcached pod on the db pool
The system SHALL provide a `memcached` kit that deploys memcached as a Kubernetes Deployment with one replica, scheduled onto a db node by node affinity. `kit.yaml` SHALL declare `type: db`. Unless extstore is enabled, the kit SHALL create no PersistentVolumes or PersistentVolumeClaims.

#### Scenario: Install and start bring up a ready pod on a db node
- **WHEN** the user runs `easy-db-lab kit install memcached` and then `easy-db-lab memcached start`
- **THEN** the command succeeds AND one memcached pod labelled `easydblab/kit=memcached` is Ready on a db node

#### Scenario: No persistent volumes are created
- **WHEN** the memcached kit is started with no `--extstore-size`
- **THEN** no PersistentVolume or PersistentVolumeClaim exists for the kit

### Requirement: memcached is reachable through a NodePort endpoint
The kit SHALL expose memcached through a NodePort Service on port 31211. `kit.yaml` SHALL declare it under `endpoints` with type `native` and `node-type: db`, so `kit info memcached` resolves it to the db node's private IP and port 31211.

#### Scenario: kit info resolves the endpoint
- **WHEN** the user runs `easy-db-lab kit info memcached` on a cluster where memcached is started
- **THEN** the output lists the memcached endpoint as `<db node private IP>:31211`

#### Scenario: Another pod can set and get over the endpoint
- **WHEN** a pod in the cluster connects to `<db node private IP>:31211`, sets a key, and gets it back
- **THEN** the get returns the value that was set

### Requirement: memcached cache size is configurable
The kit SHALL accept a `--memory` install argument, mapped to the `MEMORY_MB` variable, giving the cache size in megabytes. The default SHALL be `1024`. The value SHALL be passed to memcached as `-m <MEMORY_MB>`.

#### Scenario: Default cache size
- **WHEN** the kit is installed with no `--memory` argument and started
- **THEN** the memcached container runs with `-m 1024`

#### Scenario: Custom cache size
- **WHEN** the kit is installed with `--memory 4096` and started
- **THEN** the memcached container runs with `-m 4096`

### Requirement: memcached kit refuses a conflicting install
`kit.yaml` SHALL set `collision-check: true`, so installing memcached into a cluster where it is already present fails with a clear `CollisionDetected` error before any manifest is applied.

#### Scenario: Second install fails clearly
- **WHEN** memcached is already installed and the user installs it again
- **THEN** the install fails with a `CollisionDetected` error naming the kit AND the running memcached objects are unchanged

### Requirement: memcached stop and uninstall remove everything by label
`stop` and `uninstall` SHALL delete every Kubernetes object the kit created by the label selector `easydblab/kit=memcached`.

#### Scenario: Stop removes all kit objects
- **WHEN** the user runs `easy-db-lab memcached stop`
- **THEN** no Deployment, pod, or Service labelled `easydblab/kit=memcached` remains

#### Scenario: Uninstall leaves nothing behind
- **WHEN** the user runs `easy-db-lab memcached uninstall`
- **THEN** no object labelled `easydblab/kit=memcached` remains AND no PersistentVolume or PersistentVolumeClaim for the kit remains

### Requirement: memcached metrics reach VictoriaMetrics and Grafana
The memcached pod SHALL run a `memcached-exporter` sidecar serving Prometheus metrics on port 9150. `kit.yaml` SHALL declare a `scrape` metrics entry on port 9150 with a `pod-selector` matching the memcached pod, so the OTel collector finds it by pod discovery. The kit SHALL ship a Grafana dashboard installed into a `memcached` folder on `start`, a `METRICS.md`, and a `metrics-catalog.json` exported from a live cluster.

#### Scenario: Exporter series appear in VictoriaMetrics
- **WHEN** memcached is started and the collector has scraped at least once
- **THEN** VictoriaMetrics holds `memcached_*` series for the kit's scrape job

#### Scenario: Dashboard shows live data
- **WHEN** memcached is started and receiving set/get traffic
- **THEN** Grafana has a `memcached` folder whose dashboard panels show non-empty data

### Requirement: memcached kit is listed and documented
`kit list` SHALL show `memcached`. `docs/user-guide/kits.md` SHALL describe the kit (install, `--memory`, start, connect, stop), and `docs/reference/ports.md` SHALL list NodePort 31211 and exporter port 9150.

#### Scenario: kit list shows memcached
- **WHEN** the user runs `easy-db-lab kit list`
- **THEN** `memcached` appears in the output

### Requirement: memcached extstore is optional and lives on NVMe
The kit SHALL accept an `--extstore-size` install argument (for example `100G`). When it is not set, memcached SHALL run from RAM only and the kit SHALL create no volume. When it is set, the kit SHALL create a local PersistentVolume on the db node's NVMe through `platform-pvs`, mount it into the memcached container at `/data`, and start memcached with `-o ext_path=/data/extstore:<size>`. The kit SHALL also accept `--extstore-page-size`, `--extstore-wbuf-size`, `--extstore-threads`, and `--extstore-item-size`, passed as `ext_page_size`, `ext_wbuf_size`, `ext_threads`, and `ext_item_size`; when one is not set, memcached's own default SHALL apply and the option SHALL NOT appear on the command line. The extstore arguments SHALL be ignored unless `--extstore-size` is set. `uninstall` SHALL delete the kit's PVC and run `platform-pvs-delete`. The dashboard SHALL show extstore activity when extstore is enabled.

#### Scenario: Extstore off by default
- **WHEN** the kit is installed with no `--extstore-size` and started
- **THEN** the memcached command line has no `ext_path` option AND no PersistentVolume or PersistentVolumeClaim exists for the kit

#### Scenario: Extstore on NVMe
- **WHEN** the kit is installed with `--extstore-size 100G` and started
- **THEN** the memcached container runs with `-o ext_path=/data/extstore:100G` AND `/data` is a local PV on the db node's NVMe

#### Scenario: Extstore tuning options
- **WHEN** the kit is installed with `--extstore-size 100G --extstore-threads 8 --extstore-item-size 512`
- **THEN** the memcached `-o` options include `ext_threads=8` and `ext_item_size=512` AND do not include `ext_page_size` or `ext_wbuf_size`

#### Scenario: Items spill to extstore
- **WHEN** extstore is enabled and the client writes more data than `--memory` holds
- **THEN** the exporter's extstore metrics show objects written to flash AND gets for those keys still return their values

#### Scenario: Uninstall removes the extstore volume
- **WHEN** the user uninstalls a memcached kit that was started with extstore
- **THEN** no PersistentVolume or PersistentVolumeClaim for the kit remains
