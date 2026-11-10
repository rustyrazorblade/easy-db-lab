## ADDED Requirements

### Requirement: Observability storage is a closed set of storages
The observability stack SHALL store data in exactly one storage, chosen once per cluster, from a closed set: `S3`, the account bucket (today's behaviour); or `Volume`, a PersistentVolumeClaim per backend mounted into that backend's pod.  The pods SHALL NOT mount a hostPath for observability data in `Volume` storage.  The claims are bound to storage by the environment, not by this change: the local environment pre-creates static `Retain` PersistentVolumes over its data directory D, and a bring-your-own cluster's StorageClass provisions them.  Each backend SHALL use its native filesystem store on a path inside its claim's mount: Mimir `filesystem` instead of `s3`, Loki `filesystem` instead of `s3`, Tempo `local` instead of `s3`, and Pyroscope `filesystem` instead of `s3`.  Each backend SHALL keep one configuration resource, with one published value selecting its store.  Until the environment is recorded in cluster state, the storage SHALL be `S3`.  In `Volume` storage, no observability component SHALL send a request to S3 or to any AWS endpoint.

#### Scenario: Volume storage writes every signal under D
- **WHEN** the stack runs with `Volume` storage over PersistentVolumes backed by D, and receives metrics, logs, traces and profiles
- **THEN** all four signals are written under D
- **AND** no request is sent to S3 or to any AWS endpoint

#### Scenario: Backends mount a claim and no hostPath in Volume storage
- **WHEN** the Mimir, Loki, Tempo and Pyroscope manifests are rendered for `Volume` storage
- **THEN** each backend pod mounts its PersistentVolumeClaims at its data paths
- **AND** no observability data volume of any backend is a hostPath

#### Scenario: Default storage is S3
- **WHEN** a cluster's state records no environment
- **THEN** every backend renders with its S3 store, exactly as before this change

#### Scenario: Each backend ignores the store it is not using
- **WHEN** a backend's configuration is rendered for one storage
- **THEN** the backend starts and serves reads and writes, with no error caused by the other storage's settings

### Requirement: Backend data placement comes from one function
The Mimir, Loki, Tempo and Pyroscope manifest builders SHALL take their data volumes and mount paths from one function of the cluster's storage and the backend, and SHALL NOT hard-code a data path.  For `S3` it SHALL return today's `/mnt/db1/<backend>` hostPath.  For `Volume` it SHALL return PersistentVolumeClaim volume sources and mount paths.  The claim names and sizes SHALL be constants that an environment's provisioner reads, so a provisioner and the builders cannot disagree.

#### Scenario: Backend data placement comes from one function
- **WHEN** the four builders render for a storage
- **THEN** each backend's volume source and paths equal that function's answer for the storage and the backend
- **AND** with `S3` the placement equals today's

### Requirement: Each backend has its own claims
In `Volume` storage, each of Mimir, Loki, Tempo and Pyroscope SHALL have two PersistentVolumeClaims of its own: an object claim, whose volume is `D/<backend>/`, and a state claim, whose volume is `D/state/<backend>/`.  The Grafana documents server SHALL have a read-only claim over `D/reports/`.  No claim SHALL be mounted by two workloads.  Every claim SHALL have a fixed name and size from `Constants`, so that a PersistentVolume can be bound to it before the cluster exists.

#### Scenario: Claims are per backend
- **WHEN** the manifests are rendered for `Volume` storage
- **THEN** Mimir, Loki, Tempo and Pyroscope each declare an object claim and a state claim with the names and sizes from `Constants`, and the documents server declares a read-only reports claim
- **AND** no claim is mounted by two workloads

### Requirement: The data directory mirrors the bucket layout
In `Volume` storage, the claims' backing storage D SHALL use the account bucket's layout: `mimir/`, `loki/` (index under `loki/index/`), `tempo/`, `pyroscope/`, `grafana/annotations/<tenant>/` and `reports/<tenant>/`.  Each backend's local state SHALL live under `state/<backend>/`.  For Mimir, the macOS bind-mount gate (design.md) MAY back Mimir's state claim with node-local storage that is not on the bind mount, while Mimir's object claim stays on D.  D SHALL be a parameter and SHALL NOT be a hard-coded or personal path.

#### Scenario: Signals land in the bucket's paths
- **WHEN** the stack runs in tenant `acme` with `Volume` storage over PersistentVolumes backed by D
- **THEN** metric blocks land under `D/mimir/acme/`, log chunks under `D/loki/`, trace blocks under `D/tempo/acme/`, and profile data under `D/pyroscope/`

#### Scenario: Backend state lives under D
- **WHEN** the stack runs with `Volume` storage over PersistentVolumes backed by D
- **THEN** the Pyroscope metastore data and Raft state, the Loki WAL and index cache, and the Tempo WAL are under `D/state/`

### Requirement: The local-path provisioner never holds observability data
No claim for observability data SHALL be served by a dynamic provisioner whose reclaim policy is `Delete`, or whose directory names are random.  In particular, K3s's `local-path` provisioner SHALL NOT back any observability claim.  The local environment SHALL bind each claim to a static PersistentVolume with `persistentVolumeReclaimPolicy: Retain` and a fixed path under D, pre-bound by `claimRef` to its claim's constant name and namespace.  Every claim SHALL name an explicit StorageClass, so no default class can adopt it.  No code in this change SHALL delete a PersistentVolume, a claim's data, or anything under D: `down` and `clean` SHALL leave the claims and volumes alone.

#### Scenario: local-path is never used
- **WHEN** the manifests are rendered for `Volume` storage
- **THEN** no claim names the `local-path` StorageClass, and each claim names the StorageClass of its static PersistentVolume

#### Scenario: A removed cluster leaves the volume data intact
- **WHEN** a cluster with `Volume` storage is removed after a completed flush
- **THEN** every file under D remains, and neither `down` nor `clean` deleted a claim or a PersistentVolume

#### Scenario: A new cluster's claims bind to the same paths
- **WHEN** a new cluster's provisioner creates the static Retain PersistentVolumes over the same D and the claims are applied
- **THEN** each claim binds to the PersistentVolume pre-bound to it, and its backend reads the same directory as before
- **AND** every earlier signal can be queried

### Requirement: Volume data outlives the cluster
In `Volume` storage, every signal that a backend acknowledged and a flush completed SHALL stay readable from D after the cluster is removed.  A new cluster started on the same D SHALL read it.  Every signal SHALL also be readable past the backend's local hold window: metrics through the store-gateway and the bucket index, logs, traces, and profiles through the metastore under D.

#### Scenario: Older data is read from D
- **WHEN** data older than the backends' local hold window is queried through Grafana
- **THEN** metrics, logs, traces and profiles are returned

#### Scenario: A new cluster reads the earlier data
- **WHEN** a cluster on D is torn down after a completed flush, and a new cluster starts on the same D
- **THEN** every earlier signal, profiles included, can still be queried

### Requirement: Observability objects use a store for the chosen storage
Annotation backups, test documents and the tenant listing SHALL use an object store for the cluster's observability storage: S3 in `S3` storage, and a filesystem store rooted at D on the machine that runs the CLI in `Volume` storage (the local environment).  The filesystem store SHALL write each object atomically and SHALL refuse any key that resolves outside D.  Every other caller of the object store (Spark, the build catalog) SHALL keep using S3.

#### Scenario: Report upload lands under D
- **WHEN** `report upload` runs with `Volume` storage
- **THEN** the markdown, its HTML copy and the rebuilt `index.html` are under `D/reports/<tenant>/<name>-<id>/`

#### Scenario: A key outside D is refused
- **WHEN** the filesystem store is given a key with a `..` segment
- **THEN** it fails and writes nothing

#### Scenario: Non-observability callers keep S3
- **WHEN** the storage is `Volume` and a Spark or build-catalog operation runs
- **THEN** it uses the S3 object store

### Requirement: Grafana serves documents from the data directory
In `Volume` storage, the Grafana documents view SHALL be served from the reports claim, mounted read-only, by the documents web server alone, with no signing proxy.  It SHALL answer only `GET` requests under `/reports/`, as in `S3` storage.

#### Scenario: The documents view shows local reports
- **WHEN** a report is uploaded with `Volume` storage
- **THEN** the documents view in Grafana shows it

#### Scenario: Only GET under reports is served
- **WHEN** the documents web server receives a request that is not a `GET` under `/reports/`
- **THEN** it refuses it

### Requirement: AWS storage is unchanged
In `S3` storage, the backends' storage, the cluster's IAM deny of observability deletes, and the account compactor SHALL behave exactly as before this change.

#### Scenario: AWS behaviour is unchanged
- **WHEN** the stack runs in `S3` storage
- **THEN** the rendered backend configurations, the instance role's deny statement, and the account compactor's `ensureRunning` and `stopIfLastCluster` calls are the same as before this change
