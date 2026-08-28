# Configuring Cassandra

This page covers Cassandra version management and configuration. For a step-by-step walkthrough, see the [Tutorial](tutorial.md#part-3-configure-cassandra-50).

## Supported Versions

easy-db-lab supports the following Cassandra versions:

| Version | Java | Notes |
|---------|------|-------|
| 3.0 | 8 | Legacy support |
| 3.11 | 8 | Stable release |
| 4.0 | 11 | First 4.x release |
| 4.1 | 11 | Current LTS |
| 5.0 | 11 | **Latest stable (recommended)** |
| 5.0-HEAD | 11 | Nightly build from 5.0 branch |
| 6.0-HEAD | 21 | Nightly build from 6.0 branch |
| trunk | 17 | Development branch |

## Quick Start

```bash
# Select Cassandra 5.0
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
easy-db-lab cassandra use 5.0       # Latest stable
easy-db-lab cassandra use 4.1       # LTS version
easy-db-lab cassandra use trunk     # Development branch
```

This command:

1. Sets the active Cassandra version on all nodes
2. Downloads current configuration files locally
3. Applies any existing `cassandra.patch.yaml`

It fails if the version is not installed on a targeted node rather than leaving
the node pointed at something that isn't there. Install it first — see
[Custom Builds](#custom-builds).

### Specify Java Version

```bash
easy-db-lab cassandra use 5.0 --java 11
```

This selects the JDK the already-installed build runs under. It does not
reinstall or rebuild anything.

### List Available Versions

```bash
easy-db-lab ls
```

Versions installed on the node are listed first. A version declared with
`lazy: true` that has not been installed is listed too, marked
`(declared, not installed)`.

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

The sidecar is managed via systemd:

```bash
# Check status
ssh db0 sudo systemctl status cassandra-sidecar

# Restart
ssh db0 sudo systemctl restart cassandra-sidecar
```

### Sidecar Configuration

Configuration is located at `/etc/cassandra-sidecar/cassandra-sidecar.yaml` on each node. Key settings:

- Cassandra connection details
- Data directory paths
- Traffic shaping and throttling
- S3 integration settings

## Custom Builds

To run a custom Cassandra build (your own fork, a feature branch, or a prebuilt
tarball), you can either install it onto a cluster that is already running, or
bake it into the AMI so every future cluster has it.

### Install onto a running cluster

```bash
easy-db-lab cassandra install <version>
easy-db-lab cassandra use <version>
```

This downloads (or clones and builds) the version on every Cassandra node and
records it in each node's version list, so `cassandra use` works afterward. It
never rebuilds the AMI, so nothing is preserved across a `down`/`up` cycle —
install it again on the new cluster.

Where the install parameters come from:

- **A declared entry** (the default): whatever you declared in your profile's
  extras directory, described below. Nothing else to pass.
- **CLI options**, for a genuinely one-off test with no file to edit:

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

  An option you pass overrides the declared entry's value for that field;
  anything you leave out falls back to the declared entry. `--python` defaults
  to `3.11.9`.

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

#### Re-installing with different options

A version that is already on a node is never rebuilt. If you re-run `install`
for it with options that contradict what the node recorded when it was built —
a different `--java`, `--python`, or `--ant-flags` — the command changes nothing,
names the node and the fields that disagree, and exits non-zero:

```
db0: my-build is already installed, built with the parameters this node declares —
java: declared 11, requested 17. Nothing was changed. To change the java version
in use, run: cassandra use my-build --java <version>
```

This is deliberate: the bits on disk were compiled against Java 11, so silently
recording them as a Java 17 build would leave every later `cassandra use`
picking the wrong JDK.

What to do instead depends on what you actually want:

- **Run the existing build under a different JDK** — no reinstall needed:

  ```bash
  easy-db-lab cassandra use my-build --java 17
  ```

- **Genuinely rebuild with the new options** — install it under a different
  version name (`my-build-jdk17`), or remove `/usr/local/cassandra/<version>` on
  the affected nodes and install again.

```admonish tip title="Credentials in --url"
A `--url` pointing at a private fork may carry a token
(`https://<token>@github.com/...`). The token is used for the clone and is never
written to the node's version list, never stored in the cluster workspace, and is
stripped from log lines, error messages, and emitted events. It does still live
in your shell history like any other command argument.
```

### Bake into the AMI

You don't edit the repository's `cassandra_versions.yaml`. Instead, drop one or
more YAML files into your profile's extras directory:

```
~/.easy-db-lab/profiles/<profile>/cassandra_versions/
```

(The default profile is `default`.) At build time these are merged with the
built-in versions. Each `version` must be unique across the built-in list and
your extras, or the build fails.

### 1. Add a version entry

Create e.g. `~/.easy-db-lab/profiles/default/cassandra_versions/my-build.yaml`.

Build from a git branch (cloned and compiled with ant during the AMI build):

```yaml
- version: "my-build"
  java: "11"
  python: "3.10.6"
  url: "https://github.com/myuser/cassandra.git"
  branch: "my-feature-branch"
  ant_flags: "-Duse.jdk11=true"   # optional, passed to ant
```

Or install a prebuilt tarball:

```yaml
- version: "my-build"
  java: "11"
  python: "3.10.6"
  url: "https://example.com/apache-cassandra-my-build-bin.tar.gz"
```

An entry with no `url`/`branch` downloads the matching official Apache release.

### 2. Rebuild the Cassandra AMI

```bash
easy-db-lab build-cassandra
```

### 3. Select the custom build

```bash
easy-db-lab cassandra use my-build
```

### Declaring a version without baking it

Add `lazy: true` to an entry to declare a version without spending AMI build
time on it:

```yaml
- version: "my-build"
  java: "11"
  python: "3.11.9"
  url: "https://github.com/myuser/cassandra.git"
  branch: "my-feature-branch"
  lazy: true
```

The AMI build skips the download/clone/build for that entry, but the entry
still ships in the image. `easy-db-lab cassandra list` shows it as
`(declared, not installed)`, and `easy-db-lab cassandra install my-build`
installs it on a running cluster without you re-specifying any of its fields.

## Next Steps

- [Tutorial](tutorial.md) - Complete walkthrough
- [Shell Aliases](shell-aliases.md) - Convenient shortcuts for Cassandra management
