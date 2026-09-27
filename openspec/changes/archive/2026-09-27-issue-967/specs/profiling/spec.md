## MODIFIED Requirements

### Requirement: Completed JFR chunks are shipped to Pyroscope

The system SHALL ship completed JFR chunks from each node to the Pyroscope server's ingest endpoint,
labelled with the node's hostname and the cluster name, and carrying the cluster's tenant in the
`X-Scope-OrgID` header. The system SHALL only ship chunks that are complete, and SHALL never ship the
chunk currently being written. A chunk SHALL be shipped at most once, and SHALL remain retrievable
after it has shipped.

#### Scenario: A completed chunk is shipped
- **WHEN** a JFR chunk completes at the end of a rotation interval
- **THEN** the reconciler uploads it to Pyroscope's ingest endpoint in JFR format, labelled with the
  node's hostname and cluster name, and marks it as shipped

#### Scenario: A shipped chunk carries the tenant
- **WHEN** the reconciler uploads a chunk from a cluster in tenant `acme`
- **THEN** the upload carries `X-Scope-OrgID: acme`, and Pyroscope stores the profile under that
  tenant

#### Scenario: The chunk being written is never shipped while a session is running
- **WHEN** the reconciler examines the profile directory while a profiling session is attached
- **THEN** it excludes the most recently modified unshipped chunk, which under rotation is the one
  still being written

#### Scenario: The final chunk of a stopped session is shipped
- **WHEN** a session has been stopped, so its in-flight chunk was finalized and nothing is writing
- **THEN** that chunk is shipped rather than held back as "the newest", so the last interval of the
  run is not lost

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

### Requirement: Local JFR retention is bounded

The system SHALL bound the JFR chunks retained on each node by both age and total size. Both bounds
SHALL be configurable. Pruning SHALL apply only to chunks that have shipped, because their data is in
the profile store. The system SHALL NOT delete a chunk that has not shipped or that Pyroscope
rejected, because that chunk is data the operator cannot get back. WHEN the profile directory reaches
its size bound and no shipped chunk is left to prune, the system SHALL stop recording on that node
instead of deleting data, and SHALL resume recording once the directory is back under its bound.

#### Scenario: Shipped chunks are pruned by age
- **WHEN** a shipped chunk ages past the configured retention window
- **THEN** it is deleted from the node

#### Scenario: Shipped chunks are pruned by total size
- **WHEN** the profile directory exceeds the configured size ceiling
- **THEN** shipped chunks are deleted oldest-first until it no longer does, regardless of their age

#### Scenario: Unshipped chunks are never pruned
- **WHEN** Pyroscope has been unreachable for longer than the retention window
- **THEN** every unshipped chunk is kept on the node, whatever its age

#### Scenario: Rejected chunks are never pruned
- **WHEN** Pyroscope has rejected chunks and the profile directory exceeds its size ceiling
- **THEN** every rejected chunk is kept on the node, and remains retrievable and convertible to a
  flame graph

#### Scenario: Recording stops at the size bound instead of deleting data
- **WHEN** the profile directory reaches its size ceiling and only unshipped or rejected chunks
  remain
- **THEN** the reconciler stops the recording session on that node and deletes no chunk
- **AND** the stop and its reason reach the node's effective state, its logs, and its metrics, so
  `cassandra profile status` renders it and emits a typed profiling event

#### Scenario: Recording resumes once space is available
- **WHEN** recording was stopped at the size bound and the directory falls back under its bound,
  because chunks shipped and were pruned or the operator removed chunks
- **THEN** the reconciler starts recording again with the desired profiling arguments

#### Scenario: Profiling output does not endanger the database
- **WHEN** profiling runs continuously over a long period
- **THEN** the profile directory remains within its configured bounds, so it cannot exhaust the
  volume Cassandra stores data on

#### Scenario: Operator-supplied artifacts are never pruned
- **WHEN** an operator has placed files such as heap dumps in the node's artifacts directory
- **THEN** profiling retention never deletes them, because profiling output is kept in its own
  directory
