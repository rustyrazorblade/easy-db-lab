## Context

Today the version data for Cassandra is in one yaml file, and the bake installs from it.

- **Bake.**  `packer/cassandra/install/install_cassandra.sh` reads `/etc/cassandra_versions.yaml` with `yq`, and runs `install-cassandra-version` for each entry that is not `lazy`.  `install_axon.sh` downloads eight per-release AxonOps agent packages into `/usr/share/axonops/<agent>/lib`.  The yaml stays on the node as `/etc/cassandra_versions.yaml`.
- **ASF releases.**  With no `--url`, `install-cassandra-version` scrapes `https://dlcdn.apache.org/cassandra/` for the latest patch of the prefix.  It fetches the tarball through `cached_fetch` (`packer/base/install/edl-cache-lib.sh`) under the key `cassandra-dist/<release>/<archive>`.  The bucket comes from `/etc/edl-cache.env`, which the bake writes with the bucket of the account that baked the AMI.  dlcdn holds only the current releases, so `4.1.7` cannot install.
- **Branch builds.**  The yaml points `5.0-HEAD`, `6.0-HEAD` and `trunk` at the easy-db-lab `nightly` release.  The node fetches them directly, never through the cache.
- **`cassandra install`** (`CassandraInstall.kt`) resolves the parameters from the yaml, the profile `cassandra_versions/` extras or the S3 build catalog, writes the entry into the node's `/etc/cassandra_versions.yaml`, and runs `install-cassandra-version`.
- **`cassandra use`** (`UseCassandra.kt`) runs `set-java-version` when `--java` is given (it edits the node yaml), then `use-cassandra <ver>`, which reads `java` and `python` from the node yaml with `yq`.  It writes `state.versions[alias] = version`.  It does this inside the parallel host lambda, so threads write to one `LinkedHashMap` at the same time.  That is a data race; this change fixes it (D3).
- **`cassandra start`** checks `state.versions` (`Start.kt:102`).  When AxonOps is on, it starts `axon-agent` after Cassandra.  `cassandra.in.sh` loads the agent jar that `edl_axonops_agent_for` selects, and starts Cassandra with no agent and only a `NOTE` when no agent exists.
- **`cassandra list`** (`ListVersions.kt`) needs a cluster, lists `/usr/local/cassandra` on every node, and adds the `lazy` entries and the S3 build catalog.  It emits `Event.Cassandra.VersionList`.
- **State.**  `ClusterState` has `default: NodeState`, `nodes: Map<Alias, NodeState>` with `NodeState(version, javaVersion)`, and `versions: Map<String, String>`.  Only `versions` is written today; `nodes` is always empty, and `default.version` is read only by the status fallback.
- **JDK support of the source branches (read from `build.xml`, 2026-11-16).**  `cassandra-5.0`: default 11, supported 11, 17.  `cassandra-6.0` and `trunk`: 11, 17, 21.  `cassandra-5.0-rustyrazorblade` and `cassandra-6.0-rustyrazorblade` (cassandra-builds): 11, 17, 21.  `cassandra-4.0` and `cassandra-4.1` have no `java.supported`; they run on 8 and 11 and need `-Duse.jdk11=true` to build on 11.  3.0 and 3.11 run on 8 only.
- **cassandra-builds `nightly` release** has today: `apache-cassandra-{5.0-HEAD,6.0-HEAD,trunk,5.0-rustyrazorblade-HEAD,6.0-rustyrazorblade-HEAD}-bin.tar.gz`.  It has no manifest yet; rustyrazorblade/cassandra-builds#1 adds one.

Related work: #961 (epic, step 1 is this change), #1131 (remove the Cassandra AMI), #1132 (remove the easy-db-lab build workflows), #1061 (`ClusterS3Path` becomes `ClusterStoragePath`), #1062 (multi-cloud epic), #1158 (per-node `up` with node replacement, in progress).

## Goals / Non-Goals

**Goals:**
- The bake downloads no Cassandra version and no per-release AxonOps agent.
- `cassandra use <ver>` installs any ASF release, any cassandra-builds build, any S3 catalog build and any named build of the profile on demand.
- The CLI makes every choice (source, URL, JDK, Python, cache location, AxonOps agent) and records it in `state.json`.  The node scripts take explicit arguments and do no lookups.
- The download cache is an object-store location that the CLI names, so a second cloud can fill it.
- The AWS behaviour stays: ASF tarballs go through the account bucket's download cache, and nightly tarballs do not.

**Non-Goals:**
- Removing the Cassandra AMI (#1131), the easy-db-lab workflows and releases (#1132), or moving a version list to cassandra-builds (cassandra-builds#1).
- A GCP image, a GCS adapter, or any GCP code (#1062).
- Node replacement (#1158).  This change gives #1158 a service to call, and does not call #1158 code.
- An automatic check that a nightly changed (an ETag or a date check).  `cassandra install --reinstall` (D12) is the manual way.
- Changes to `cassandra build` or to the git-branch path of `cassandra install`, other than where their parameters come from.

## Decisions

### D1. The version sources, one resolver in the CLI

A new `CassandraVersionResolver` service turns a version name and the CLI options into one `InstallSpec`: the install name, the source, the tarball URL (and the fallback URL for ASF), the cache location (ASF only), the supported JDKs, the chosen JDK, Python, and for a git build the branch and the ant flags.

The resolver checks, in this order:

1. **A record in `state.json`.**  If any node in the cluster has a record for this name, the resolver uses that record's source, URL and supported JDKs.  Thus all nodes get the same build for one name: `use 5.0` on a new node gets the same `5.0.x` as the other nodes, not a newer patch.  A replaced node gets its own record (D7).
2. **A named build in the profile** (D13): an entry in a yaml file in `<profile dir>/cassandra_versions/`.  Source `profile`.
3. **`--url`** (with or without `--branch`): source `url` or `git`, as today.
4. **ASF.**  The name is a release directory on `archive.apache.org/dist/cassandra/` (for example `4.1.7`, `5.0-rc1`), or a release line `X.Y` (for example `5.0`), which resolves to the highest `X.Y.Z` GA release in that line.  The tarball URL is `https://dlcdn.apache.org/cassandra/<rel>/apache-cassandra-<rel>-bin.tar.gz`, and the fallback URL is the same path on `https://archive.apache.org/dist/cassandra/`.
5. **cassandra-builds.**  The name is a `<ref>` of an `apache-cassandra-<ref>-bin.tar.gz` asset on the cassandra-builds `nightly` release.  The URL is that asset's download URL.
6. **The S3 build catalog**, as today.  It is consulted last, so a name of the other sources costs no bucket lookup.

If no source has the name, the command fails before it changes any node, and names the sources it checked.

Why the CLI and not the node: the owner wants the CLI to make the choices and to record them, and the CLI already lists the sources for `cassandra list`.  One resolution for the cluster also means that every node installs the same build.

Alternatives considered:
- Keep the dlcdn scrape on the node.  Each node can then resolve a different patch, the CLI cannot record the URL before the install, and `4.1.7` still fails.
- Download `cassandra_versions.yaml` from cassandra-builds (as #961 first said).  The owner decided on 2026-11-14 that the yaml goes away.

`--reinstall` (D12) changes rule 1 for `cassandra install` only: the record still gives the source and URL, but the CLI options can replace its fields, and the new values are recorded.

### D2. JDK and Python choice

The supported JDK list comes from the first layer that has one:

1. `--java N`.  If a lower layer has a list and N is not in it, the command fails and names the supported JDKs.  If no lower layer has a list, the list is `[N]`.
2. For a named build in the profile, its entry (D13): `java_supported`, else `[java]` when only `java` is set.  The manifest and the table are not read for a profile build.
3. The build's manifest.  For cassandra-builds, the manifest that cassandra-builds#1 publishes beside each tarball (`java.supported`, `java.default`, `ant_flags`).  For the S3 catalog, `CassandraBuildManifest` gets a `javaSupported` list (D8).
4. The release-line table, a resource in the CLI (`cassandra/release-lines.yaml`, read with kaml into a `@Serializable` data class, so it ships in the distribution):

   | Entry                 | Supported JDKs | ant flags (git builds only) |
   |-----------------------|----------------|-----------------------------|
   | 3.0                   | 8              |                             |
   | 3.11                  | 8              |                             |
   | 4.0                   | 8, 11          | `-Duse.jdk11=true` on 11    |
   | 4.1                   | 8, 11          | `-Duse.jdk11=true` on 11    |
   | 5.0                   | 11, 17         |                             |
   | `5.0-rustyrazorblade` | 11, 17, 21     |                             |
   | 6.0                   | 11, 17, 21     |                             |
   | trunk                 | 11, 17, 21     |                             |

   A name maps to the longest entry that is a prefix of it, at a `-` boundary or the end of the name.  Thus `5.0-rustyrazorblade-HEAD` maps to `5.0-rustyrazorblade`, `5.0-HEAD` and `5.0.6` map to 5.0, `4.1.7` maps to 4.1, and `trunk` maps to trunk.  A release line maps from the leading `X.Y` of the name.

   `5.0-rustyrazorblade` is a per-ref entry (owner decision, 2026-11-16).  The fork's branch `cassandra-5.0-rustyrazorblade` sets `java.supported` to `11,17,21`, but the upstream 5.0 line supports only 11 and 17.  The entry stays until the cassandra-builds manifest (cassandra-builds#1) publishes the supported JDKs; then the manifest (layer 3) gives the same list, and a task removes the entry.  An integration-tier test reads `java.supported` from the fork's `build.xml` on that branch and fails when it differs from the entry, so the table cannot drift from the fork without a red test.  `6.0-rustyrazorblade` needs no entry, because the 6.0 line already gives 11, 17 and 21.

With no `--java`, the CLI picks:
- the JDK in the node's record for that version, if one exists (so `use 5.0 --java 11` stays on 11 at the next `use 5.0`); else
- for a profile build, its `java` (the default JDK), if set; else
- the **highest** supported JDK.

If no layer has a list and there is no `--java`, the command fails, and the message says to pass `--java`.

Effects on today's versions: 3.0 and 3.11 stay on 8, 4.0 and 4.1 stay on 11, `5.0-HEAD` stays on 17, `6.0-HEAD` and `trunk` stay on 21, and `5.0-rustyrazorblade-HEAD` and `6.0-rustyrazorblade-HEAD` run on 21.  ASF 5.0 moves from 11 to 17 (**BREAKING**, by owner decision).

Python is `Constants.Cassandra.DEFAULT_PYTHON_VERSION` unless `--python` is given, or a profile build sets `python`.  `--python` overrides the profile value.  The CLI records it, and passes it to `use-cassandra`.

### D3. The version record in `state.json`

`NodeState` grows, and `nodes[alias]` becomes the per-node record:

```kotlin
enum class CassandraSource { ASF, CASSANDRA_BUILDS, BUILD_CATALOG, PROFILE, URL, GIT }

data class InstalledVersion(
    val version: String,              // install name = /usr/local/cassandra/<version>
    val java: String,                 // JDK this node runs the version on
    val javaSupported: List<String>,
    val python: String,
    val source: CassandraSource,
    val url: String,                  // credentials redacted
    val branch: String? = null,       // a git build only (GIT, or a PROFILE entry with a branch)
    val antFlags: String? = null,     // a git build only
)

data class NodeState(
    var version: String = "",         // active version
    var javaVersion: String = "",     // JDK of the active version
    var installed: MutableMap<String, InstalledVersion> = mutableMapOf(),
)
```

- `cassandra install` adds or updates `nodes[alias].installed[name]` for each host that installed it or already had it.
- `cassandra use` does the same, and sets `nodes[alias].version` and `javaVersion`.
- `default` keeps the last cluster-wide choice of `use` (no `--hosts`).  A node with no record of its own (a new node) uses it.
- `ClusterState.versions` is folded into `nodes[alias]` (owner decision, 2026-11-16): the active version is `nodes[alias].version`, and each installed version is an `InstalledVersion` in `nodes[alias].installed`.  The separate `versions` map is removed.  `Start`, `ClusterStatusCollector` and the tests read `nodes`.
- `branch` and `antFlags` are not in the owner's field list.  They are recorded because a replaced node cannot rebuild a git build without them.
- A URL can carry a token.  `state.json` goes to the account bucket in the backup, so the record holds the URL after `redactUrlCredentials`.  A replaced node that needs a private-fork build therefore needs `cassandra install --url` again; the replacement says so.
- Each host returns its result from the parallel lambda.  The command writes the state in one thread after all hosts finish, and saves it one time.  This removes today's race on `state.versions`.

The state uses the existing Jackson mapper of `ClusterStateManager` with `FAIL_ON_UNKNOWN_PROPERTIES = false`, so a `state.json` with the old `versions` key still loads (the key is ignored).  The state classes do not move to kotlinx.serialization in this change.

**What stays on the node, and why.**
- `/usr/local/cassandra/<version>/`: the installs.  The CLI lists this directory to decide whether to download.  The directory is the only proof that the files are on the disk.
- `/usr/share/axonops/<agent>/lib`: the AxonOps agents installed on demand (D6).  The CLI asks the node script, which checks the directory.
- `/etc/systemd/system/cassandra.service.d/axonops.conf`: the agent that the next start loads, or none (D6).  Reason: `cassandra.in.sh` runs under systemd at each start and restart, and the systemd unit is the only channel from the CLI to that process.
- No version record and no yaml.  `/etc/cassandra_versions.yaml` is deleted.

### D4. Node scripts take explicit arguments

`install-cassandra-version` (new contract):

```
install-cassandra-version <name> --url URL [--fallback-url URL] [--cache-uri URI] [--reinstall]
install-cassandra-version <name> --url GIT_URL --branch B --java N [--ant-flags F] [--reinstall]
```

- `--url` is now required.  The "no `--url`" mode and the dlcdn scrape are removed.
- With `--cache-uri`, the node fetches through the cache: it reads the object at the URI; on a miss, it downloads `--url`, then `--fallback-url`, and writes the tarball to the URI.  The CLI gives `--cache-uri` only for ASF releases.  A nightly, a `--url` tarball and an `s3://` catalog build get no `--cache-uri`, so they are fetched directly, as the existing requirement says.
- On failure, the script exits non-zero and prints the version and every URL that it tried.  The CLI adds the host.
- The already-installed no-op stays.  `--reinstall` (D12) skips it: the script installs into a new directory beside the old one, and then replaces `/usr/local/cassandra/<name>` with it, so a failed download leaves the old install in place.

`use-cassandra <version> --java N --python X`: it no longer reads a file and no longer needs `yq`.  It keeps the directory check, the symlinks, the architecture mapping, the JDK switch and the Python switch.

`set-java-version` is deleted.  Its only job was to edit the node yaml; `use-cassandra --java` replaces it.

`edl-cache-lib.sh` gets `cached_fetch_uri <cache-uri> <dest> <url> [<fallback-url>]`.  It selects the copy tool by the URI scheme: `s3://` uses `aws s3 cp`.  An unknown scheme fails with a message that names it.  `cached_fetch` and `/etc/edl-cache.env` stay for the bake scripts.  The runtime install no longer reads `/etc/edl-cache.env`.  This also fixes a latent problem: that file holds the bucket of the account that baked the AMI, which is not the bucket of a user in another account.

`packer/cassandra/patch-jvm-options.py` reads the yaml and has no caller; it prints its arguments and exits 1 before it patches anything.  It is deleted with the yaml.

### D5. The download cache is an object-store location

The CLI names the cache with the storage path type, not with a string that starts with `s3://`:

- `ClusterS3Path.downloadCacheRoot(accountBucket)` = `<account bucket>/download-cache`, next to `cassandraBuildsRoot`.  #1061 renames the type to `ClusterStoragePath`; this change does not wait for it.
- The ASF key is `downloadCacheRoot.resolve("cassandra-dist/<release>/apache-cassandra-<release>-bin.tar.gz")`, the same key as today, so the cache that exists now still hits.
- The AxonOps key is `downloadCacheRoot.resolve("axonops/<package>/<package-version>/<file>.deb")`.
- The node URI is made by the path's `toUri()`.  After #1061 the S3 adapter renders it.  The node script sees only a URI.
- The node writes the cache with its instance role, as today.  The role's S3 policy already covers the account bucket.

#### How a GCP node would fill the cache

Nothing here is built.  This is the path that #1062 takes:
- The GCS `ObjectStore` adapter renders `downloadCacheRoot` as `gs://<bucket>/download-cache/...`.  The CLI code in this change does not change.
- `cached_fetch_uri` gets a `gs://` case that uses `gcloud storage cp` (or `curl` with a metadata-server token).  The GCP image has that tool.
- The node's service account gets read and write on the `download-cache/` prefix of the account bucket, as the AWS instance role has today.
- The ASF and cassandra-builds URLs are public HTTPS, so they need nothing from the cloud.

### D6. AxonOps agents on demand

The bake keeps the `axon-agent` package, the repository key and the sudoers file.  It no longer downloads the eight per-release agent packages.  The download function moves out of `install_axon.sh` into a new node script, `install-axonops-agent <X.Y> <java> --cache-prefix URI`:

1. It runs `edl_axonops_agent_for <X.Y> <java>` (JDK 8 is passed as `1`, as the function expects).  If there is no agent, it exits 3 and prints the agents that AxonOps ships (a new `edl_axonops_agents` function in the same POSIX library, so the list has one source).
2. If `/usr/share/axonops/<agent>/lib` has the jar, it prints the agent name and exits 0.
3. Else it gets the candidate version of the package from apt, and fetches the `.deb` with `cached_fetch_uri` under `<cache-prefix>/<package>/<package-version>/`.  On a miss it runs `apt-get download`.  It unpacks the jar into the agent directory.  The package version is in the key, so a new agent release is a cache miss.

`cassandra start`, when AxonOps is on and `--skip-axonops` is not given:
- **Before it starts Cassandra on any node**, it runs `install-axonops-agent` on every targeted node in parallel, with the node's active release line and JDK from `state.json`.
- If any node has no agent, the command fails, and starts nothing.  The message names the release, the JDK, the agents AxonOps ships and `--skip-axonops`.  The JDK is never changed to fit AxonOps.
- Then it writes `Environment=EDL_AXONOPS_AGENT=<agent>` to the systemd drop-in (D3), runs `systemctl daemon-reload`, and starts Cassandra and `axon-agent` as today.

With `--skip-axonops`, or when AxonOps is off, the drop-in sets `EDL_AXONOPS_AGENT=` (empty), and `axon-agent` is not started.  `cassandra.in.sh` loads the jar of `EDL_AXONOPS_AGENT` only, and no longer calls `edl_axonops_agent_for`.  This removes the silent "no agent" start.  `cassandra restart` uses the drop-in that the last start wrote.

### D7. Node replacement (#1158)

A replaced node keeps its alias, so its `nodes[alias]` record is in `state.json`.  This change adds `CassandraNodeRestorer.restore(host, nodeState)`: it installs **only** the node's active version from its record (same source, URL and cache location, not a new resolution), and runs `use-cassandra` with the recorded JDK and Python (owner decision, 2026-11-16).  It does not install the other versions in `installed`.  They install when a later `use` or `install` names them (D1, rule 1).  The restorer removes from `installed` every record other than the active one, so `state.json` matches the disk of the new instance.  A node with no record uses `default`.

#1158 calls this service from its db-node pipeline.  This change does not call #1158 code.  Whichever change merges second adds the call; if this change merges first, the call is a task of #1158.

### D8. S3 catalog manifests record the supported JDKs

`cassandra build` reads `java.supported` from the checkout's `build.xml` when the property exists, and writes it as `javaSupported` in `manifest.json`.  When the property is absent (4.x and older), it uses the release-line table, and else `[javaVersion]`.  A manifest without the field (an older build) reads as `[javaVersion]`.

### D9. `cassandra list`

- It needs the profile (`@RequireProfileSetup`, for the S3 catalog), not a cluster.
- It shows one table with the columns `VERSION`, `SOURCE` (`profile`, `asf`, `cassandra-builds`, `build`) and, when a cluster exists, `INSTALLED` (the nodes that have it, or `all`).  ASF releases show one row for each release line, with its latest release and the list of its patch releases, so the 100-plus ASF releases fit on one screen.  An installed version that no source lists (for example a `--url` install) shows with its recorded source.
- It is a read-only display command, so it uses `println()` (root `CLAUDE.md`).  `Event.Cassandra.VersionList` is removed.  A node that cannot be listed is named, and the command exits non-zero, as today.
- **Profile cache.**  `<profile dir>/cassandra-sources/asf.json` and `cassandra-builds.json` hold the parsed list and the fetch time (kotlinx.serialization).  With no cache file, the command fetches the list and writes the file.  With a cache file, it makes no call to ASF or GitHub.  `--refresh` fetches both lists and replaces the files.
- A fetch that fails with no cache fails the command, and names the source and the URL.  A fetch that fails with a cache uses the cache, and prints that the cache is stale, with its fetch time.
- The resolver uses the same cache.  When a name looks like an ASF release (`X.Y.Z`) and the cached list does not have it, the resolver fetches the ASF list one time before it fails, so a release that came out after the last fetch installs without `--refresh`.
- The HTTP calls go direct, not through the SOCKS tunnel; ASF and GitHub are public.  They use a plain OkHttp client with timeouts from `Constants`, and resilience4j retry for IO errors.  The GitHub call is anonymous (60 requests an hour), which one call per refresh does not reach.

### D10. Removed with the yaml

- `packer/cassandra/cassandra_versions.yaml`, its packer upload and `/etc/cassandra_versions.yaml`.
- `CassandraVersion` and its unused `axonops`, `jvm_options`, `java_build`, `jvm_config` fields and the `lazy` flag.  `ProfileBuild` (D13) replaces it for the profile entries.
- `DeclaredVersions.kt` (the packaged file merged with the profile extras).  `ProfileBuildSource` (D13) replaces it, and reads only the profile directory.
- The yaml step in `containers/Packer.kt` (the merge of the packaged yaml and the profile extras, `Packer.kt:249`), `Up.downloadCassandraVersions`, the `CASSANDRA_VERSIONS` backup target and `ClusterS3Path.cassandraVersions()`, and the yaml entry in `Clean`.  The bake no longer reads the profile directory.
- The profile `cassandra_versions/` directory stays (D13).
- The yaml check in `.github/cassandra-image/resolve-build-plan.test.sh`.  #1132 deletes that file; until then the check has nothing to read.

### D11. Bake

`install_cassandra.sh` keeps the one-time setup (the `cassandra` user with uid 999, the directories, cqlsh, the default JDK, the `cassandra.in.sh` snippet, the agents library) and loses the install loop and the `yq` functions.  `cassandra.pkr.hcl` loses the yaml provisioner, and its `s3_bucket` comment is corrected: the variable fills `/etc/edl-cache.env`, which the bake's cached fetches (apt cache, Pyroscope agent) read.  `install-axonops-agent` is installed on the node `PATH`.  `edl-cache-lib.sh` is in the base AMI, so `build-image` bakes both images.

### D12. `cassandra install --reinstall`

A nightly build can change under the same name.  The already-installed no-op (D4) then keeps the old build on the node.  `cassandra install <name> --reinstall` is the manual way to get the new build (owner decision, 2026-11-16).  There is no automatic ETag or date check.

- The CLI resolves the name as usual (D1).  The state record still gives the source and the URL, so all targeted nodes get the same build.  `--url`, `--java`, `--python` and `--ant-flags` replace the recorded fields when given.  The git mismatch check (the "parameters a git build contradicts" scenario) does not apply, because the build is made again.
- The CLI passes `--reinstall` to `install-cassandra-version`.  The script skips the directory check, installs into `/usr/local/cassandra/.<name>.new`, and then replaces `/usr/local/cassandra/<name>` with it.  A failed download or build leaves the old install in place and the old record unchanged.
- The fetch rules of the source do not change: a nightly, a `--url` tarball and a catalog build are fetched from their origin; an ASF release goes through the download cache (an ASF release does not change, so a reinstall of it gets the same tarball).
- The CLI writes the new values to `nodes[alias].installed[name]` for each host that succeeded, one time after all hosts finish (D3).
- The conf directory is inside the install directory.  When `<name>` is the active version of a host, the CLI re-applies `cassandra.patch.yaml` to that host after the reinstall, as `use` does.
- When `<name>` is the active version of a host and Cassandra runs on that host, the command fails for that host before it downloads, and says to run `cassandra stop` first.  Replacing the jars under a running JVM breaks the process.
- `cassandra use` has no `--reinstall`.  The sequence is `cassandra install <name> --reinstall`, then `cassandra start`.

### D13. Named builds in the profile

The profile directory `<profile dir>/cassandra_versions/` stays (owner decision, 2026-11-16).  It is the only account-level way to give a custom build a name that every cluster of the profile can use.  It is read by the CLI at resolve time, from the user's profile, so it works for a Homebrew install with no checkout.

- **Format.**  Each `*.yaml` file in the directory holds a list of entries.  An entry is a `@Serializable` `ProfileBuild`, read with kaml:

  | Key              | Required | Meaning                                             |
  |------------------|----------|-----------------------------------------------------|
  | `version`        | yes      | The name (`/usr/local/cassandra/<version>`).        |
  | `url`            | yes      | A tarball URL, an `s3://` URI, or a git URL.        |
  | `branch`         | no       | With a git `url`: the branch to build.              |
  | `java_supported` | no       | The supported JDKs.                                 |
  | `java`           | no       | The default JDK.  It must be in `java_supported`.   |
  | `python`         | no       | The Python version.                                 |
  | `ant_flags`      | no       | With a git `url`: the ant flags.                    |

  The key `version` is kept, so the files that users have today still load.  The removed keys (`lazy`, `axonops`, `jvm_options`, `java_build`, `jvm_config`) are ignored.  An entry with no `url` fails the load and names the file and the entry: the old meaning (an ASF release) is now the ASF source.  A name in two entries fails the load and names both files, as today.  A file that cannot be parsed fails the load and names the file.
- **Resolution.**  The resolver checks the profile builds after the state record and before `--url` (D1, rule 2).  The source is `PROFILE`.  A profile build is fetched from its `url` directly, never through the download cache, as a `--url` build is.  With a `branch`, it is built on the node with `ant`, as a git `--url` build is.
- **JDK and Python.**  The entry's values take the place of the manifest and the release-line table (D2, layer 2).  `--java` and `--python` still override them, and `--java N` fails when N is not in `java_supported`.  An entry with neither `java` nor `java_supported`, and no `--java`, fails and says to pass `--java` or to add `java` to the entry.
- **`cassandra list`** shows each profile build with the source `profile`.
- **The bake no longer reads the directory.**  `Packer.kt` loses the merge (D10).
- **Code.**  `ProfileBuildSource` reads the directory (`Context.cassandraVersionsExtra`, the same path).  It replaces `declaredCassandraVersions` in `DeclaredVersions.kt`, which merged the packaged yaml with the profile files.  `CassandraInstall.candidateVersions` and `ListVersions` use the resolver and the source lists in its place.

Why a profile build is checked before `--url`: a name in the profile is the owner's chosen name for a build.  `cassandra install my-build` must get that build with no other arguments.  `--url` with a profile name replaces the entry's URL for that install (the CLI options win field by field), and the record shows the URL that was used.

### D14. ASF checksums

An ASF release is checked against the strongest checksum ASF publishes for it (owner decision, 2026-11-17): `.sha512`, then `.sha256`, then `.sha1`.  Requiring `.sha512` would stop 171 releases from installing, because `.sha512` is published for 2.1.21 and later 2.1, 2.2.14 and later 2.2, 3.0.18 and later 3.0, 3.11.4 and later 3.11, and every 4.x and 5.x release; older releases back to 0.7.5 publish only `.sha1` (and `.md5`, which is not used); 0.3.0 to 0.7.4 publish none.

- The check runs on the node, in `install-cassandra-version`, after the download (or the cache hit) and before the extract.  The CLI passes `--verify-checksum` for the ASF source only (`InstallSpec.hasPublishedChecksum`); every other source has no published checksum and is not checked.
- The checksum file is read from `--url`, then `--fallback-url`, for each algorithm, with `curl -fsSL` (redirects followed: dlcdn redirects an old release to the archive).
- A mismatch, or no readable checksum file, fails the install and removes the cache object, so a bad tarball is never installed later from the cache.
- The output names the algorithm, so a `sha1` check is visible.

## Conflicts with existing specs

Each conflict below is resolved by a delta in this change.  The owner decisions in #1130 (2026-11-14 and 2026-11-16) are the authority for each one.

- `cassandra` / Multi-Version Support: "on the same AMI".  Now the versions are on the node, and the AMI has none.
- `cassandra` / Runtime Version Installation:
  - "the same mechanism ... at AMI bake time or via `cassandra install`".  The bake no longer installs a version.
  - "resolve ... from a declared `cassandra_versions.yaml` entry (including profile-local extras)", "write ... into the targeted node's `/etc/cassandra_versions.yaml`", the `lazy: true` paragraph and its two scenarios, and the "Repairing an installed-but-undeclared version" scenario.  The packaged yaml is removed, and the record is in `state.json`.  The profile entries stay as named builds (D13).
  - "installing a version that is already in `/usr/local/cassandra/<version>` ... MUST be a safe no-op".  It stays the default; `--reinstall` (D12) is the one exception.
  - "An official release ... MAY fetch it through the S3 download cache".  It is now MUST, and the cache is named as an object-store location.
- `cassandra` / Use Side Effects: "the JDK the version declares".  The CLI now chooses the JDK.
- `cassandra` / JDK 25 Selection: the scenario runs `set-java-version 25`.  That script is deleted.
- `ami-building` / AMI Creation: the scenario "Multiple Cassandra versions still supported" (all versions installed at bake).  It is removed.
- `cassandra-local-builds` / Installing a published build: "`java` MUST be written into the node's `/etc/cassandra_versions.yaml`" and "a locally declared version".  These are replaced by the state record and the source order.
- `cassandra-local-builds` / Builds are discovered from the bucket: "distinguishably from ... declared-but-uninstalled versions".  Declared versions no longer exist.
- `cassandra-branch-publishing` / Published tarball is consumable by the lab install path: names `cassandra_versions.yaml` and `install_cassandra.sh`.  #1132 removes this capability; until then, the delta points it at `cassandra install --url`.
- `cluster-lifecycle` / Cluster State Backup: no conflict.  The backup no longer holds a yaml, and `state.json`, which it holds, now has the version record.  #961's criterion "the backup still has the version list" is met by `state.json`.
- **#961 conflicts.**  #961 says the yaml is downloaded from cassandra-builds and the profile extras still merge on top.  #1130 (owner, 2026-11-14) deletes the yaml.  This change follows #1130.  The profile extras stay, as #961 says, but they are a version source of the CLI (D13), not a merge into a yaml.  #961's out-of-scope item "pre-downloading or caching tarballs ... in S3" conflicts with #1130's "the ASF download goes through the S3 download cache"; this change follows #1130.
- **#1158 interaction.**  #1158's node pipeline must call `CassandraNodeRestorer` for a replaced db node (D7).  No spec of #1158 is changed here.

## Risks / Trade-offs

- [The first `cassandra use` on a cluster downloads on every node, so it is slower than with a baked version.] → The ASF tarball comes from the account cache after the first download in the account.  Nodes install in parallel (`Constants.Scale.HOST_CONCURRENCY`).
- [ASF 5.0 moves from JDK 11 to 17.] → Owner decision; the docs and the `use` output name the JDK.  `--java 11` keeps the old behaviour.
- [A nightly tarball that was built on JDK 17 does not run on 11, but its `java.supported` from `build.xml` includes 11.] → Without `--java`, the CLI picks the highest JDK, so the default works.  When the cassandra-builds manifest records the build JDK, the CLI removes the lower JDKs from the supported list.
- [A version line that the table does not know (for example `5.1` in an S3 build name) with no manifest list.] → The command fails and says to pass `--java`.
- [A nightly already on a node is not downloaded again, so it stays at the build of its first install.] → `cassandra install <name> --reinstall` (D12).  The docs say so.
- [The `5.0-rustyrazorblade` table entry can drift from the fork.] → The integration-tier test against the fork's `build.xml` (D2), and a task to remove the entry when cassandra-builds#1 ships the manifest.
- [A replaced node has only its active version, so the first `use` of another version on it downloads that version.] → Owner decision (D7): a fast replacement matters more.
- [The profile cache never expires.] → `--refresh`, and the one-time refetch for an unknown `X.Y.Z`.  A new patch of a line (`5.0` → `5.0.7`) is seen only after `--refresh`; the docs say so.
- [`state.json` of an old workspace has `versions` and no `nodes` records.] → Clusters are ephemeral; such a cluster has no records, and `use` reinstalls nothing that is on the disk (the directory check comes first) and records it.
- [AxonOps adds an apt lookup and a `.deb` fetch to the first start.] → It runs before any Cassandra start, in parallel, and the cache serves later clusters.

## Migration Plan

No migration.  Clusters are ephemeral.  A new AMI from `build-image` is necessary; an old AMI has versions baked and the old scripts, and the new CLI is not tested against it.

## Open Questions

None.  The owner answered the five questions of the first draft on 2026-11-16:

1. `5.0-rustyrazorblade-HEAD` on JDK 21: a per-ref table entry `5.0-rustyrazorblade` with 11, 17 and 21, a test against the fork's `build.xml`, and a task to remove the entry when the fork ships the manifest (D2).
2. `ClusterState.versions` is folded into `nodes[alias]` and removed (D3).
3. The profile `cassandra_versions/` builds stay, as a version source (D13).
4. A replaced node installs only its active version (D7).
5. `cassandra install --reinstall` (D12).
