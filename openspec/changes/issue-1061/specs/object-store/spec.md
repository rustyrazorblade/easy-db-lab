## ADDED Requirements

### Requirement: Storage locations carry no URI scheme

A storage location SHALL be a `ClusterStoragePath`: a bucket, a store prefix and key segments, with no URI scheme.  Its key SHALL be the store prefix followed by the segments, with an empty prefix left out.  No class outside the S3 adapter packages (`services/aws`, `providers/aws`) SHALL render or parse `s3://`, an S3 endpoint host or an S3 object ARN.  A location shown to the user SHALL be rendered by the `ObjectStore` adapter that stores it.

#### Scenario: An empty prefix leaves every key unchanged
- **WHEN** a path is built for a cluster's backups with an empty store prefix
- **THEN** its key is `clusters/<name>-<id>/backups`, as today

#### Scenario: A store prefix leads every key
- **WHEN** a path is built with the store prefix `team-a`
- **THEN** its key starts with `team-a/`

#### Scenario: No scheme outside the S3 adapter
- **WHEN** the main source outside `services/aws` and `providers/aws` is searched
- **THEN** no class renders or parses `s3://` or an `amazonaws.com` host

#### Scenario: S3 locations still read as s3 URIs
- **WHEN** a command on AWS reports where it stored a file
- **THEN** the location it shows is `s3://<bucket>/<key>`, as today

#### Scenario: A Spark jar given as an s3 URI still resolves
- **WHEN** `spark submit` is given a jar as `s3://<bucket>/<key>`
- **THEN** it submits that bucket and key, as today

### Requirement: The store backend is a neutral model

The object store a cluster uses SHALL be described by a `StoreBackend`: a sealed type outside the AWS adapter packages that imports no AWS SDK type.  Its only case SHALL be `S3Backend`, holding the bucket, the store prefix, the region and the endpoint.  Every cluster SHALL resolve to `S3Backend` with its account bucket, an empty prefix, its region and the endpoint `s3.<region>.amazonaws.com`.  The type SHALL hold no credential source; how pods get store credentials is outside this requirement.  A store prefix SHALL be refused wherever a `StoreBackend`, an `ObservabilityStore` or the compactor's policy is built from it, unless it is plain path segments (letters, digits, `.`, `_` and `-`; no empty, `.` or `..` segment; no `*` or `?`) none of which is a store root (`mimir`, `loki`, `tempo`, `grafana`, `reports`, `pyroscope`).  Slashes around the prefix are allowed.

#### Scenario: A prefix that could widen a policy is refused
- **WHEN** a store backend is built with the prefix `team-*`, `a/../b`, `grafana` or `x/mimir`
- **THEN** building it fails with a message naming the prefix

#### Scenario: An accepted prefix keeps the compactor off the uncompacted roots
- **WHEN** the compactor's task-role policy is built from any accepted prefix
- **THEN** it reaches no object under `grafana/`, `reports/` or `pyroscope/`, with or without that prefix in front

#### Scenario: A cluster resolves to the S3 backend
- **WHEN** the store backend is resolved for a cluster in `us-west-2` with account bucket `easy-db-lab-x`
- **THEN** it is `S3Backend` with bucket `easy-db-lab-x`, an empty prefix, region `us-west-2` and endpoint `s3.us-west-2.amazonaws.com`

#### Scenario: The model stays outside the SDK boundary
- **WHEN** the provider-boundary ArchUnit rules run
- **THEN** they pass with no allowlist entry for the store model

### Requirement: Each backend takes its object-store block from the store backend

The configuration of Mimir, Loki, Tempo, the Pyroscope server and the account compactor's Tempo backend SHALL take its object-store block from a fragment resource selected by the `StoreBackend` case, placed where the block stands in the configuration.  The S3 fragments SHALL hold the only `backend: s3` lines and the only S3 endpoint in the backend configurations.  On AWS the fully expanded configuration of every backend SHALL be byte-identical to the configuration captured before this change.  The account compactor's registered task definition SHALL be byte-identical to the one captured before this change, apart from the environment names design D5 renames (`S3_BUCKET`, `AWS_REGION` and `*_S3_PREFIX` become `STORE_BUCKET`, `STORE_REGION` and `*_STORE_PREFIX`) and the one variable it adds, `STORE_ENDPOINT`.

#### Scenario: The AWS configuration is unchanged
- **WHEN** Mimir, Loki, Tempo and Pyroscope are rendered for an AWS cluster and expanded against its cluster configuration
- **THEN** each result is byte-identical to its golden file captured before the change

#### Scenario: The compactor task definition is unchanged
- **WHEN** the account compactor's task definition is built for an AWS account
- **THEN** the request, with every configuration it carries decoded and expanded and each D5-renamed environment name mapped back to its former name, is byte-identical to its golden file captured before the change
- **AND** every container carries `STORE_ENDPOINT` with the region's S3 endpoint

#### Scenario: A different block renders with no S3 in it
- **WHEN** every backend is rendered with a stub store backend whose fragment holds a stub block
- **THEN** each configuration holds the stub block at the block's path
- **AND** it holds no `s3://`, no `amazonaws.com` and no S3 endpoint

#### Scenario: The endpoint lives only in the S3 fragments
- **WHEN** the backend configuration resources outside the S3 fragments are searched
- **THEN** none names `amazonaws.com` or `backend: s3`

### Requirement: An in-memory object store backs unit tests

The system SHALL provide an in-memory `ObjectStore` that keeps every object in process memory and implements the whole `ObjectStore` contract: upload and download of files, content and directories; listing with `recursive` and `startAfter` in key order; existence and metadata checks; and delete.  A unit test that only needs somewhere to store objects SHALL use it instead of a mock.  A test that makes storage fail SHALL keep a mock.

#### Scenario: Content round-trips
- **WHEN** content is uploaded to a path and read back
- **THEN** the same content is returned

#### Scenario: Listing follows key order and startAfter
- **WHEN** keys `a/1`, `a/2` and `a/3` are stored and `a/` is listed with `startAfter` `a/1`
- **THEN** only `a/2` and `a/3` are returned, in that order

#### Scenario: A non-recursive listing returns only direct children
- **WHEN** keys `a/1` and `a/b/2` are stored and `a/` is listed non-recursively
- **THEN** `a/1` and one directory entry `a/b` are returned, in that order, as `S3ObjectStore` returns a common prefix
- **AND** `a/b/2` is not returned

#### Scenario: Reading a missing object fails
- **WHEN** a path that holds no object is read
- **THEN** the read throws

#### Scenario: Stub-only tests use the adapter
- **WHEN** the unit and integration test sources are searched for a mocked `ObjectStore`
- **THEN** every remaining mock makes a storage call fail
