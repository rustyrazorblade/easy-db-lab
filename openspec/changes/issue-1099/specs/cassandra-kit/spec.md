## ADDED Requirements

### Requirement: Artifact selection and check before any resource
By default the kit SHALL run a Cassandra release tarball on the `cassandra-runtime` image. The `cassandra-artifact` contributor SHALL resolve the tarball through `CassandraVersionResolver` before any Kubernetes resource is created, from exactly one of these sources:

- `--version`: any name `cassandra use` resolves. An Apache release SHALL resolve to the ASF tarball. A catalog build from `cassandra build` SHALL resolve to a presigned URL.
- `--build <ref>`: a cassandra-builds fixed-name build. It SHALL be resolved only from cassandra-builds, never from ASF.

The result SHALL be a `TarballArtifact`: URL, fallback URL, sha256, build label and JDK. The sha256 SHALL come from the source:

- ASF: the published `.sha256`. When only a `.sha512` is published, the CLI SHALL check one download against the `.sha512` and compute the sha256 from it.
- cassandra-builds: the GitHub asset `digest`.
- Catalog: the manifest's `tarballSha256`.

`--java` SHALL override the JDK, which otherwise comes from `release-lines.yaml`. `--image` SHALL bypass the tarball path: it runs that image with today's pod spec, and keeps the registry manifest check through the workspace `curl` wrapper. No image SHALL be built or pushed for `--version` or `--build`.

`start` SHALL exit non-zero, naming the source, and create no resource, when:

- the version or ref is unknown;
- no digest is available;
- presigning fails;
- `--build` is given together with `--image`.

#### Scenario: Default version runs the Apache tarball
- **WHEN** the kit is started with no `--version`, `--build` or `--image`
- **THEN** the pods run the `cassandra-runtime` image
- **AND** Cassandra runs from the ASF tarball of the latest `5.0` release, checked against its published sha256

#### Scenario: Release on amd64 and arm64
- **WHEN** `kit start cassandra-pods --version 5.0.x` runs on a cluster of amd64 db nodes, and again on a cluster of arm64 db nodes
- **THEN** on each, Cassandra starts from the Apache tarball and every pod reaches UN
- **AND** a CQL client on the operator's machine gets an answer to `SELECT release_version FROM system.local` on port 30942

#### Scenario: Branch build without an image
- **WHEN** the kit is started with `--build 5.0-HEAD`
- **THEN** the pods run that cassandra-builds build's tarball, checked against its GitHub asset digest
- **AND** no image is built or pushed

#### Scenario: Build ref is never read as a release
- **WHEN** `--build 5.0` is given and cassandra-builds has no build named `5.0`
- **THEN** `start` exits non-zero, naming cassandra-builds and `5.0`, and no ASF release is resolved

#### Scenario: Catalog build gets a presigned URL
- **WHEN** `--version` names a build published by `cassandra build`
- **THEN** the pod's tarball URL is a presigned GET URL for the catalog object, and the sha256 is the manifest's `tarballSha256`

#### Scenario: Image override bypasses the tarball path
- **WHEN** the kit is started with `--image ghcr.io/example/cassandra:sha-abc`
- **THEN** the pods run that image with no `cassandra-fetch` or `cassandra-render` init container, whatever `--version` is

#### Scenario: Unknown version fails before any pod
- **WHEN** `start` runs with a `--version` the resolver does not know
- **THEN** `start` exits non-zero with a message naming the version
- **AND** no StatefulSet, Service, ConfigMap or pod is created

#### Scenario: No digest fails before any pod
- **WHEN** the resolved source publishes no sha256, sha512 or digest for the tarball
- **THEN** `start` exits non-zero, naming the tarball URL, and creates no resource

#### Scenario: Build and image together are refused
- **WHEN** `start` runs with both `--build` and `--image`
- **THEN** `start` exits non-zero, naming both options, and creates no resource

#### Scenario: Unknown image fails before any pod
- **WHEN** `start` runs with an `--image` whose manifest the registry does not have
- **THEN** `start` exits non-zero with a message naming the image
- **AND** no StatefulSet, Service, ConfigMap or pod is created

### Requirement: Multi-arch Cassandra runtime image
The image `ghcr.io/rustyrazorblade/cassandra-runtime` SHALL be published for both `linux/amd64` and `linux/arm64`, with one tag per JDK (`<rev>-jre8`, `<rev>-jre11`, `<rev>-jre17`, `<rev>-jre21`) built `FROM eclipse-temurin:<N>-jre`. It SHALL contain curl, python3, procps, and yq at the version `packer/base/install/install_yq.sh` pins. It SHALL run as uid 999. It SHALL contain no Cassandra and no easy-db-lab script. The publish job SHALL fail when either platform is missing from a tag's manifest list. The kit SHALL pick the tag for the resolved JDK.

#### Scenario: Both platforms published
- **WHEN** the publish job builds a `cassandra-runtime` tag
- **THEN** the tag's manifest list has a `linux/amd64` and a `linux/arm64` entry

#### Scenario: Missing platform fails publication
- **WHEN** a built tag's manifest list lacks one of the two platforms
- **THEN** the publish job fails, naming the missing platform

#### Scenario: JDK picks the tag
- **WHEN** the resolved artifact's JDK is 17
- **THEN** the cassandra, fetch and render containers run the `-jre17` tag

#### Scenario: yq matches the AMI
- **WHEN** `yq --version` runs in the image
- **THEN** it prints the version `install_yq.sh` installs

### Requirement: Tarball cached on the claim by checksum
A `cassandra-fetch` init container SHALL look for `/var/lib/cassandra/.edl/artifacts/sha256-<hex>/.complete`, where `<hex>` is `EDL_TARBALL_SHA256`. When that marker exists, it SHALL make no network call. Otherwise it SHALL:

1. download `EDL_TARBALL_URL`, or `EDL_TARBALL_FALLBACK_URL` when the first fails, into a temporary directory on the claim;
2. check the download's sha256;
3. extract it;
4. move the tree into `sha256-<hex>/` and write `.complete` with an atomic rename.

A sha256 mismatch SHALL fail the container with a message naming the expected and the actual hash, and SHALL leave no `sha256-<hex>/` directory. A tree with no `.complete` SHALL never be used. Nothing SHALL change a cached tree after `.complete` is written. Older cached versions SHALL be kept. An expired presigned URL SHALL fail with a message naming the expiry and saying to run `upgrade` again (or `stop` then `start`) to sign a new one.

#### Scenario: Cache hit makes no download
- **WHEN** a pod starts and `sha256-<hex>/.complete` for its `EDL_TARBALL_SHA256` is already on its claim
- **THEN** `cassandra-fetch` makes no network call and Cassandra starts from the cached tree

#### Scenario: Restart with the URL unreachable
- **WHEN** the kit has run once, is stopped, and is started again with the same sha256 but the tarball URL pointing at an unreachable host
- **THEN** every pod reaches UN

#### Scenario: Checksum mismatch
- **WHEN** the downloaded tarball's sha256 differs from `EDL_TARBALL_SHA256`
- **THEN** `cassandra-fetch` exits non-zero with a message naming both hashes
- **AND** no `sha256-<hex>/` directory exists on the claim

#### Scenario: Interrupted extract is not used
- **WHEN** `cassandra-fetch` is killed after extraction starts and before the rename
- **THEN** no `.complete` exists for that sha256, and the next start downloads the tarball again

#### Scenario: Rollback is a cache hit
- **WHEN** a kit upgraded from version A to version B is upgraded back to A
- **THEN** A's tree is still on each claim, and `cassandra-fetch` makes no network call

#### Scenario: Expired presigned URL
- **WHEN** a pod with a cold cache starts after its presigned URL has expired
- **THEN** `cassandra-fetch` exits non-zero with a message naming the expiry and saying to run `upgrade` again, or `stop` then `start`

### Requirement: Config parity with the VM path
A `cassandra-render` init container SHALL build `cassandra.yaml` into an `emptyDir` used as `CASSANDRA_CONF`, with `edl_merge_cassandra_yaml` from `edl-cassandra-config.sh`. It SHALL merge these layers in order:

1. the tarball's `conf/cassandra.yaml`;
2. the workspace `cassandra.patch.yaml`, with the common keys and the user's edits;
3. the kit placement patch: seeds, directories under `/var/lib/cassandra`, the snitch, and `native_transport_port` 30942;
4. the per-pod addresses.

It SHALL also write `cassandra.in.sh` (the tarball's own file, then `cassandra_storagedir=/var/lib/cassandra`, then the packer snippet) into that `emptyDir`, and set `CASSANDRA_INCLUDE` to it. So every directory `cassandra.yaml` leaves unset, such as the Accord journal and `cdc_raw`, defaults onto the claim and never into the per-sha256 cached tree. It SHALL write `cassandra-rackdc.properties` with DC `--dc` and rack `rack1`.

The scripts SHALL reach the pod in the kit ConfigMap `cassandra-pods-config`, copied at build time from `packer/cassandra/`. They SHALL NOT be baked into the image. When the workspace has no `cassandra.patch.yaml` (any environment other than AWS), the contributor SHALL write the common patch itself. For the same version, the rendered `cassandra.yaml` SHALL differ from the VM path's only in addresses, ports, paths and the snitch.

#### Scenario: Same version on both paths
- **WHEN** one version's `cassandra.yaml` is rendered once with the VM layers and once with the pod layers, from the same `cassandra.patch.yaml`
- **THEN** every key that differs is an address, a port, a path, `endpoint_snitch` or `seed_provider`

#### Scenario: User tuning reaches the pods
- **WHEN** the workspace `cassandra.patch.yaml` sets `concurrent_reads: 128` and the kit is started
- **THEN** each pod's rendered `cassandra.yaml` has `concurrent_reads: 128`

#### Scenario: Placement wins over VM keys
- **WHEN** the workspace `cassandra.patch.yaml` holds the `Ec2Snitch`, the `/mnt/db1` directories and VM seed IPs that `write-config` writes
- **THEN** each pod's rendered `cassandra.yaml` has GossipingPropertyFileSnitch, directories under `/var/lib/cassandra` and the pod-0 DNS seed

#### Scenario: No directory defaults into the cached tree
- **WHEN** a pod starts a build whose `cassandra.yaml` leaves `accord.journal_directory` or `cdc_raw_directory` unset
- **THEN** Cassandra runs with `-Dcassandra.storagedir=/var/lib/cassandra`, so those directories sit on the claim and survive an `upgrade` to another sha256

#### Scenario: No workspace patch off AWS
- **WHEN** the kit starts in a workspace with no `cassandra.patch.yaml`
- **THEN** the contributor writes the common patch, and the pods' `cassandra.yaml` carries `cluster_name`, `num_tokens`, the concurrency settings and `trickle_fsync` from it

#### Scenario: Scripts come from the distribution
- **WHEN** the packaged kit resources are listed
- **THEN** they hold `edl-cassandra-config.sh`, `cassandra.in.sh` and `edl-cassandra-agents.sh` identical to the files under `packer/cassandra/`

### Requirement: Upgrade the version on a running kit
The kit SHALL declare a command `cassandra-pods upgrade` that takes `--version` or `--build` (exactly one, and optionally `--java`). It SHALL:

1. resolve the artifact as `start` does;
2. rewrite the kit's recorded version or build arg;
3. apply the StatefulSet with the new artifact env;
4. wait for the rollout to finish and for every node to be UN.

Pods SHALL restart one at a time (`RollingUpdate` with `OrderedReady`). Each pod SHALL drain in `preStop` and become Ready only while CQL is serving. `upgrade` SHALL refuse a major-version downgrade before changing anything, naming both versions. A failed resolution SHALL change nothing.

#### Scenario: Rolling upgrade keeps QUORUM
- **WHEN** a 3-node kit with an RF=3 keyspace takes QUORUM reads and writes, and `upgrade --version` names a newer release
- **THEN** pods restart one at a time, at most one pod is not Ready at any moment, and the QUORUM traffic gets no unavailable errors
- **AND** `upgrade` exits 0 once every node is UN on the new version

#### Scenario: Upgrade changes the pod template
- **WHEN** `upgrade` resolves a tarball with a different sha256
- **THEN** the StatefulSet's pod template has the new `EDL_TARBALL_SHA256`, and the pods' `cassandra_build` label is the new build
- **AND** each node comes back with the rows written before the upgrade and the same host ID

#### Scenario: Recorded arg updated
- **WHEN** `upgrade --version 5.0.4` succeeds and the kit is later stopped and started
- **THEN** the kit starts version 5.0.4

#### Scenario: Major downgrade refused
- **WHEN** `upgrade --version 4.1` runs on a kit running 5.0
- **THEN** `upgrade` exits non-zero, naming both versions, and the StatefulSet is unchanged

#### Scenario: Unknown upgrade target
- **WHEN** `upgrade --build` names a ref cassandra-builds does not have
- **THEN** `upgrade` exits non-zero, naming the ref, and neither the recorded arg nor the StatefulSet changes

### Requirement: CPU request and limit per environment
The kit SHALL take `--cpus`. Its default SHALL be `DB_NODE_ALLOCATABLE_CPU` minus 1, as a whole number of at least 1. When `GUARANTEED_PODS=true`, the cassandra container's CPU request SHALL equal its limit, and every container in the pod SHALL set equal requests and limits, so the pod's QoS class is Guaranteed. Otherwise the container SHALL request `--cpus` and set no CPU limit.

#### Scenario: Guaranteed on owned nodes
- **WHEN** the kit starts with `GUARANTEED_PODS=true` and `DB_NODE_ALLOCATABLE_CPU=8`
- **THEN** the cassandra container requests and limits 7 CPUs, and each pod's QoS class is `Guaranteed`

#### Scenario: No CPU limit elsewhere
- **WHEN** the kit starts with `GUARANTEED_PODS=false`
- **THEN** the cassandra container has a CPU request and no CPU limit

### Requirement: Restarts by config hash and restricted Pod Security
The pod template SHALL carry an `easydblab/config-hash` annotation: a hash of the `cassandra-pods-config` ConfigMap's content and of the artifact env other than its URLs. The URLs SHALL reach `cassandra-fetch` from the `cassandra-pods-artifact` Secret, never from the pod template, so a newly signed URL alone changes nothing in it. So a change to `cassandra.patch.yaml` or to the artifact rolls the pods when `upgrade` applies it, and an `upgrade` with nothing changed does not. `start` refuses a running kit (`collision-check`), so a patch edit reaches running pods through `upgrade` with the running `--version` or `--build`, or through `stop` then `start`. Every container of the tarball path SHALL meet the Pod Security "restricted" profile, and the pod SHALL set `fsGroup: 999`.

#### Scenario: Patch edit rolls the pods
- **WHEN** `cassandra.patch.yaml` is edited and `upgrade` runs on a running kit with the version it already runs
- **THEN** the config-hash annotation changes, the pods restart one at a time with the edited setting, and each keeps its data and host ID

#### Scenario: Start on a running kit is refused
- **WHEN** `start` runs while the kit's pods are in the cluster
- **THEN** it exits non-zero with `Kit.CollisionDetected` before any step, and nothing is applied

#### Scenario: Nothing changed, nothing rolls
- **WHEN** `upgrade` runs on a running kit with the version it already runs and no change to the patch, including a catalog build whose URL is signed again
- **THEN** the pod template is unchanged and no pod restarts

#### Scenario: Restricted namespace admits the pods
- **WHEN** the kit starts in a namespace that enforces Pod Security `restricted`
- **THEN** every pod is admitted and reaches UN

## MODIFIED Requirements

### Requirement: Built-in cassandra-pods kit
easy-db-lab SHALL ship a built-in `db` kit named `cassandra-pods` that runs a Cassandra cluster as pods. The kit SHALL be packaged as classpath resources, so it installs and starts from a Homebrew install with no source checkout. Every install arg SHALL be a named option:

- `--version` (default `5.0`)
- `--build` (default empty)
- `--java` (default empty, meaning the JDK from `release-lines.yaml`)
- `--image` (default empty)
- `--nodes` (default the cluster's db node count)
- `--memory` (default from the environment, see "Memory request, limit and heap")
- `--cpus` (default from the environment, see "CPU request and limit per environment")
- `--heap` (default half of `--memory`)
- `--dc` (default `dc1`)
- `--storage-size` (default `${KIT_STORAGE_SIZE}`)

`kit.yaml` SHALL declare `contributors: [cassandra-artifact]`.

#### Scenario: Install from the classpath
- **WHEN** `kit install cassandra-pods` runs from an install with no source checkout
- **THEN** the kit directory `cassandra-pods/` is written in the workspace from packaged resources
- **AND** `cassandra-pods start`, `cassandra-pods stop` and `cassandra-pods upgrade` are available as commands

#### Scenario: kit.yaml parsed
- **WHEN** the packaged `cassandra-pods/kit.yaml` is parsed
- **THEN** it declares type `db`, the ten named args with their defaults, the contributor `cassandra-artifact`, a `CQL` endpoint of type `native` on port 30942, and Java-agent metrics with service name `cassandra`

### Requirement: Memory request, limit and heap
The cassandra container SHALL request and limit the same memory, taken from `--memory`, so the memory request equals the limit and each pod is scheduled only on a node where that memory fits. When `GUARANTEED_PODS=true`, the default `--memory` SHALL be about 75% of `DB_NODE_ALLOCATABLE_MIB`, rounded down to a whole `Mi`. Otherwise it SHALL be `3Gi`. The pod SHALL set the JVM heap itself, because `cassandra-env.sh` sizes it from the host's memory rather than the container limit: `--heap` when given, otherwise half of the memory limit. `start` SHALL exit non-zero, naming both values, and before any resource is created, when `--memory` is not a whole number of `Gi` or `Mi`, or when `--heap` is not smaller than `--memory`. A pod SHALL become Ready only while the native transport is running.

#### Scenario: Default memory off owned nodes
- **WHEN** the kit is started with `GUARANTEED_PODS=false` and no `--memory` and no `--heap`
- **THEN** the cassandra container requests and limits `3Gi`, the JVM heap is 1536M, and the pod reaches UN without being OOM-killed on a single K3s node of a 7 GB host

#### Scenario: Default memory on owned nodes
- **WHEN** the kit is started with `GUARANTEED_PODS=true`, `DB_NODE_ALLOCATABLE_MIB=30000` and no `--memory`
- **THEN** the cassandra container requests and limits `22500Mi`

#### Scenario: Heap larger than memory
- **WHEN** the kit is started with `--heap 3G --memory 2Gi`
- **THEN** `start` exits non-zero with a message naming `--heap 3G` and `--memory 2Gi`, and creates no resource

#### Scenario: Not Ready while CQL is down
- **WHEN** `nodetool statusbinary` prints `not running`
- **THEN** the readiness probe fails

### Requirement: Telemetry without the AMI
Each pod SHALL load the OpenTelemetry Java agent, copied by an init container from the pinned `autoinstrumentation-java` image whose tag equals the build's OpenTelemetry agent version. It SHALL NOT read any host path the AMI provides. The agent SHALL be configured by the packer `cassandra.in.sh` snippet, through its env overrides. It SHALL send OTLP to the collector on the pod's node, with:

- service name `cassandra`;
- `host.name` set to the node name;
- `node_role=db`;
- `cassandra_build` set to the build label: the resolved artifact's label on the tarball path, or on the `--image` path the image tag (`sha256-` plus the first 12 hex chars of the digest, for a digest-only reference);
- the packaged Cassandra JMX rules.

#### Scenario: Metrics and logs reach the stack
- **WHEN** the kit has been running for two minutes
- **THEN** Mimir returns Cassandra series with `job="cassandra"`, this cluster's `cluster` label, and a `host_name` for each db node running a pod
- **AND** Loki returns Cassandra log lines for this cluster

#### Scenario: Agent version pinned to the build
- **WHEN** the kit's StatefulSet template is rendered
- **THEN** the init container's image tag equals the build's OpenTelemetry agent version

#### Scenario: No AMI host path
- **WHEN** the kit's StatefulSet template is rendered
- **THEN** no volume is a hostPath under `/usr/local/otel`

#### Scenario: host.name kept on the tarball path
- **WHEN** a tarball-path pod's series reach Mimir
- **THEN** they carry `host_name` set to the pod's node name and `cassandra_build` set to the resolved build label

### Requirement: Kit documentation
The kit SHALL have a user-guide page listed in `docs/SUMMARY.md` and in the kit list. The page SHALL cover:

- the args, including `--version`, `--build`, `--java` and `--image`;
- the tarball cache on the claim;
- config parity with the VM path, and its known differences (the heap, and the per-version config directory);
- `upgrade`;
- CQL access, persistence, and the Python 2 cqlsh of 3.x tarballs.

`docs/development/kits.md` SHALL describe the init-container agent pattern and kit variable contributors. The root `CLAUDE.md` SHALL list NodePort 30942.

#### Scenario: Docs list the kit
- **WHEN** the docs are built
- **THEN** the kit's page is in the table of contents, and the kit list names `cassandra-pods`

## REMOVED Requirements

### Requirement: Image selection and check before any resource
**Reason**: The tarball on the `cassandra-runtime` image replaces `cassandra:<version>` images as the default artifact (#1099). The image path is kept only behind `--image`.
**Migration**: Use "Artifact selection and check before any resource". `--version` resolves a tarball, `--build` runs a cassandra-builds build, and `--image` keeps the old behavior, including the manifest check.
