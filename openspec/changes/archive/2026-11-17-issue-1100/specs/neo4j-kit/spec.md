## MODIFIED Requirements

### Requirement: Neo4j kit runs Neo4j Community on a db node
The system SHALL provide a `neo4j` kit that deploys Neo4j Community Edition as a StatefulSet with one replica, scheduled onto a db node, with its data on a claim of `__KIT_STORAGE_CLASS__` that its `kit-storage` step readies. `kit.yaml` SHALL declare `type: db`.

#### Scenario: Install succeeds
- **WHEN** the user runs `easy-db-lab kit install neo4j`
- **THEN** the install succeeds and the `neo4j` kit directory holds its `kit.yaml`

#### Scenario: Start yields a Ready pod on a db node
- **WHEN** the user runs `easy-db-lab neo4j start`
- **THEN** one Neo4j pod labelled `easydblab/kit=neo4j` is Ready on a db node AND its data directory is on a claim of the kit storage class

### Requirement: Neo4j stop and uninstall clean up by label
`stop` SHALL delete the StatefulSet, Services, and pods labelled `easydblab/kit=neo4j`. `uninstall` SHALL additionally delete the kit's PVCs (the StatefulSet's `volumeClaimTemplates` metadata carries the kit label) and run `kit-storage-delete`.

#### Scenario: Stop removes pod and NodePort services
- **WHEN** the user runs `easy-db-lab neo4j stop`
- **THEN** no StatefulSet, pod, or Service labelled `easydblab/kit=neo4j` remains

#### Scenario: Uninstall removes PVCs and PVs
- **WHEN** the user runs `easy-db-lab neo4j uninstall`
- **THEN** no PVC labelled `easydblab/kit=neo4j` remains AND on local volumes the kit's PVs and its class are deleted
