# Cassandra Local Builds Spec

## ADDED Requirements

### Requirement: Local build of a Cassandra checkout

The system MUST support building a Cassandra source checkout on the operator's own machine via
`cassandra build [<dir>] --java <N>`, defaulting `<dir>` to the current directory.

The build MUST run `ant realclean` followed by `ant artifacts -Dno-checkstyle=true` in the
checkout, under the JDK named by `--java`, which is REQUIRED because it forms part of the build's
identity. `JAVA_HOME` MUST be set for the ant process only, never for the calling shell, and the
operator's default JDK MUST NOT be changed.

The system MUST reject a directory that is not a Cassandra git checkout — no `build.xml`, or no
`.git` — naming what is missing, before running any build.

The system MUST derive the version from the `base.version` property declared in the checkout's
`build.xml`, and MUST fail naming that file when the property cannot be read. The version MUST NOT
be inferred from the branch name.

The system MUST build a checkout with uncommitted changes rather than refusing it, MUST warn that
the recorded sha does not fully describe the result, and MUST record the tree as dirty in the
manifest.

The system MUST require no cluster. A build MUST succeed against a profile that has never
provisioned one.

#### Scenario: Building the current checkout

- **GIVEN** the working directory is a Cassandra git checkout whose `build.xml` declares
  `base.version` `5.1`
- **WHEN** the operator runs `cassandra build --java 17`
- **THEN** `ant realclean` and `ant artifacts` run in that directory under a JDK 17 `JAVA_HOME`
- **AND** the resulting binary tarball is published.

#### Scenario: A directory that is not a Cassandra checkout

- **WHEN** the operator runs `cassandra build <dir> --java 17` against a directory with no
  `build.xml`
- **THEN** the command fails naming the missing `build.xml`
- **AND** no build is started and nothing is uploaded.

#### Scenario: A dirty tree builds and is recorded as dirty

- **GIVEN** a checkout with uncommitted changes
- **WHEN** the operator runs `cassandra build --java 17`
- **THEN** the build proceeds
- **AND** the operator is warned that the sha does not fully describe the build
- **AND** the published manifest records `dirty` as true.

#### Scenario: An unavailable JDK names where it was looked for

- **WHEN** the operator runs `cassandra build --java <N>` for a JDK that is not installed
- **THEN** the command fails before building
- **AND** the message lists the locations that were searched.

#### Scenario: A stale artifact is never published as a fresh one

- **GIVEN** a build directory holding more than one `apache-cassandra-*-bin.tar.gz`
- **WHEN** the tarball to publish is selected
- **THEN** the command fails rather than choosing between them
- **AND** the message names the candidates found.

### Requirement: Build identity

A build MUST be named `<version>-[<JIRA>-][<label>-]<YYYYMMDD>-<short-sha>-jdk<java>`, where
`<version>` is the checkout's `base.version` with any `-SNAPSHOT` suffix removed.

The JIRA and label segments MUST be omitted entirely — not filled with a placeholder — when absent.

The ticket MUST come only from `--jira`, and MUST be normalised to upper case so one ticket yields
one prefix. The system MUST NOT infer a ticket from the branch name. A branch naming convention is a
habit that changes, whereas a published name is permanent, so nothing about a build's identity may
depend on one.

The system MUST accept a free-form label via `--name`, so that builds which otherwise differ only by
sha can be told apart at a glance. The label MUST be placed after the ticket and before the date, so
that the version remains the leading segment and a flat listing still sorts by release.

A label MUST be restricted to characters usable in an S3 key segment and a node directory name, and
one that is not MUST be refused, naming the offending characters, rather than silently rewritten —
the name is the build's identity everywhere afterwards, so a mangled label yields a build the
operator did not ask for.

The name MUST be the build's S3 directory, the version a node installs it under, and the name
`cassandra install` accepts.

#### Scenario: A build for a ticket

- **GIVEN** a checkout of `base.version` `5.1` at sha `a1b2c3d` on branch `CASSANDRA-19000-trunk`
- **WHEN** the operator runs `cassandra build --java 17` on 2026-09-05
- **THEN** the build is named `5.1-CASSANDRA-19000-20260905-a1b2c3d-jdk17`.

#### Scenario: A build with no ticket

- **GIVEN** the same checkout on a branch whose name carries no `CASSANDRA-<n>`
- **WHEN** the operator runs `cassandra build --java 17`
- **THEN** the build is named `5.1-20260905-a1b2c3d-jdk17`, with no empty segment where the ticket
  would be.

#### Scenario: The ticket comes only from the flag

- **WHEN** the operator runs `cassandra build --java 17 --jira CASSANDRA-19000`
- **THEN** the name carries `CASSANDRA-19000`.

#### Scenario: A branch name never contributes a ticket

- **GIVEN** a checkout on a branch named `CASSANDRA-19000-trunk`, `21460-cursor-bti`, or
  `cassandra-5.0`
- **WHEN** the operator runs `cassandra build --java 17` with no `--jira`
- **THEN** the build carries no ticket segment in any of those cases.

#### Scenario: A label distinguishes two builds of the same commit

- **GIVEN** a checkout of `base.version` `5.1` at sha `a1b2c3d` on branch `CASSANDRA-19000-trunk`
- **WHEN** the operator runs `cassandra build --java 17 --name flushfix`
- **THEN** the build is named `5.1-CASSANDRA-19000-flushfix-20260905-a1b2c3d-jdk17`
- **AND** the version is still the leading segment.

#### Scenario: An unusable label is refused, not rewritten

- **WHEN** the operator runs `cassandra build --java 17 --name "flush fix"`
- **THEN** the command fails before building
- **AND** the message names the characters that are not allowed.

### Requirement: Published build layout

A published build MUST occupy its own directory, `cassandra-builds/<name>/`, directly under the
profile's account S3 bucket — flat, with no grouping level between the prefix and the build.

That directory MUST hold exactly two objects: the binary tarball, named
`apache-cassandra-<name>-bin.tar.gz` so a downloaded file identifies itself, and `manifest.json`
beside it.

The manifest MUST record the build name, base version, JDK version, full and short git sha, branch,
remote, dirty flag, ticket, ant flags, build timestamp, the profile identity that produced it, and
the tarball's name, size and SHA-256.

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

### Requirement: Builds are discovered from the bucket

The system MUST discover published builds by listing `cassandra-builds/` in the account bucket and
reading each `manifest.json`, and MUST NOT depend on any local record of what was built. A build
published from one machine MUST be discoverable and installable from another using the same
profile.

There MUST be no index object listing builds; the objects in the bucket are the only record.

A manifest that cannot be read MUST be skipped with a warning, and MUST NOT prevent the remaining
builds from being listed.

`cassandra list` MUST show published builds that are not installed on the queried node, marked
distinguishably from installed versions and from declared-but-uninstalled versions, and MUST NOT
list a build that is already installed there as available to install.

#### Scenario: A build published elsewhere is installable here

- **GIVEN** a build published from another machine using the same profile
- **WHEN** the operator runs `cassandra list`
- **THEN** that build is listed as available to install.

#### Scenario: One unreadable manifest does not hide the rest

- **GIVEN** two published builds, one of whose manifests is unreadable
- **WHEN** the operator runs `cassandra list`
- **THEN** the readable build is still listed.

### Requirement: Installing a published build

`cassandra install <name>` MUST install a published build when `<name>` is not a locally declared
version, resolving its parameters from the build's manifest.

The bucket MUST only be consulted when the name is not declared locally, so that installing a
declared version costs no lookup.

The tarball MUST be referenced by an `s3://` URI rather than a presigned HTTPS URL, so that nodes
fetch it with their instance profile and the URL still ends in `.tar.gz`. The node-side fetch
helper MUST accept an `s3://` source and fetch it directly without routing it through the download
cache, which would store a second copy of an object already in the same bucket.

The version's `java` MUST be written into the node's `/etc/cassandra_versions.yaml`, so that
`cassandra use <name>` afterwards selects the JDK the build was produced under.

#### Scenario: Installing a build by name

- **GIVEN** a published build named `<name>` and a running cluster
- **WHEN** the operator runs `cassandra install <name>`
- **THEN** each targeted node fetches the tarball from the account bucket with its instance profile
- **AND** the build is extracted to `/usr/local/cassandra/<name>`
- **AND** `cassandra use <name>` afterwards selects the JDK recorded in the manifest.

#### Scenario: A declared version is resolved without a bucket lookup

- **GIVEN** a version declared in `cassandra_versions.yaml`
- **WHEN** the operator runs `cassandra install <that-version>`
- **THEN** the build catalog is not consulted.

#### Scenario: An unknown name is not silently treated as a build

- **WHEN** the operator runs `cassandra install <name>` for a name that is neither declared nor
  published
- **THEN** the command fails as it does for any undeclared version, requiring the parameters be
  supplied as flags.
