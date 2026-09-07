# Profiling Spec

## MODIFIED Requirements

### Requirement: Completed JFR chunks are shipped to Pyroscope

The system SHALL ship completed JFR chunks from each node to the Pyroscope server's ingest endpoint,
labelled with the node's hostname and the cluster name. The system SHALL only ship chunks that are
complete, and SHALL never ship the chunk currently being written. A chunk SHALL be shipped at most
once, and SHALL remain retrievable after it has shipped.

Pyroscope's object store SHALL be the accumulating account bucket, not the ephemeral per-cluster
data bucket. Profiles are critical observability data: `down` applies a whole-bucket lifecycle
expiration to the data bucket, so a profile stored there does not remain retrievable and the
guarantee above does not hold. The account bucket accumulates and carries no expiry.

The account bucket's region SHALL be resolved once, at bucket-ensure time, via `GetBucketLocation`,
stored on `ClusterState`, and exposed as its own template variable. Pyroscope's configuration SHALL
build its S3 endpoint and region from that variable, not from the cluster's region: the account
bucket is one per account, while a cluster can be brought up in a different region, and a config
built from the cluster's region would point at the wrong endpoint.

A cluster provisioned before this field existed carries a `state.json` without it. When the stored
region is absent, the system SHALL resolve it on first use with the same `GetBucketLocation` call,
and SHALL persist the result to `ClusterState`, so the resolution happens once per cluster rather
than once per command. The absence of the field SHALL NOT be an error, and SHALL NOT require the
user to re-provision.

The system SHALL NOT fall back to the cluster's region when the stored region is absent. That
fallback is the exact defect this rule exists to prevent, and it fails silently: a cluster in the
bucket's own region would work, and one outside it would point at the wrong endpoint.

When the lazy resolution itself fails — the bucket is unreachable, the credential lacks
`GetBucketLocation`, or the bucket does not exist — the system SHALL fail naming the bucket and the
call it attempted. It SHALL NOT substitute any other region and SHALL NOT continue with an unset
one.

`BUCKET_NAME` SHALL keep its current meaning untouched. It has exactly two consumers, and the other
is ClickHouse's S3 data disk, which SHALL NOT move to the account bucket.

#### Scenario: A completed chunk is shipped
- **WHEN** a JFR chunk completes at the end of a rotation interval
- **THEN** the reconciler uploads it to Pyroscope's ingest endpoint in JFR format, labelled with the
  node's hostname and cluster name, and marks it as shipped

#### Scenario: The chunk being written is never shipped while a session is running
- **WHEN** the reconciler examines the profile directory while a profiling session is attached
- **THEN** it excludes the most recently modified unshipped chunk, which under rotation is the one
  still being written

#### Scenario: The final chunk of a stopped session is shipped
- **WHEN** a session has been stopped, so its in-flight chunk was finalized and nothing is writing
- **THEN** that chunk is shipped rather than held back as "the newest", so the last interval of the
  run is not lost to retention

#### Scenario: A shipped chunk stays retrievable and is not shipped again
- **WHEN** a chunk has been uploaded successfully
- **THEN** it is excluded from subsequent uploads, and it remains listed among the node's completed
  chunks so retrieval and flame-graph conversion can still use it

#### Scenario: Profiles are selectable in Pyroscope
- **WHEN** a session profiling CPU with allocation sampling has been shipping for some time
- **THEN** CPU and allocation flame graphs are both selectable and populated in Pyroscope for that
  node

#### Scenario: Wall-clock profiles come from a separate session
- **WHEN** a session profiling wall-clock time has been shipping for some time
- **THEN** the wall-clock flame graph is populated in Pyroscope for that node, and obtaining CPU,
  wall, and allocation profiles requires running more than one session in sequence

#### Scenario: One pass does not run past its systemd timeout
- **WHEN** a backlog of completed chunks is larger than one pass can upload inside the reconcile
  unit's start timeout
- **THEN** the pass uploads a bounded number of them, reports that it was truncated as a distinct
  condition, and leaves the rest for later passes — and if it is killed anyway, it persists its
  counters, metrics and effective state on the way out, so a slow Pyroscope is never reported as a
  reconciler that has stopped running

#### Scenario: A pass killed during the attach persists what it learned
- **WHEN** a pass is killed while attaching to, detaching from, or probing a database JVM that is
  slow to answer
- **THEN** it still writes its counters, metrics and effective state before exiting, and reports the
  kill as its own condition — those calls are the ones that can consume the whole start timeout, and
  a pass killed just after attaching with no record written would have the next pass read the healthy
  session as unknown and tear it down

#### Scenario: Pyroscope stores profiles in the account bucket
- **WHEN** Pyroscope is configured on a cluster
- **THEN** its `bucket_name` is the accumulating account bucket
- **AND** it is not the per-cluster data bucket

#### Scenario: Profiles survive the data bucket's expiry
- **WHEN** a cluster is torn down and its per-cluster data bucket expires
- **THEN** the cluster's profiles are still readable, because none of them were stored in that bucket

#### Scenario: The bucket's region is resolved from the bucket, not the cluster
- **GIVEN** a cluster brought up in a region different from the account bucket's
- **WHEN** Pyroscope's configuration is rendered
- **THEN** its S3 endpoint and region come from the region resolved at bucket-ensure time via `GetBucketLocation` and stored on `ClusterState`
- **AND** they are not derived from the cluster's region

#### Scenario: An existing cluster with no stored region keeps working

- **GIVEN** a cluster provisioned before the field existed, whose `state.json` carries no account-bucket region
- **WHEN** a command needs that region
- **THEN** it is resolved on first use via `GetBucketLocation` and persisted to `ClusterState`
- **AND** the command succeeds without the user re-provisioning the cluster

#### Scenario: The resolution happens once, not once per command

- **GIVEN** a cluster whose account-bucket region has just been resolved lazily and persisted
- **WHEN** a later command needs that region
- **THEN** it reads the stored value
- **AND** it makes no further `GetBucketLocation` call

#### Scenario: An absent region never falls back to the cluster's

- **GIVEN** a cluster in a region different from the account bucket's, whose `state.json` carries no account-bucket region
- **WHEN** Pyroscope's configuration is rendered
- **THEN** the region comes from `GetBucketLocation` against the account bucket
- **AND** the cluster's own region is not used as a fallback

#### Scenario: A failed lazy resolution fails fast

- **GIVEN** a `state.json` carrying no account-bucket region
- **WHEN** the `GetBucketLocation` call fails because the bucket is unreachable, absent, or the credential lacks the permission
- **THEN** the command fails naming the bucket and the call it attempted
- **AND** it substitutes no other region and does not continue with an unset one

#### Scenario: `BUCKET_NAME` keeps its meaning

- **WHEN** the `BUCKET_NAME` template variable is resolved
- **THEN** it resolves as it did before this change
- **AND** ClickHouse's S3 data disk continues to use the per-cluster data bucket
