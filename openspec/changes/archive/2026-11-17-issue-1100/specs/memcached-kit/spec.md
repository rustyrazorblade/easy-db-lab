## MODIFIED Requirements

### Requirement: memcached extstore is optional and lives on NVMe
The kit SHALL accept an `--extstore-size` install argument (for example `100G`). When it is not set, memcached SHALL run from RAM only and the kit SHALL create no volume. When it is set, the kit SHALL claim a volume from `__KIT_STORAGE_CLASS__` at `--storage-size` (default `${KIT_STORAGE_SIZE}`), with no `volumeName`; on local volumes its `kit-storage` step creates that volume as a local PersistentVolume on the db node's NVMe. The kit SHALL mount it into the memcached container at `/data` and start memcached with `-o ext_path=/data/extstore:<size>`. The kit SHALL also accept `--extstore-page-size`, `--extstore-wbuf-size`, `--extstore-threads`, and `--extstore-item-size`, passed as `ext_page_size`, `ext_wbuf_size`, `ext_threads`, and `ext_item_size`; when one is not set, memcached's own default SHALL apply and the option SHALL NOT appear on the command line. The extstore arguments SHALL be ignored unless `--extstore-size` is set. `uninstall` SHALL delete the kit's PVC and run `kit-storage-delete`. The dashboard SHALL show extstore activity when extstore is enabled.

#### Scenario: Extstore off by default
- **WHEN** the kit is installed with no `--extstore-size` and started
- **THEN** the memcached command line has no `ext_path` option AND no PersistentVolume or PersistentVolumeClaim exists for the kit

#### Scenario: Extstore on NVMe
- **WHEN** the kit is installed with `--extstore-size 100G` and started on a cluster with no recorded kit StorageClass
- **THEN** the memcached container runs with `-o ext_path=/data/extstore:100G` AND `/data` is a local PV of the class `edl-local-memcached` on the db node's NVMe

#### Scenario: Extstore tuning options
- **WHEN** the kit is installed with `--extstore-size 100G --extstore-threads 8 --extstore-item-size 512`
- **THEN** the memcached `-o` options include `ext_threads=8` and `ext_item_size=512` AND do not include `ext_page_size` or `ext_wbuf_size`

#### Scenario: Items spill to extstore
- **WHEN** extstore is enabled and the client writes more data than `--memory` holds
- **THEN** the exporter's extstore metrics show objects written to flash AND gets for those keys still return their values

#### Scenario: Uninstall removes the extstore volume
- **WHEN** the user uninstalls a memcached kit that was started with extstore
- **THEN** no PersistentVolume or PersistentVolumeClaim for the kit remains
