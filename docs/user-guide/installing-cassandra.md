# Configuring Cassandra

This page covers Cassandra version management and configuration. For a step-by-step walkthrough, see the [Tutorial](tutorial.md#part-3-configure-cassandra-50).

## Versions

The AMI has no Cassandra version installed. `cassandra use <version>` installs the version on
every node that does not have it yet, then switches to it. You can name any version that one of
these sources has. The CLI checks them in this order:

| Source | Names | Example |
|--------|-------|---------|
| The cluster state | A version a node of this cluster already installed keeps its recorded URL and build | `5.0` stays at the release it first resolved to |
| [Named builds in your profile](#named-builds-in-your-profile) | Any `version` in `<profile>/cassandra_versions/*.yaml` | `my-build` |
| `--url` | A tarball URL, an `s3://` URI, or a git URL with `--branch` | see [Custom Builds](#custom-builds) |
| ASF | Any release on the Apache dist archive, or a release line | `4.1.7`, `5.0` (the latest 5.0 release) |
| cassandra-builds | The nightly builds of `rustyrazorblade/cassandra-builds` | `5.0-HEAD`, `6.0-HEAD`, `trunk`, `5.0-rustyrazorblade-HEAD` |
| The build catalog | The builds you published with [`cassandra build`](#build-your-working-branch-and-install-it-by-name) | `5.1-CASSANDRA-19000-20260905-a1b2c3d-jdk17` |

A name no source has fails before any node changes, and the message names the sources it checked.
A version name is one path segment: letters, digits, `.`, `_`, `+` and `-`, starting with a letter
or a digit. `install` and `use` print the source, URL and JDK the name resolved to before any node
changes.

A release line such as `5.0` resolves to its latest ASF release the first time this cluster
installs it. Later installs on this cluster, including a replaced node, get that same release.

An ASF release downloads from `dlcdn.apache.org`, and from `archive.apache.org` when dlcdn no
longer has it. It goes through your account's S3 download cache, so after the first download a
node reads it from S3. A cassandra-builds nightly, a `--url` tarball and a profile build always
download from their origin.

Before a node unpacks an ASF release, it checks the tarball against the strongest checksum that
ASF publishes for it: `.sha512`, else `.sha256`, else `.sha1`. The install output names the one it
used, for example `checksum sha512 OK`. ASF publishes `.sha512` for 2.1.21 and later 2.1, 2.2.14 and
later 2.2, 3.0.18 and later 3.0, 3.11.4 and later 3.11, and every 4.x and 5.x release. Older
releases back to 0.7.5 have only `.sha1`, and 0.3.0 to 0.7.4 have no checksum. When the hash does not
match, or no checksum can be read, the install fails and the tarball is removed from the download
cache. A nightly, a catalog build, a profile build and a `--url` tarball have no published checksum
and are not checked.

### JDK choice

The CLI picks the JDK a version runs on. It takes the list of supported JDKs from the first of:

1. `--java N`.
2. The entry of a profile build: its `java_supported`, else its `java`.
3. The manifest of a cassandra-builds build or a build catalog build.
4. The release-line table:

| Release line | Supported JDKs |
|--------------|----------------|
| 3.0, 3.11 | 8 |
| 4.0, 4.1 | 8, 11 |
| 5.0 | 11, 17 |
| 5.0-rustyrazorblade | 11, 17, 21 |
| 6.0, trunk | 11, 17, 21 |

A name uses the longest entry that is a prefix of it at a `-`, else the entry of its leading
`X.Y`. So `5.0-rustyrazorblade-HEAD` runs on JDK 21 and `5.0-HEAD` runs on JDK 17.

A cassandra-builds nightly is compiled on JDK 17 (5.0 lines) or JDK 21 (6.0 and trunk), and
cannot run on a lower JDK, so its list starts there: `5.0-HEAD` supports only 17, and
`cassandra use 5.0-HEAD --java 11` fails before any node changes.

With no `--java`, the CLI uses the JDK the cluster state records for the version on that node,
else the profile build's `java`, else the JDK a build catalog build was built with, else the
highest supported JDK. `--java N` outside the supported
list fails before any node changes and lists the supported JDKs. A version with no list at all
needs `--java`. Python is `--python`, else the profile build's `python`, else 3.11.9. cqlsh on the
node runs under that Python: the node environment sets `CQLSH_PYTHON=/usr/bin/python`, the
alternative `use` switches. Without it, cqlsh picks the distro `python3`, where the `six` that a 4.x
release bundles does not import.

## Quick Start

```bash
# Install and select Cassandra 5.0
easy-db-lab cassandra use 5.0

# Generate configuration patch
easy-db-lab cassandra write-config

# Apply configuration and start
easy-db-lab cassandra update-config
easy-db-lab cassandra start

# Verify cluster
ssh db0 nodetool status
```

## Version Management

### Select a Version

```bash
easy-db-lab cassandra use <version>
```

Examples:
```bash
easy-db-lab cassandra use 5.0                       # Latest 5.0 release, JDK 17
easy-db-lab cassandra use 4.1.7                     # A specific release, JDK 11
easy-db-lab cassandra use 5.0-HEAD                  # Nightly build of the 5.0 branch
easy-db-lab cassandra use 5.0-rustyrazorblade-HEAD  # Nightly build of the fork, JDK 21
```

This command:

1. Installs the version on every targeted node that does not have it. It prints the hosts that
   download.
2. Sets the active Cassandra version on all nodes
3. Downloads current configuration files locally
4. Applies any existing `cassandra.patch.yaml`, to every targeted host, as its last step
5. Switches the JDK to the one the CLI picked (or the one you pass with `--java`)

Step 4 overwrites per-host settings you applied earlier with `update-config --hosts`. Run
`cassandra use` first, then tune individual nodes with `update-config`. `use` prints the hosts it
re-applies the file to before it starts.

The cluster state (`state.json`) records, for each db node, the active version, its JDK, and
every version installed there with its source, URL, JDK and Python. A node that replaces a db
node installs that node's active version from the recorded URL, and nothing else.

### Specify Java Version

```bash
easy-db-lab cassandra use 5.0 --java 11
```

This selects the JDK the version runs under. It does not reinstall or rebuild anything. The AMI
has JDK 8, 11, 17, 21 and 25 installed. The JDK you pick is kept: the next `cassandra use 5.0`
with no `--java`, and the next start, use JDK 11 again. If the JDK switch fails on a node, the
command fails.

### List Available Versions

```bash
easy-db-lab cassandra list
easy-db-lab cassandra list --refresh
```

`cassandra list` works with no cluster. It shows one table of the profile builds, the ASF release
lines (with the latest release and the patch releases of each), the cassandra-builds builds and
the build catalog, with the source of each.

When it runs in a cluster workspace, an `INSTALLED` column shows the nodes that have each version
(`all` when every db node has it). A version installed on a node that no source lists is shown
too. A node that cannot be listed is named with its error, and the command exits non-zero.

The ASF and cassandra-builds lists are fetched once and cached in the profile directory
(`cassandra-sources/`). A later run reads the cache. `--refresh` fetches both lists again. If a
fetch fails and a cache exists, the cached list is shown and marked stale; with no cache, the
command fails and names the URL it fetched.

### AxonOps agents

With AxonOps on, `cassandra start` installs on each node the AxonOps agent for that node's
release and JDK, before it starts Cassandra anywhere. For a build whose name has no `X.Y` release
(say `cep-45`), the release is the one its `apache-cassandra` jar names. If AxonOps ships no agent for that release
and JDK (for example 5.0 on JDK 21), the start fails before Cassandra starts and lists the agents
AxonOps ships. The JDK is never changed to fit AxonOps. To start without the agent this time:

```bash
easy-db-lab cassandra start --skip-axonops
```

`cassandra use` keeps the agent in step: on a node that was started with an agent, it selects the
agent for the new release and JDK, so `cassandra restart` loads the right one. A node started with
`--skip-axonops` stays without an agent.

## Configuration

### The Patch File

Cassandra configuration uses a **patch file** approach. The `cassandra.patch.yaml` file contains only the settings you want to customize, which are merged with the default `cassandra.yaml`.

Generate a new patch file:

```bash
easy-db-lab cassandra write-config
```

Options:
- `-t`, `--tokens`: Number of tokens (default: 4)

Example patch file:
```yaml
cluster_name: "my-cluster"
num_tokens: 4
concurrent_reads: 64
concurrent_writes: 64
trickle_fsync: true
```

```admonish warning title="Auto-Managed Settings — Do Not Include"
The following settings are automatically managed by easy-db-lab. Including them in your patch file may cause problems:

- `listen_address`, `rpc_address` — injected with each node's private IP
- `seed_provider` / `seeds` — configured automatically based on cluster topology
- `hints_directory`, `data_file_directories`, `commitlog_directory` — set based on the cluster's disk configuration
```

### Apply Configuration

```bash
easy-db-lab cassandra update-config
```

Options:
- `--restart`, `-r`: Restart Cassandra after applying
- `--hosts`: Filter to specific hosts

Apply and restart in one command:
```bash
easy-db-lab cassandra update-config --restart
```

### Download Configuration

Download current configuration files from nodes:

```bash
easy-db-lab cassandra download-config
```

Files are saved to a local directory named after the version (e.g., `5.0/`).

## Starting and Stopping

```bash
# Start on all nodes
easy-db-lab cassandra start

# Stop on all nodes
easy-db-lab cassandra stop

# Restart on all nodes
easy-db-lab cassandra restart

# Target specific hosts
easy-db-lab cassandra start --hosts db0,db1
```

## Cassandra Sidecar

The [Apache Cassandra Sidecar](https://github.com/apache/cassandra-sidecar) is automatically installed and started alongside Cassandra. The sidecar provides:

- REST API for Cassandra operations
- S3 import/restore capabilities
- Streaming data operations
- Metrics collection (Prometheus-compatible)

### Sidecar Access

The sidecar runs on port `9043` on each Cassandra node:

```bash
# Check sidecar health
curl http://<cassandra-node-ip>:9043/api/v1/__health
```

### Sidecar Management

The sidecar runs in K3s as the `cassandra-sidecar` DaemonSet, with one pod on each db node.  Each pod uses the host network, so the sidecar answers on the node's own IP.

`cassandra start` deploys the sidecar to every db node, and `cassandra stop` removes it.  A stop limited with `--hosts` leaves the sidecar in place while any other db node still runs the database; the stop that leaves no db node running removes it.  `cassandra restart` restarts the database, then restarts the sidecar pods one at a time.

Use `kubectl` from the cluster workspace after `source env.sh` (see [Kubernetes](kubernetes.md)):

```bash
# See the sidecar pods and the node each one runs on
kubectl get pods -l app.kubernetes.io/name=cassandra-sidecar -o wide

# Read the sidecar logs
kubectl logs -l app.kubernetes.io/name=cassandra-sidecar --tail=100

# Restart the sidecar on every db node
kubectl rollout restart daemonset/cassandra-sidecar
```

### Sidecar Configuration

The configuration is in the `cassandra-sidecar-config` ConfigMap.  When a pod starts, it writes the configuration to `/conf/sidecar.yaml` in the pod, with the node's IP filled in.  Key settings:

- Cassandra connection details
- Data directory paths
- Traffic shaping and throttling
- S3 integration settings

## Custom Builds

To run a custom Cassandra build (your own fork, a feature branch, or a prebuilt
tarball), you can build it on your own machine and publish it, install it onto a
cluster that is already running, or name it in your profile so every cluster can
install it.

### Build your working branch and install it by name

If you are developing Cassandra itself, build the checkout you are already
working in and publish it once, rather than making every node build the same
branch:

```bash
cd ~/dev/cassandra
easy-db-lab cassandra build --java 17
# -> 5.1-CASSANDRA-19000-20260905-a1b2c3d-jdk17

easy-db-lab cassandra install 5.1-CASSANDRA-19000-20260905-a1b2c3d-jdk17
easy-db-lab cassandra use 5.1-CASSANDRA-19000-20260905-a1b2c3d-jdk17
```

The build is published to your profile's S3 bucket, so it outlives the cluster
and installs onto whatever you bring up next. Uncommitted changes are fine — the
manifest records the tree as dirty so you can tell later. `cassandra list` shows
everything you have published, with the source `build`.

See [`cassandra build`](../reference/commands.md#cassandra-build) for the naming
scheme and what the manifest records.

### Install onto a running cluster

`cassandra use` installs a missing version for you. `cassandra install` installs a version
without switching to it:

```bash
easy-db-lab cassandra install <version>
easy-db-lab cassandra use <version>
```

This downloads (or clones and builds) the version on every Cassandra node and records it in the
cluster state. It never rebuilds the AMI.

Where the install parameters come from:

- **A version name** (the default): any version one of the [sources](#versions) has. Nothing
  else to pass.
- **CLI options**, for a one-off test with no file to edit:

  ```bash
  easy-db-lab cassandra install my-build \
    --url https://example.com/apache-cassandra-my-build-bin.tar.gz \
    --java 11
  ```

  Or from a branch, built with ant on each node:

  ```bash
  easy-db-lab cassandra install my-build \
    --url https://github.com/myuser/cassandra.git \
    --branch my-feature-branch \
    --java 11 \
    --ant-flags "-Duse.jdk11=true"
  ```

  `--python` defaults to `3.11.9`.

Useful details:

- `--hosts db0,db1` installs on a subset of nodes only.
- Re-installing a version a node already has is a no-op, as long as you ask for
  the same parameters it was built with. See
  [Re-installing with different options](#re-installing-with-different-options).
- If a node fails, the command exits non-zero and names the node and the reason;
  nodes that succeeded keep their install.
- A branch build runs a full `ant` build on every targeted node, which is
  CPU-heavy and takes several minutes. It is safe while Cassandra is running —
  the install only writes to `/usr/local/cassandra/<version>` — but it is not
  free. Tarball installs are much cheaper.

#### Getting a new nightly build

A nightly such as `5.0-HEAD` is installed once and then kept. To get the current build, stop
Cassandra and install it again:

```bash
easy-db-lab cassandra stop
easy-db-lab cassandra install 5.0-HEAD --reinstall
easy-db-lab cassandra start
```

`--reinstall` downloads or builds the version again and replaces the install. It also re-applies
`cassandra.patch.yaml` to the new install. A node where Cassandra runs the version is refused and
left alone. If the download or build fails on a node, that node keeps its old install. For a git
build, `--reinstall` takes a new `--url`, `--branch` or `--ant-flags`; without one it builds the
recorded one again. `--reinstall --url <git-url> --branch <b>` turns a tarball version into a git
build. Without `--reinstall`, a `--url` other than the one a tarball version was installed from
fails before any node changes, and names the recorded URL. `--branch` without `--url` works only
where a git repository is already known: a profile entry with a git URL, or `--reinstall` of a git
build. For any other version it fails and says to pass `--url`. A git build rebuilt on another JDK
(a replaced node, or `--java`) uses the ant flags for that JDK, not the recorded ones.

#### Re-installing with different options

A version that is already on a node is never rebuilt by a plain `install`. If you re-run
`install` for it with options that contradict what the cluster state records for it — a
different `--java`, `--python`, or `--ant-flags` — the command changes nothing, names the node
and the fields that disagree, and exits non-zero:

```
db0: my-build is already installed, built with the parameters the cluster state records:
java: recorded 11, requested 17. Nothing was changed. To run it on another JDK:
cassandra use my-build --java <N>. To build it again: add --reinstall.
```

What to do instead depends on what you actually want:

- **Run the existing build under a different JDK** — no reinstall needed:

  ```bash
  easy-db-lab cassandra use my-build --java 17
  ```

- **Rebuild with the new options** — stop Cassandra and add `--reinstall`, or install it under a
  different version name (`my-build-jdk17`).

```admonish tip title="Credentials in --url"
A `--url` pointing at a private fork may carry a token
(`https://<token>@github.com/...`). The token is used for the download or clone and is
never written to the cluster state, and is stripped from log lines, error messages, and
emitted events. It does still live in your shell history like any other command argument.
Because the state holds the URL without the token, a node that replaces a db node cannot
install that version by itself; the CLI says so and prints the `cassandra install --reinstall
--url` command to run.
```

### Named builds in your profile

To give a build a name every cluster of your profile can use, drop one or more YAML files into
your profile's `cassandra_versions/` directory:

```
~/.easy-db-lab/profiles/<profile>/cassandra_versions/
```

(The default profile is `default`.) No AMI rebuild is needed: the CLI reads these files when you
run `cassandra use`, `cassandra install` or `cassandra list`, and `cassandra list` shows each
entry with the source `profile`. A profile build is checked before every other source except the
cluster state, so an entry named `5.0-HEAD` replaces the nightly for your profile.

Create e.g. `~/.easy-db-lab/profiles/default/cassandra_versions/my-build.yaml` with a prebuilt
tarball (an `https://` URL or an `s3://` URI):

```yaml
- version: "my-build"
  url: "https://example.com/apache-cassandra-my-build-bin.tar.gz"
  java_supported: ["11", "17"]   # optional
  java: "17"                     # optional, the default JDK
  python: "3.11.9"               # optional
```

Or a git branch, cloned and built with ant on each node:

```yaml
- version: "my-build"
  url: "https://github.com/myuser/cassandra.git"
  branch: "my-feature-branch"
  java: "11"
  ant_flags: "-Duse.jdk11=true"   # optional, passed to ant
```

Then:

```bash
easy-db-lab cassandra use my-build
```

Every entry needs a `version` and a `url`. The entry's `java_supported` and `java` take the place
of the manifest and the release-line table; `--java` and `--python` override them. An entry with
no `url`, a file that cannot be parsed, and a name in two entries each fail the command, and the
message names the file. A profile build always downloads from its URL; it does not use the S3
download cache.

## Next Steps

- [Tutorial](tutorial.md) - Complete walkthrough
- [Shell Aliases](shell-aliases.md) - Convenient shortcuts for Cassandra management
