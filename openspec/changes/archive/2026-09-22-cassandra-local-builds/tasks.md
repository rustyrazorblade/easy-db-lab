# Tasks

- [x] `CassandraBuildManifest` — the manifest record, the build-name derivation, and the JIRA sniff.
- [x] `CassandraBuildService` — validate the checkout, read `base.version`, capture git state,
      resolve a local JDK, run `ant realclean` + `ant artifacts`, locate the tarball, digest it.
- [x] `CassandraBuildCatalog` — publish to `cassandra-builds/<name>/`, list, find, and express a
      manifest as an installable `CassandraVersion`.
- [x] `CassandraBuild` command, registered under the `cassandra` group.
- [x] `ClusterS3Path.cassandraBuildsRoot` for the account-level prefix.
- [x] `Event.Cassandra.BuildStarting` / `BuildArtifactReady` / `BuildPublished`; `VersionList`
      gains a `builds` field.
- [x] `cassandra list` reports published builds not yet installed.
- [x] `cassandra install` resolves a published build by name.
- [x] `cached_fetch` accepts an `s3://` source.
- [x] Unit tests for name derivation, manifest round-trip, `base.version` parsing, tarball
      location, and digesting.
- [x] Docs: `cassandra build` in `docs/reference/commands.md`, the build-and-install workflow in
      `docs/user-guide/installing-cassandra.md`, and the `list`/`install` sections updated.
