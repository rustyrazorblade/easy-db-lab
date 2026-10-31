## ADDED Requirements

### Requirement: Observability storage is S3 or a host directory
The observability stack SHALL store data in exactly one of two storages, chosen once per cluster: `S3`, the account bucket (today's behaviour), or `Filesystem`, a host data directory D mounted into the cluster at `Constants.Observability.LOCAL_DATA_MOUNT`.  Each backend SHALL use its native store for the chosen storage: Mimir `filesystem` or `s3`, Loki `filesystem` or `s3`, Tempo `local` or `s3`, and Pyroscope `filesystem` or `s3`.  Each backend SHALL keep one configuration resource, with one published value selecting its store.  Until the environment is recorded in cluster state, the storage SHALL be `S3`.  In `Filesystem` storage, no observability component SHALL send a request to S3 or to any AWS endpoint.

#### Scenario: Filesystem storage writes every signal under D
- **WHEN** the stack runs with `Filesystem` storage at D and receives metrics, logs, traces and profiles
- **THEN** all four signals are written under D
- **AND** no request is sent to S3 or to any AWS endpoint

#### Scenario: Default storage is S3
- **WHEN** a cluster's state records no environment
- **THEN** every backend renders with its S3 store, exactly as before this change

#### Scenario: Each backend ignores the store it is not using
- **WHEN** a backend's configuration is rendered for one storage
- **THEN** the backend starts and serves reads and writes, with no error caused by the other storage's settings

### Requirement: The data directory mirrors the bucket layout
In `Filesystem` storage, D SHALL use the account bucket's layout: `mimir/`, `loki/` (index under `loki/index/`), `tempo/`, `pyroscope/`, `grafana/annotations/<tenant>/` and `reports/<tenant>/`.  Each backend's local state SHALL live under `state/<backend>/`.  For Mimir, the macOS bind-mount gate (design.md) MAY place the TSDB and sync directories on the node instead, while Mimir's object root stays under D.  D SHALL be a parameter and SHALL NOT be a hard-coded or personal path.

#### Scenario: Signals land in the bucket's paths
- **WHEN** the stack runs in tenant `acme` with `Filesystem` storage at D
- **THEN** metric blocks land under `D/mimir/acme/`, log chunks under `D/loki/`, trace blocks under `D/tempo/acme/`, and profile data under `D/pyroscope/`

#### Scenario: Backend state lives under D
- **WHEN** the stack runs with `Filesystem` storage at D
- **THEN** the Pyroscope metastore data and Raft state, the Loki WAL and index cache, and the Tempo WAL are under `D/state/`

### Requirement: Filesystem data outlives the cluster
In `Filesystem` storage, every signal that a backend acknowledged and a flush completed SHALL stay readable from D after the cluster is removed.  A new cluster started on the same D SHALL read it.  Every signal SHALL also be readable past the backend's local hold window: metrics through the store-gateway and the bucket index, logs, traces, and profiles through the metastore under D.

#### Scenario: Older data is read from D
- **WHEN** data older than the backends' local hold window is queried through Grafana
- **THEN** metrics, logs, traces and profiles are returned

#### Scenario: A new cluster reads the earlier data
- **WHEN** a cluster on D is torn down after a completed flush, and a new cluster starts on the same D
- **THEN** every earlier signal, profiles included, can still be queried

### Requirement: Observability objects use a store for the chosen storage
Annotation backups, test documents and the tenant listing SHALL use an object store for the cluster's observability storage: S3 in `S3` storage, and a filesystem store rooted at D in `Filesystem` storage.  The filesystem store SHALL write each object atomically and SHALL refuse any key that resolves outside D.  Every other caller of the object store (Spark, the build catalog) SHALL keep using S3.

#### Scenario: Report upload lands under D
- **WHEN** `report upload` runs with `Filesystem` storage
- **THEN** the markdown, its HTML copy and the rebuilt `index.html` are under `D/reports/<tenant>/<name>-<id>/`

#### Scenario: A key outside D is refused
- **WHEN** the filesystem store is given a key with a `..` segment
- **THEN** it fails and writes nothing

#### Scenario: Non-observability callers keep S3
- **WHEN** the storage is `Filesystem` and a Spark or build-catalog operation runs
- **THEN** it uses the S3 object store

### Requirement: Grafana serves documents from the data directory
In `Filesystem` storage, the Grafana documents view SHALL be served from `D/reports`, mounted read-only, by the documents web server alone, with no signing proxy.  It SHALL answer only `GET` requests under `/reports/`, as in `S3` storage.

#### Scenario: The documents view shows local reports
- **WHEN** a report is uploaded with `Filesystem` storage
- **THEN** the documents view in Grafana shows it

#### Scenario: Only GET under reports is served
- **WHEN** the documents web server receives a request that is not a `GET` under `/reports/`
- **THEN** it refuses it

### Requirement: AWS storage is unchanged
In `S3` storage, the backends' storage, the cluster's IAM deny of observability deletes, and the account compactor SHALL behave exactly as before this change.

#### Scenario: AWS behaviour is unchanged
- **WHEN** the stack runs in `S3` storage
- **THEN** the rendered backend configurations, the instance role's deny statement, and the account compactor's `ensureRunning` and `stopIfLastCluster` calls are the same as before this change
