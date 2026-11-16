## MODIFIED Requirements

### Requirement: Published build layout

A published build MUST occupy its own directory, `cassandra-builds/<name>/`, directly under the
profile's account S3 bucket — flat, with no grouping level between the prefix and the build.

That directory MUST hold exactly two objects: the binary tarball, named
`apache-cassandra-<name>-bin.tar.gz` so a downloaded file identifies itself, and `manifest.json`
beside it.

The manifest MUST record the build name, base version, JDK version, the supported JDKs, full and
short git sha, branch, remote, dirty flag, ticket, ant flags, build timestamp, the profile identity
that produced it, and the tarball's name, size and SHA-256.

The supported JDKs MUST come from the `java.supported` property of the checkout's `build.xml` when
it has one, else from the release-line table of the CLI, else from the build's JDK alone.  A
manifest that has no supported JDKs (one written before this field existed) MUST be read as
supporting only its build JDK.

The tarball MUST be uploaded before the manifest, so that an interrupted publish leaves a build
that is not listed rather than one that is listed but cannot be installed.

The account bucket MUST be created if it does not yet exist.

#### Scenario: A published build's objects

- **WHEN** `cassandra build` completes successfully for a build named `<name>`
- **THEN** `cassandra-builds/<name>/apache-cassandra-<name>-bin.tar.gz` and
  `cassandra-builds/<name>/manifest.json` both exist in the account bucket
- **AND** the operator is shown the build's location and the command that installs it.

#### Scenario: An interrupted publish does not advertise a broken build

- **GIVEN** a publish that uploaded the tarball and then failed
- **WHEN** the operator runs `cassandra list`
- **THEN** that build is not listed.

#### Scenario: The manifest records the supported JDKs

- **GIVEN** a checkout whose `build.xml` sets `java.supported` to `11,17,21`
- **WHEN** `cassandra build --java 17` publishes it
- **THEN** its `manifest.json` lists 11, 17 and 21 as the supported JDKs.

### Requirement: Builds are discovered from the bucket

The system MUST discover published builds by listing `cassandra-builds/` in the account bucket and
reading each `manifest.json`, and MUST NOT depend on any local record of what was built. A build
published from one machine MUST be discoverable and installable from another using the same
profile.

There MUST be no index object listing builds; the objects in the bucket are the only record.

A manifest that cannot be read MUST be skipped with a warning, and MUST NOT prevent the remaining
builds from being listed.

`cassandra list` MUST show published builds with the source `build`, and MUST mark a build that is
installed on a node as installed there.

#### Scenario: A build published elsewhere is installable here

- **GIVEN** a build published from another machine using the same profile
- **WHEN** the operator runs `cassandra list`
- **THEN** that build is listed with the source `build`.

#### Scenario: One unreadable manifest does not hide the rest

- **GIVEN** two published builds, one of whose manifests is unreadable
- **WHEN** the operator runs `cassandra list`
- **THEN** the readable build is still listed.

### Requirement: Installing a published build

`cassandra install <name>` and `cassandra use <name>` MUST install a published build when `<name>`
is not recorded in the cluster state and no ASF or cassandra-builds source has it, resolving its
parameters from the build's manifest.

The bucket MUST only be consulted when no other source has the name, so that installing an ASF
release or a cassandra-builds build costs no bucket lookup.

The tarball MUST be referenced by an `s3://` URI rather than a presigned HTTPS URL, so that nodes
fetch it with their instance profile and the URL still ends in `.tar.gz`. The node-side fetch
helper MUST accept an `s3://` source and fetch it directly without routing it through the download
cache, which would store a second copy of an object already in the same bucket.

The build's JDK and supported JDKs MUST be recorded in the cluster state for each node that
installs it.  With no `--java`, the build MUST run on the JDK it was built with.

#### Scenario: Installing a build by name

- **GIVEN** a published build named `<name>` and a running cluster
- **WHEN** the operator runs `cassandra install <name>`
- **THEN** each targeted node fetches the tarball from the account bucket with its instance profile
- **AND** the build is extracted to `/usr/local/cassandra/<name>`
- **AND** `cassandra use <name>` afterwards selects the JDK recorded in the manifest.

#### Scenario: An ASF release is resolved without a bucket lookup

- **WHEN** the operator runs `cassandra install 5.0.6`
- **THEN** the build catalog is not consulted.

#### Scenario: An unknown name is not silently treated as a build

- **WHEN** the operator runs `cassandra install <name>` for a name that no source has
- **THEN** the command fails before any node changes, and names the sources it checked.
