## ADDED Requirements

### Requirement: The cluster records its observability store
The cluster state SHALL record the observability store as `observabilityStorage`, one of a closed set:
- `ObjectStore`, with scheme, bucket, prefix, region, an optional endpoint, and optional writer and compactor Secret names;
- `Filesystem`, with the data directory D on the machine that runs the CLI, or none on a user's cluster.

Either case MAY name a StorageClass and a size for clusters without the `HOST_PATH` capability. A state with no record SHALL resolve to the AWS account bucket reached as the instance role, exactly as before this change. The record SHALL round-trip through `state.json`.

#### Scenario: No record means the account bucket
- **WHEN** a cluster's state has no `observabilityStorage`
- **THEN** the store resolves to `S3Backend` on the account bucket, the hosting to host directories under `/mnt/db1`, and both identities to the instance role

#### Scenario: The record round-trips
- **WHEN** a state with a `Filesystem` record or an `ObjectStore` record is saved and loaded
- **THEN** the loaded record equals the saved one

### Requirement: A filesystem store writes each backend's native filesystem format
For a `Filesystem` record, each backend SHALL render its own store fragment instead of the S3 one: Mimir `filesystem`, Loki `filesystem`, Tempo `local` and Pyroscope `filesystem`. Each SHALL be rooted at the tree mounted at `/store`, under the store prefix. Loki's schema `object_store` SHALL read the published `store_kind`. The fragments SHALL be classpath resources and SHALL NOT be built from strings in Kotlin. On a filesystem store, no observability component and no CLI step of `up`, `down`, `grafana backup` or `report upload` SHALL send a request to S3 or to any AWS endpoint.

#### Scenario: All four signals land under D with no AWS request
- **WHEN** the stack runs on a filesystem store over D, on a network with no egress, and receives metrics, logs, traces and profiles
- **THEN** all four signals are written under D
- **AND** no request is sent to S3 or to any AWS endpoint, including the bucket-region lookup, the signing proxy and the account compactor

#### Scenario: Older data is read from D
- **WHEN** data older than the backends' local hold window is queried through Grafana on a filesystem store
- **THEN** metrics, logs, traces and profiles are returned

### Requirement: The data directory mirrors the bucket layout
On a filesystem store, D SHALL use the account bucket's layout, under the store prefix when one is set:
- `mimir/`, `loki/` (index under `loki/index/`), `tempo/`, `pyroscope/`
- `grafana/annotations/<tenant>/`, `reports/<tenant>/`
- each backend's local state under `state/<backend>/`

D SHALL be a parameter and never a hard-coded or personal path. Compactor scratch SHALL NOT be on D.

#### Scenario: Signals land in the bucket's paths
- **WHEN** the stack runs in tenant `acme` on a filesystem store over D
- **THEN** metric blocks land under `D/mimir/acme/`, log chunks under `D/loki/`, trace blocks under `D/tempo/acme/`, and profile data under `D/pyroscope/`
- **AND** the Mimir TSDB and WAL, the Loki WAL and index cache, the Tempo WAL, and the Pyroscope metastore and Raft state are under `D/state/`

#### Scenario: A new cluster reads the earlier data
- **WHEN** a cluster on D is torn down after a completed flush, and a new cluster starts on the same D
- **THEN** every earlier signal, profiles included, can still be queried
- **AND** no log line is returned twice

### Requirement: Volume hosting is chosen by capability, not by store
Where the cluster declares `HOST_PATH`, backend state and a filesystem tree SHALL be host directories: under `/mnt/db1` on AWS, and under `Constants.Observability.NODE_DATA_DIR` locally. Elsewhere they SHALL be the workload's retained claim of the recorded StorageClass, and a filesystem store SHALL use that claim's `state` and `store` sub-paths. No backend SHALL mount a hostPath on a cluster without `HOST_PATH`. A missing StorageClass SHALL fail before any apply and name the `init` option. A StorageClass whose reclaim policy is not `Retain` SHALL be refused, naming the class. Where `NODE_SSH` is absent, each backend SHALL create its own state directories with an init container.

#### Scenario: Local cluster mounts D
- **WHEN** the manifests are rendered for a filesystem store on a cluster declaring `HOST_PATH`
- **THEN** each backend mounts the node's D at `/store` and its state directory at `state/<backend>`

#### Scenario: Your cluster mounts the claim
- **WHEN** the manifests are rendered for a filesystem store on a cluster without `HOST_PATH`
- **THEN** each backend mounts its retained claim at the `state` and `store` sub-paths
- **AND** no backend volume is a hostPath

#### Scenario: A deleting class is refused
- **WHEN** the recorded StorageClass reclaims with `Delete`
- **THEN** `up` fails before applying anything and names the class

### Requirement: A retained-volume store is labelled as cluster-scoped
When a filesystem store sits on retained claims, `up` SHALL emit a typed event naming the StorageClass. The event and `status` SHALL say that the data lives only as long as the cluster's volumes.

#### Scenario: Retained volume is labelled
- **WHEN** `up` runs with a filesystem store on a cluster without `HOST_PATH`
- **THEN** a `StoreIsClusterScoped` event names the class
- **AND** `status` says the data lives as long as the cluster's volumes

### Requirement: Store access uses a writer and a compactor identity
An object store SHALL be reached with two identities. The writer, used by Mimir, Loki, Tempo and Pyroscope, SHALL NOT be able to delete under `mimir/`, `loki/` or `tempo/`, at the top of the store or under any prefix. It MAY delete under `pyroscope/` only, for Pyroscope v2 compaction. The compactor SHALL delete only sources it merged. Off AWS, each identity SHALL come from a Kubernetes Secret named in the record, with the defaults `edl-store-writer` and `edl-store-compactor`. Backends SHALL receive only the writer Secret, and the compactor only the compactor Secret. A filesystem store SHALL have no identities.

#### Scenario: Backends get the writer, the compactor gets the compactor
- **WHEN** the stack is rendered for an object store with writer and compactor Secrets
- **THEN** every backend reads the writer Secret and no backend reads the compactor Secret
- **AND** the compactor reads only the compactor Secret

#### Scenario: The writer cannot delete merged roots
- **WHEN** the writer policy is rendered
- **THEN** it denies deletes under `mimir/`, `loki/` and `tempo/`, at the top and under any prefix
- **AND** it allows deletes only under `pyroscope/`

### Requirement: Missing store Secrets stop up before anything is applied
`up` SHALL check, before it applies any observability resource, that every Secret named for an identity exists in the observability namespace and holds `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`. If one does not, `up` SHALL fail, name the Secret and the missing key, and print the policy that identity needs.

#### Scenario: Missing Secret
- **WHEN** the writer Secret does not exist
- **THEN** `up` fails before applying anything, naming the Secret and printing the writer policy

#### Scenario: Missing key
- **WHEN** the compactor Secret lacks `AWS_SECRET_ACCESS_KEY`
- **THEN** `up` fails, naming the Secret and the key

### Requirement: Observability objects use a store for the cluster
Annotation backups, test documents and the tenant listing SHALL use the store for the cluster: S3 for an object store, and a filesystem store rooted at D for a local filesystem store. The filesystem store SHALL write each object atomically and SHALL refuse any key that resolves outside D. A non-recursive listing SHALL return child directories as S3 returns common prefixes. On a filesystem store with no D (a user's retained volume), these commands SHALL fail with a message naming #1082. Every other object-store caller (Spark, the build catalog, cluster backups) SHALL keep using S3.

#### Scenario: Report upload lands under D
- **WHEN** `report upload` runs on a local filesystem store
- **THEN** the markdown, its HTML copy and the rebuilt `index.html` are under `D/reports/<tenant>/<name>-<id>/`

#### Scenario: A key outside D is refused
- **WHEN** the filesystem store is given a key with a `..` segment or an absolute segment
- **THEN** it fails and writes nothing

#### Scenario: Tenants are listed from D
- **WHEN** tenant directories exist under `D/mimir/`
- **THEN** the tenant listing returns them, as it does for the bucket

#### Scenario: Non-observability callers keep S3
- **WHEN** the store is a filesystem and a Spark or build-catalog operation runs
- **THEN** it uses the S3 object store

### Requirement: Grafana serves documents from the store tree
On a filesystem store, the Grafana documents view SHALL be served by nginx alone from the store tree mounted read-only, with no signing proxy and no bucket-region lookup. It SHALL answer only `GET` and `HEAD` under `/reports/`.

#### Scenario: The documents view shows local reports
- **WHEN** a report is uploaded on a local filesystem store
- **THEN** the documents view in Grafana shows it

#### Scenario: Only reads under reports are served
- **WHEN** the documents server receives a request that is not a `GET` or `HEAD` under `/reports/`
- **THEN** it refuses it

### Requirement: AWS storage is unchanged
With no store record, the backends' rendered storage, the IAM deny rules and the compactor SHALL behave as before this change. The one exception is Loki's schema `object_store`, which now reads `store_kind` and renders as `s3`.

#### Scenario: AWS rendering is unchanged
- **WHEN** the stack renders with no store record
- **THEN** each backend's store fragment, the host paths under `/mnt/db1` and the instance role are the same as before this change
- **AND** Loki's schema still names `s3`

## MODIFIED Requirements

### Requirement: No observability backend deletes data automatically

No backend, setting, or default SHALL delete stored observability data.  Compaction SHALL be allowed only where it writes a merged copy holding all of its sources' data before it removes them; retention and expiry SHALL stay off everywhere.

- Mimir on a cluster with an object store SHALL run with no compactor module, so no compaction, retention, cleanup or tenant-deletion path exists in the cluster.  On a filesystem store, Mimir SHALL run its compactor module as the `local-observability-compaction` capability specifies.
- Loki on a cluster with an object store SHALL run with retention disabled, deletion mode `disabled`, and a compaction interval so long that compaction never runs.  On a filesystem store, Loki SHALL compact as the `local-observability-compaction` capability specifies, with retention and deletion still disabled.
- Tempo on a cluster SHALL run with compaction and retention disabled for every tenant.
- The Pyroscope server SHALL run pure v2 storage with the metastore retention cleanup disabled and no retention period.  Pyroscope v2 compaction MAY merge segments into blocks, because the merged block keeps their data.
- The account compactor SHALL compact Mimir, Loki and Tempo data with retention and every other deletion path off, as the `account-compactor` capability specifies.

#### Scenario: Mimir has no deletion path in the cluster
- **WHEN** Mimir runs on a cluster with an object store
- **THEN** its module list contains no compactor
- **AND** no Mimir block, bucket index, or tenant is deleted or marked for deletion by the cluster

#### Scenario: Loki in the cluster never compacts or deletes
- **WHEN** Loki runs on a cluster with an object store for longer than any former retention period
- **THEN** no compaction and no retention run in the cluster
- **AND** a delete request is refused

#### Scenario: Tempo in the cluster keeps every block
- **WHEN** Tempo runs on a cluster for longer than any former retention period
- **THEN** its configuration disables compaction and retention for every tenant
- **AND** no Tempo block is deleted by the cluster

#### Scenario: Pyroscope keeps every profile
- **WHEN** the Pyroscope server runs
- **THEN** it uses v2 storage with retention cleanup disabled
- **AND** no profile is deleted by age

#### Scenario: The account compactor runs with retention off
- **WHEN** the account compactor runs
- **THEN** no retention setting is on in Mimir, Loki or Tempo
- **AND** every sample, log line and trace is still queryable after compaction
