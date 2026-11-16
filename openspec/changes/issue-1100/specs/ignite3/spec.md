## ADDED Requirements

### Requirement: Ignite 3 Storage

The StatefulSet's `persistence` claim SHALL name `__KIT_STORAGE_CLASS__` and request `--storage-size` (default `${KIT_STORAGE_SIZE}`); a class-less claim, which would land on the cluster's default class, SHALL NOT be rendered. `start` SHALL run a `kit-storage` step for the db pool before the StatefulSet, and `uninstall` SHALL run `kit-storage-delete` after deleting the claims.

#### Scenario: Ignite 3 on local volumes

- **WHEN** the user runs `ignite3 start` on a cluster with no recorded kit StorageClass
- **THEN** each node's `persistence` claim binds a PV of the class `edl-local-ignite3` at `10Ti`

#### Scenario: Uninstall removes the local volumes

- **WHEN** the user runs `kit uninstall ignite3` on local volumes
- **THEN** the kit's PVs, its class and its node directories are removed
