---
name: cassandra-builds
description: Build a local Cassandra source tree and run it on a cluster
---
# Cassandra builds

Build a Cassandra checkout on this machine, publish it to the profile's bucket, then install and run it on a cluster.

Before you build:
- `ant` must be on `PATH`.
- The source directory must be a git checkout of Cassandra, with `build.xml` at its root.
- A JDK of the `--java` version must be installed. The tool looks for it in this order: `JAVA_HOME` (only if its major version matches), `/usr/libexec/java_home -v N`, SDKMAN (`SDKMAN_DIR` or `~/.sdkman`), then `/usr/lib/jvm`.
- The profile must be set up. Run `easy-db-lab profile setup` once.
- `build` needs no cluster. `list`, `install`, and `use` need a running cluster. Run them from the cluster workspace directory.

Steps:
1. Build: `easy-db-lab cassandra build [<dir>] -j N [--jira T] [--name L] [--ant-flags F]`.
   - `<dir>` defaults to `.`, the current directory. `-j`/`--java` is required.
   - The command runs `ant realclean`, then `ant artifacts`. Then it publishes the tarball to the profile's bucket and prints the build name.
   - Uncommitted changes are allowed. The build manifest marks the tree as dirty.
   - Build name format: `<version>-<JIRA>-<name>-<yyyyMMdd>-<sha>-jdk<N>`. Example: `5.1-CASSANDRA-19000-fastpath-20260905-a1b2c3d-jdk17`.
   - The ticket and name segments are dropped when `--jira` or `--name` is absent. Example: `5.1-20260905-a1b2c3d-jdk17`.
   - The version comes from `base.version` in `build.xml`. Nothing is taken from the branch name. The ticket comes only from `--jira`.
   - `--name` allows only letters, digits, `.`, `_`, and `-`, up to 40 characters.
2. List: `easy-db-lab cassandra list`. It shows the installed versions, plus published builds that are not installed yet.
3. Install: `easy-db-lab cassandra install <name>`. It installs the build on every db node. Add `--hosts db0,db1` to install on a subset.
   - Running it again with the same parameters does nothing.
   - Running it again with a different `--java`, `--python`, or `--ant-flags` changes nothing and exits non-zero.
4. Use: `easy-db-lab cassandra use <name> [-j N]`. It switches the active version. `-j`/`--java` overrides the Java version. Add `--hosts db0,db1` to switch a subset.

Limits of `cassandra list`:
- It reads only the first db node. A build installed with `--hosts` on other nodes can show as installable.
- With no running cluster or no db node, it fails. It does not list the published builds.

Related: `cassandra`.
