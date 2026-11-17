## MODIFIED Requirements

### Requirement: Multi-Version Support

The system MUST support multiple Cassandra versions (3.0 through trunk) on the same node.  A node SHALL have no Cassandra version when it is provisioned.  A version SHALL be on a node only after `cassandra use` or `cassandra install` installed it there.

#### Scenario: Select a Cassandra version

- **GIVEN** a running cluster
- **WHEN** the user selects a Cassandra version
- **THEN** that version is installed where it is missing, and activated with the JDK and Python that the CLI chose for it.

#### Scenario: Start cluster on selected version

- **GIVEN** a selected version
- **WHEN** the user starts the cluster
- **THEN** all nodes run the selected Cassandra version and join the ring.

#### Scenario: A new node has no version

- **GIVEN** a cluster that `up` provisioned
- **WHEN** no `cassandra use` or `cassandra install` has run
- **THEN** `/usr/local/cassandra` on each db node holds no version.

### Requirement: Use Side Effects

`cassandra use` MUST tell the user, before it starts, which hosts it will re-apply
`cassandra.patch.yaml` to as its last step (replacing per-host settings applied earlier with
`update-config`), which hosts must download the version first, and the JDK it switches to (the
`--java` value, or the JDK that the CLI chose for the version). It
MUST pass its `--hosts` filter to the config download as well as the update, MUST fail when no
host matches the filter, and MUST exit non-zero, naming the failed step, when the install, the
config download or the update fails.

#### Scenario: `use` reports a failed config update

- **GIVEN** the version switch succeeded on the targeted hosts
- **WHEN** the config update fails
- **THEN** the command reports that the version is active but the configuration was not updated,
  names `update-config --hosts` for the targeted hosts, and exits non-zero.

#### Scenario: `use` names the hosts that download

- **GIVEN** `db0` has version `5.0` and `db1` does not
- **WHEN** the user runs `cassandra use 5.0`
- **THEN** before any host changes, the output names `db1` as the host that downloads `5.0`, and names the JDK.

### Requirement: Runtime Version Installation

The system MUST support installing an additional Cassandra version onto an already-provisioned,
running cluster without rebuilding the AMI, via `cassandra install <version>`.

The system MUST support these install mechanisms: downloading a tarball from a URL that the CLI
resolved from a version source, downloading a tarball given with `--url`, and cloning a git
`--url` with `--branch` and building it with `ant`. `cassandra install` and `cassandra use` MUST
use the same node-side install implementation.

The CLI MUST resolve every install parameter (the source, the URL, the JDK, Python and, for a git
build, the ant flags) before it changes any node, and MUST give them to the node install script
as explicit arguments. The node install script MUST NOT read a version file or look up a version.
CLI options MUST take precedence over resolved values, field by field.

The system MUST support targeting all Cassandra nodes or a `--hosts`-filtered subset, matching the
targeting mechanism `cassandra use` already provides.

The system MUST record the installed version in the cluster state for each targeted node, so that
`cassandra use` for that version afterward uses the recorded parameters.

The system MUST treat installing a version that is already in `/usr/local/cassandra/<version>`
on a node as a safe no-op on that node: no re-download, no re-build, no error.  The only exception
is `cassandra install <version> --reinstall`, which downloads (or builds) the version again on each
targeted node, replaces the install, and updates the record in the cluster state.  The system MUST
NOT detect a changed build by itself (no ETag or date check).  A failed reinstall MUST leave the
old install and its record in place.  A reinstall of the active version of a node where Cassandra
runs MUST fail for that node before it downloads, and MUST say to stop Cassandra first.

The system MUST report success or failure per targeted host, and MUST fail the overall command
when any targeted host fails to install, without leaving other hosts partially configured beyond
what each host's own install actually completed. A failure message MUST name the host, the
version and each URL that the host tried.

The system MUST fetch a nightly build, a `--url` tarball and a build catalog tarball from its origin on every install.  It MUST NOT read that tarball from the download cache, and it MUST NOT write it to the cache.  The contents of such a URL can change under the same name (for example, a nightly build), so a cached copy can be an old build.  When the URL is `s3://...`, the system MUST copy it directly from S3.  When the download fails, the system MUST fail and MUST NOT fall back to a cached copy.  An ASF release does not change upstream, so the system MUST fetch it through the download cache, under a key named for the full release number.

#### Scenario: Install from a git branch

- **GIVEN** a version not yet present on the targeted node(s)
- **WHEN** the user runs `cassandra install <version> --url <git-url> --branch <b> --java <j>`
- **THEN** the branch is cloned and built with `ant` on each targeted node, under JDK `<j>`
- **AND** the built artifact lands at `/usr/local/cassandra/<version>`
- **AND** the command reports success per targeted host.

#### Scenario: Install from a tarball URL

- **GIVEN** a version not yet present
- **WHEN** the user runs `cassandra install <version> --url <tarball-url> --java <j>`
- **THEN** the tarball is downloaded and extracted to `/usr/local/cassandra/<version>` on each
  targeted node
- **AND** `--python` defaults to the default Python version (3.11.9) when not supplied.

#### Scenario: Install an ASF release by name

- **GIVEN** no version file anywhere names `4.1.7`
- **WHEN** the user runs `cassandra install 4.1.7`
- **THEN** each targeted node installs ASF release 4.1.7 to `/usr/local/cassandra/4.1.7`.

#### Scenario: Targeting a host subset

- **GIVEN** a cluster with multiple Cassandra nodes
- **WHEN** the user runs `cassandra install <version> --hosts <subset>`
- **THEN** only the targeted nodes have the version installed and recorded
- **AND** untargeted nodes are unaffected.

#### Scenario: Installed version becomes usable

- **GIVEN** a version successfully installed via `cassandra install`
- **WHEN** the user runs `cassandra use <version>` on the same node afterward
- **THEN** it does not download the version again, and uses the JDK and Python recorded by install.

#### Scenario: Re-running install for an already-installed version is a no-op

- **GIVEN** a version already installed on a node
- **WHEN** the user runs `cassandra install <version>` again against that node
- **THEN** the command does not error, does not re-download or re-build, and reports the version as
  already present.

#### Scenario: `--reinstall` gets a changed nightly

- **GIVEN** `db0` has `5.0-HEAD` from an earlier nightly, and the `nightly` release now has a newer tarball under the same name
- **AND** Cassandra is stopped on `db0`
- **WHEN** the user runs `cassandra install 5.0-HEAD --reinstall`
- **THEN** `db0` downloads the tarball from its origin again, and `/usr/local/cassandra/5.0-HEAD` holds the newer build
- **AND** the `5.0-HEAD` record of `db0` in the cluster state is updated
- **AND** the download cache is not read and not written for that tarball.

#### Scenario: A failed reinstall keeps the old install

- **GIVEN** `db0` has `5.0-HEAD`, and the download of the tarball fails
- **WHEN** the user runs `cassandra install 5.0-HEAD --reinstall`
- **THEN** the command exits non-zero, and names `db0`, the version and the URL
- **AND** the old install and its record are unchanged.

#### Scenario: A reinstall of a running version is refused

- **GIVEN** `5.0-HEAD` is the active version of `db0`, and Cassandra runs on `db0`
- **WHEN** the user runs `cassandra install 5.0-HEAD --reinstall`
- **THEN** `db0` downloads nothing, and the command exits non-zero and says to run `cassandra stop` first.

#### Scenario: Re-running install with parameters a git build contradicts

- **GIVEN** a version built from a git branch on a node, recorded with JDK 11
- **WHEN** the user runs `cassandra install <version> --java 17` (with no `--reinstall`) against that node
- **THEN** nothing on that node is changed, neither the on-disk install nor its record
- **AND** the host is reported with the fields that disagree (`java: recorded 11, requested 17`)
- **AND** the command exits non-zero.

Rationale: the bits on disk were built against the recorded JDK. Changing the JDK an existing
build runs under is `cassandra use --java`'s job.

#### Scenario: Recording an installed-but-unrecorded version

- **GIVEN** a version present on disk at `/usr/local/cassandra/<version>` with no record for that
  node in the cluster state
- **WHEN** the user runs `cassandra install <version>` against that node
- **THEN** the resolved parameters are recorded for that node
- **AND** the on-disk install is not re-downloaded or re-built
- **AND** the command reports the version as already present and does not error.

#### Scenario: A credential in `--url` is not persisted or echoed

- **GIVEN** a `--url` whose userinfo carries a token (e.g. `https://<token>@github.com/owner/repo.git`)
- **WHEN** `cassandra install <version> --url <that-url> --branch <branch>` runs
- **THEN** the URL recorded in the cluster state has the token redacted
- **AND** the token does not appear in any log line, error message, or emitted event, whether the
  install succeeds or fails.

#### Scenario: Install failure is reported per host, loudly

- **GIVEN** a version whose install fails on one targeted node (bad branch, unreachable/404 tarball
  URL, or ant build failure) while succeeding on another
- **WHEN** `cassandra install <version>` completes
- **THEN** the command exits non-zero
- **AND** the failure is reported against the specific host it occurred on, naming the version, the
  URLs tried and the failure reason
- **AND** the successful host's install is left in place, with no rollback of a host that already
  succeeded.

#### Scenario: A stale cache object for a URL version is ignored

- **GIVEN** a version with a tarball URL whose origin now serves a newer nightly build
- **AND** the download cache holds an older tarball for that version
- **WHEN** the version is installed
- **THEN** the tarball is downloaded from the URL and the newer nightly build is installed
- **AND** the download cache is not read and not written for that tarball
- **AND** the install log shows one `direct download (not cached): <url>` line for that tarball.

#### Scenario: A URL version is not written to a cold cache

- **GIVEN** a version with a tarball URL and a download cache that holds no object for it
- **WHEN** the version is installed
- **THEN** the tarball is downloaded from the URL
- **AND** no object for that tarball is written to the download cache.

#### Scenario: A failed URL download does not fall back to the cache

- **GIVEN** a version with a tarball URL whose download fails
- **AND** the download cache holds a tarball for that version
- **WHEN** the version is installed
- **THEN** the install fails and names the host, the version and the URL
- **AND** the cached tarball is not read.

#### Scenario: ASF releases use the cache

- **GIVEN** an ASF release and a download cache in the account's object store
- **WHEN** the release is installed
- **THEN** the tarball is fetched through the download cache, under a key named for the full release number
- **AND** the install log shows a `cache hit:` or `cache miss:` line for that release.

#### Scenario: An ASF release that is not in the cache is stored there

- **GIVEN** an ASF release that the download cache does not hold
- **WHEN** the release is installed on a node
- **THEN** the node downloads it from ASF and writes it to the download cache
- **AND** a later install of that release in the same account reads it from the cache.

#### Scenario: An s3 URL is copied directly from S3

- **GIVEN** a version with a tarball URL of the form `s3://...`
- **WHEN** the version is installed
- **THEN** the tarball is copied directly from that S3 location
- **AND** the download cache is not read and not written for that tarball
- **AND** the install log shows one `direct download (not cached): <url>` line for that tarball.

#### Scenario: `cassandra build` and the git path behave as before

- **WHEN** the user runs `cassandra build`, or `cassandra install <name> --url <git> --branch <b>`
- **THEN** the build and the install behave as before this change, except that the parameters are recorded in the cluster state and not in a file on the node.

### Requirement: JDK 25 Selection

The system SHALL let an operator run a Cassandra version on JDK 25 when JDK 25 is in the version's supported JDKs, or when no source gives a supported list.  `use-cassandra` SHALL switch the node to JDK 25 when the CLI passes `--java 25`.

#### Scenario: Select JDK 25 for a version

- **GIVEN** a node with a Cassandra version installed, whose supported JDKs include 25 or are unknown
- **WHEN** the operator runs `cassandra use <version> --java 25`
- **THEN** `use-cassandra` runs `update-java-alternatives -s java-1.25.0-openjdk-<arch>`
- **AND** it does not exit with "Unknown java version"

#### Scenario: Cassandra runs on JDK 25

- **GIVEN** a Cassandra build that supports JDK 25
- **WHEN** the operator runs `cassandra use <version> --java 25` and starts Cassandra
- **THEN** Cassandra runs on JDK 25

## ADDED Requirements

### Requirement: On-demand install in `cassandra use`

`cassandra use <version>` SHALL install the version on each targeted node that does not have it, through the same install path as `cassandra install`, and SHALL then activate it.  A node that has `/usr/local/cassandra/<version>` SHALL NOT download it again.  The CLI SHALL decide which nodes have the version by listing `/usr/local/cassandra` on each node.  When the install fails on any targeted node, `cassandra use` SHALL activate the version on no targeted node, and SHALL exit non-zero.

#### Scenario: `use` installs a missing version

- **GIVEN** a cluster whose db nodes do not have `5.0`
- **WHEN** the user runs `cassandra use 5.0`
- **THEN** each db node downloads and installs `5.0`, and then activates it.

#### Scenario: `use` does not download a version that is on the node

- **GIVEN** a db node that has `/usr/local/cassandra/5.0`
- **WHEN** the user runs `cassandra use 5.0`
- **THEN** that node does not download `5.0`, and activates it.

#### Scenario: `use` with an ASF release that no file names

- **GIVEN** no version file names `4.1.7`
- **WHEN** the user runs `cassandra use 4.1.7`
- **THEN** the nodes install ASF release 4.1.7, and activate it.

#### Scenario: A download fails on a node

- **GIVEN** a version whose download fails on `db1`
- **WHEN** the user runs `cassandra use <version>`
- **THEN** the command exits non-zero, and the message names `db1`, the version and the URL
- **AND** no targeted node is switched to the version.

### Requirement: Cassandra version sources

The CLI SHALL resolve a version name, with no version file, from these sources, in this order:

1. The cluster state: a version that a node of the cluster already records keeps that record's source and URL.
2. A named build in the profile: an entry in a yaml file in `<profile dir>/cassandra_versions/`.
3. `--url`, with or without `--branch`.
4. ASF: a release directory on the ASF dist archive (for example `4.1.7`), or a release line `X.Y` (for example `5.0`), which resolves to the highest GA release of that line.  The node downloads from `https://dlcdn.apache.org/cassandra/` and, when that fails, from `https://archive.apache.org/dist/cassandra/`.
5. cassandra-builds: the `<ref>` of an `apache-cassandra-<ref>-bin.tar.gz` asset on the `nightly` release of `rustyrazorblade/cassandra-builds`.  It includes `5.0-HEAD`, `6.0-HEAD`, `trunk`, `5.0-rustyrazorblade-HEAD` and `6.0-rustyrazorblade-HEAD`.
6. The S3 build catalog of the profile.

No URL that the system uses SHALL point at an easy-db-lab GitHub release.  When no source has the name, the command SHALL fail before it changes any node, and SHALL name the sources it checked.

#### Scenario: An unknown name
- **WHEN** the user runs `cassandra use no-such-version`
- **THEN** the command fails before any node changes, and the message names the profile builds, ASF, cassandra-builds and the build catalog.

#### Scenario: A release line resolves to its latest release

- **GIVEN** the latest ASF 5.0 release is 5.0.6
- **WHEN** the user runs `cassandra use 5.0` on a cluster with no record of `5.0`
- **THEN** each node installs 5.0.6 as `/usr/local/cassandra/5.0`.

#### Scenario: All nodes get the same release

- **GIVEN** `db0` installed `5.0` as release 5.0.6, and ASF has since published 5.0.7
- **WHEN** the user runs `cassandra use 5.0 --hosts db1`
- **THEN** `db1` installs 5.0.6, the release that the cluster state records.

#### Scenario: dlcdn does not have the release

- **GIVEN** an ASF release that dlcdn does not hold and the archive holds
- **WHEN** a node installs it
- **THEN** the node downloads it from `https://archive.apache.org/dist/cassandra/`.

#### Scenario: A branch build comes from cassandra-builds

- **WHEN** the user runs `cassandra use 5.0-rustyrazorblade-HEAD`
- **THEN** each node downloads `https://github.com/rustyrazorblade/cassandra-builds/releases/download/nightly/apache-cassandra-5.0-rustyrazorblade-HEAD-bin.tar.gz`.

### Requirement: JDK and Python choice

The CLI SHALL choose the JDK of a version, and SHALL take the supported JDK list from the first layer that has one:

1. `--java N` on `cassandra use` or `cassandra install`.
2. For a named build in the profile, its entry: its supported JDKs, else its default JDK.  For a profile build, the manifest and the table SHALL NOT be read.
3. The build's manifest: the manifest published beside a cassandra-builds tarball, or the build catalog manifest.
4. The release-line table in the CLI resources: 3.0 and 3.11 support 8; 4.0 and 4.1 support 8 and 11; 5.0 supports 11 and 17; `5.0-rustyrazorblade` supports 11, 17 and 21; 6.0 and trunk support 11, 17 and 21.  A name maps to the longest table entry that is a prefix of it at a `-` boundary or the end of the name, and else to the release line of its leading `X.Y`.

The `5.0-rustyrazorblade` entry SHALL hold the JDKs that `java.supported` sets in the `build.xml` of the fork branch `cassandra-5.0-rustyrazorblade`, and a test SHALL check it against that file.  The entry SHALL be removed when the cassandra-builds manifest gives the supported JDKs of the fork builds.

When `--java N` is given and a lower layer has a list without N, the command SHALL fail before it changes any node, and SHALL list the supported JDKs.  With no `--java`, the CLI SHALL use the JDK that the node records for the version, else the default JDK of a profile build, and else the highest supported JDK.  When no layer has a list and no `--java` is given, the command SHALL fail, and the message SHALL say to pass `--java`.

Python SHALL be `--python` when it is given, else the Python of a profile build, else the default Python version (3.11.9).  The CLI SHALL pass the JDK and Python to the node script as explicit arguments.

#### Scenario: A rustyrazorblade 5.0 build starts on JDK 21

- **GIVEN** no manifest for `5.0-rustyrazorblade-HEAD`
- **WHEN** the user runs `cassandra use 5.0-rustyrazorblade-HEAD` with no `--java`
- **THEN** the `5.0-rustyrazorblade` table entry gives 11, 17 and 21, and the nodes start it on JDK 21.

#### Scenario: `5.0-HEAD` is not matched by the fork entry

- **WHEN** the user runs `cassandra use 5.0-HEAD` with no `--java` and no manifest exists
- **THEN** the 5.0 line gives 11 and 17, and the nodes start it on JDK 17.

#### Scenario: The fork entry matches the fork

- **GIVEN** the `build.xml` of the fork branch `cassandra-5.0-rustyrazorblade`
- **WHEN** the table test runs
- **THEN** it fails when `java.supported` in that file differs from the `5.0-rustyrazorblade` entry.

#### Scenario: ASF 5.0 starts on JDK 17

- **WHEN** the user runs `cassandra use 5.0.6` with no `--java`
- **THEN** the nodes start it on JDK 17.

#### Scenario: An unsupported JDK is refused

- **WHEN** the user runs `cassandra use 5.0.6 --java 21`
- **THEN** the command fails before any node changes, and the message lists 11 and 17.

#### Scenario: No list and no `--java`

- **GIVEN** a version with no manifest list and no release line
- **WHEN** the user runs `cassandra use` or `cassandra install` for it with no `--java`
- **THEN** the command fails, and the message says to pass `--java`.

#### Scenario: A changed JDK is kept

- **GIVEN** `db0` runs `5.0` on JDK 17
- **WHEN** the user runs `cassandra use 5.0 --java 11`, and later `cassandra use 5.0` with no `--java`
- **THEN** the cluster state records JDK 11 for `5.0` on `db0` after the first command
- **AND** the next start and the second `use` use JDK 11.

### Requirement: Cassandra version record in the cluster state

The CLI SHALL keep the Cassandra version record in `state.json`, in the record of each db node.  The cluster state SHALL have no separate map of node versions.  For each db node and each version installed on it, it SHALL record the version, the JDK, the supported JDKs, Python, the source, and the URL with credentials redacted, and for a git build the branch and the ant flags.  For each db node it SHALL record the active version and its JDK.  The CLI SHALL write the state one time, after every targeted host has finished.

A db node SHALL keep only the install directories under `/usr/local/cassandra` and the AxonOps agent files and the systemd drop-in that names the agent of the next start.  No version file SHALL be written to a node.  `cassandra start` SHALL refuse a targeted node that has no active version in the cluster state, as today.

A node that replaces a db node with the same alias SHALL get its active version, JDK and Python from that alias's record.  It SHALL install only the active version, from the recorded source and URL, not from a new resolution.  It SHALL NOT install the other versions that the record lists, and the record of the node SHALL then list only the active version.  A later `cassandra use` or `cassandra install` of another version installs it on that node.

#### Scenario: `use` records each node

- **WHEN** `cassandra use 5.0` succeeds on `db0` and `db1`
- **THEN** `state.json` holds, for `db0` and for `db1`, the active version `5.0`, its JDK, and an installed record of `5.0` with its JDK, supported JDKs, Python, source `ASF` and URL.

#### Scenario: A replaced node gets its version from the state

- **GIVEN** `state.json` records `db1` with active version `5.0-HEAD` on JDK 17, and also `4.1` installed
- **WHEN** the db node `db1` is replaced by a new instance
- **THEN** the new instance installs `5.0-HEAD` from the recorded URL and activates it on JDK 17
- **AND** it does not install `4.1`, and the record of `db1` lists only `5.0-HEAD`.

#### Scenario: Parallel hosts do not lose records

- **GIVEN** a cluster with 50 db nodes
- **WHEN** `cassandra use 5.0` succeeds on all of them
- **THEN** `state.json` holds a record for each of the 50 nodes.

### Requirement: `cassandra list` shows the version sources

`cassandra list` SHALL show the named builds of the profile (source `profile`), the ASF releases, the cassandra-builds builds and the S3 build catalog in one list, with the source of each entry.  It SHALL run with no cluster and exit 0.  When a cluster exists, it SHALL also mark the versions installed on each db node, list every installed version that no source lists, name a node that cannot be listed with its error, and exit non-zero when a node cannot be listed.  A version installed on only some nodes SHALL be shown with the nodes that have it.

The ASF list and the cassandra-builds release list SHALL be fetched one time and cached in the profile directory.  A later run SHALL use the cache and make no call to ASF or GitHub.  `cassandra list --refresh` SHALL fetch both lists again and replace the cache.  When a fetch fails and no cache exists, the command SHALL fail, and the message SHALL name the source and the URL.  When a fetch fails and a cache exists, the command SHALL use the cache, and SHALL say that the cache is stale.

#### Scenario: List with no cluster

- **GIVEN** a profile and no cluster workspace
- **WHEN** the user runs `cassandra list`
- **THEN** it shows the available versions with their sources, and exits 0.

#### Scenario: A profile build is listed

- **GIVEN** a profile build `my-build`
- **WHEN** the user runs `cassandra list`
- **THEN** `my-build` is listed with the source `profile`.

#### Scenario: List with a cluster

- **GIVEN** a cluster in which `db0` and `db1` have `5.0` and `db2` also has `trunk`
- **WHEN** the user runs `cassandra list`
- **THEN** `5.0` is marked installed on all nodes, and `trunk` is marked installed on `db2` only.

#### Scenario: A node cannot be listed

- **GIVEN** a node whose versions cannot be listed
- **WHEN** the user runs `cassandra list`
- **THEN** the output names that node and the error, lists what the other nodes reported, and the
  command exits non-zero.

#### Scenario: The second run uses the cache

- **GIVEN** a profile whose source cache was written by an earlier `cassandra list`
- **WHEN** the user runs `cassandra list`
- **THEN** it makes no call to ASF or GitHub.

#### Scenario: `--refresh` fetches again

- **WHEN** the user runs `cassandra list --refresh`
- **THEN** it fetches the ASF list and the cassandra-builds release list, and replaces the cache.

#### Scenario: A fetch fails with no cache

- **GIVEN** no source cache in the profile, and GitHub cannot be reached
- **WHEN** the user runs `cassandra list`
- **THEN** the command fails, and the message names cassandra-builds and the URL it fetched.

#### Scenario: A fetch fails with a cache

- **GIVEN** a source cache in the profile, and ASF cannot be reached
- **WHEN** the user runs `cassandra list --refresh`
- **THEN** the command shows the cached ASF list, and says that the ASF cache is stale.

### Requirement: AxonOps agents install on demand

When AxonOps is on, `cassandra start` SHALL, before it starts Cassandra on any node, install on each targeted node the AxonOps agent that the agent selection picks for the node's active release and JDK, when the node does not have it.  It SHALL fetch the agent package through the account's download cache.  The JDK SHALL always be the JDK that the CLI chose; the system SHALL NOT change the JDK to fit AxonOps.

When AxonOps ships no agent for the release and the JDK of a targeted node, `cassandra start` SHALL fail before it starts Cassandra on any node.  The message SHALL name the release, the JDK, the agents that AxonOps ships, and `--skip-axonops`.

`cassandra start --skip-axonops` SHALL start Cassandra with no AxonOps agent for that start, and SHALL NOT start `axon-agent`.  Cassandra SHALL load only the agent that the last start selected, and SHALL NOT select an agent by itself.

#### Scenario: The agent installs on demand and connects

- **GIVEN** AxonOps is on, and the nodes run `4.1` on JDK 11
- **WHEN** the user runs `cassandra start`
- **THEN** each node installs the `4.1-agent` agent, Cassandra starts with it, and the agent connects to AxonOps.

#### Scenario: No agent for the release and JDK

- **GIVEN** AxonOps is on, and the nodes run `5.0-rustyrazorblade-HEAD` on JDK 21
- **WHEN** the user runs `cassandra start`
- **THEN** the command fails before Cassandra starts on any node
- **AND** the message names release 5.0, JDK 21, the agents AxonOps ships, and `--skip-axonops`
- **AND** the JDK of the nodes is still 21.

#### Scenario: `--skip-axonops`

- **GIVEN** the case above
- **WHEN** the user runs `cassandra start --skip-axonops`
- **THEN** Cassandra starts on JDK 21 with no AxonOps agent, and `axon-agent` is not started.

### Requirement: Named builds in the profile

The CLI SHALL read named builds from the yaml files in `<profile dir>/cassandra_versions/`.  Each entry SHALL have a name (`version`) and a `url` (a tarball URL, an `s3://` URI, or a git URL), and MAY have a `branch`, the supported JDKs (`java_supported`), the default JDK (`java`), `python` and `ant_flags`.  A profile build SHALL be usable from every cluster of the profile, with no AMI bake and no source checkout.

The resolver SHALL check the profile builds right after the cluster state record, and before `--url`, ASF, cassandra-builds and the build catalog.  The JDK and Python values of an entry SHALL take the place of the manifest and the release-line table.  `--java` and `--python` SHALL override them.  `--java N` SHALL fail when the entry lists supported JDKs and N is not one of them.  A profile build SHALL be fetched from its URL directly, and SHALL NOT be read from or written to the download cache.

An entry with no `url`, a file that cannot be parsed, and a name in two entries SHALL each fail the command before any node changes, and the message SHALL name the file.  The AMI bake SHALL NOT read the profile builds.

#### Scenario: A profile build installs by name

- **GIVEN** a profile build `my-build` with a tarball `url`, `java` 17 and `python` 3.11.9
- **WHEN** the user runs `cassandra use my-build`
- **THEN** each node downloads the tarball from that URL, and activates `my-build` on JDK 17 with Python 3.11.9
- **AND** the cluster state records `my-build` with the source `PROFILE`.

#### Scenario: A profile build wins over the other sources

- **GIVEN** a profile build named `5.0-HEAD` whose `url` points at a private tarball
- **WHEN** the user runs `cassandra use 5.0-HEAD` on a cluster with no record of `5.0-HEAD`
- **THEN** the nodes install the private tarball, not the cassandra-builds nightly, and no call goes to ASF, GitHub or the build catalog for the name.

#### Scenario: The cluster state record wins over a profile build

- **GIVEN** `db0` records `my-build` with URL A, and the profile entry for `my-build` now has URL B
- **WHEN** the user runs `cassandra use my-build --hosts db1`
- **THEN** `db1` installs from URL A.

#### Scenario: The entry's JDKs take the place of the table

- **GIVEN** a profile build `5.0-mine` with `java_supported` 11 and 17, and no `java`
- **WHEN** the user runs `cassandra use 5.0-mine` with no `--java`
- **THEN** the nodes run it on JDK 17, and the release-line table and any manifest are not read.

#### Scenario: `--java` and `--python` override the entry

- **GIVEN** a profile build `my-build` with `java_supported` 11 and 17, `java` 11 and `python` 3.10.6
- **WHEN** the user runs `cassandra use my-build --java 17 --python 3.11.9`
- **THEN** the nodes run it on JDK 17 with Python 3.11.9, and the record holds those values.

#### Scenario: `--java` outside the entry's list is refused

- **GIVEN** a profile build `my-build` with `java_supported` 11 and 17
- **WHEN** the user runs `cassandra use my-build --java 21`
- **THEN** the command fails before any node changes, and the message lists 11 and 17.

#### Scenario: A profile build from a git branch

- **GIVEN** a profile build `my-build` with a git `url`, a `branch`, `java` 11 and `ant_flags`
- **WHEN** the user runs `cassandra install my-build`
- **THEN** each node clones the branch and builds it with `ant`, those flags and JDK 11.

#### Scenario: An entry with no URL

- **GIVEN** a profile file with an entry that has a `version` and no `url`
- **WHEN** the user runs `cassandra install` or `cassandra list`
- **THEN** the command fails before any node changes, and names the file and the entry.

#### Scenario: A name in two entries

- **GIVEN** two profile files that both name `my-build`
- **WHEN** the user runs `cassandra install my-build`
- **THEN** the command fails before any node changes, and names both files.

#### Scenario: The bake does not read the profile builds

- **GIVEN** a profile with named builds
- **WHEN** the Cassandra AMI is baked
- **THEN** the bake does not read the profile directory, and installs no profile build.
