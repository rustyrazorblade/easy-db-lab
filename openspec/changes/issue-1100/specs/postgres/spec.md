## MODIFIED Requirements

### Requirement: Data Lifecycle

Stop MUST preserve data; data is only deleted on uninstall. Starting after a stop MUST resume the existing dataset without any additional user steps.

#### Scenario: Data survives stop and start
- **WHEN** data is written to PostgreSQL, `postgres stop` is run, and then `postgres start` is run again
- **THEN** the previously written data is accessible without any restore step.

#### Scenario: Uninstall deletes data
- **WHEN** the user runs `postgres uninstall` on local volumes
- **THEN** the CNPG operator is removed and the instance's PVs, its class `edl-local-<instance>` and its node directories are deleted.

#### Scenario: First start after fresh install
- **WHEN** `postgres start` is run after a fresh install with no prior starts
- **THEN** the `kit-storage` step readies the instance's storage, and the Cluster CR, whose storage names `__KIT_STORAGE_CLASS__` at `--storage-size` with no selector, is deployed successfully.
