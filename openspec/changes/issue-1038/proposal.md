## Why

A plan that uses Cassandra can only run on the AMI-based db nodes, where Cassandra is a systemd service started over SSH. The same plan therefore cannot run on any other environment, including the local K3s-in-docker environment in epic #1032 (decision Q6: Cassandra becomes a kit that runs as pods; the AMI `cassandra` commands stay AWS-only). The `postgres`, `clickhouse` and `neo4j` kits already run databases as pods, and a spike pod with the OpenTelemetry Java agent sent Cassandra metrics to Mimir. This change makes that a built-in kit. It is useful on AWS as soon as it lands, before any local work.

While tracing the kit's name, a bug turned up: `CommandLineParser.registerDynamicKitSubcommands` silently skips a workspace kit whose name matches a built-in command, so the kit installs but none of its commands exist. This change fixes that bug.

## What Changes

- **New built-in `db` kit `cassandra-pods`** under `src/main/resources/com/rustyrazorblade/easydblab/kits/cassandra-pods/`:
  - A plain StatefulSet with one pod per `type=db` node (required pod anti-affinity), `OrderedReady` start, a headless Service for gossip, and pod 0 as the seed.
  - GossipingPropertyFileSnitch with DC `--dc` (default `dc1`) and the single rack `rack1`.
  - CQL from the operator's machine: each pod advertises its node IP as `broadcast_rpc_address`. Its native port is set to the NodePort value 30942. A NodePort Service maps 30942 to 30942 with `externalTrafficPolicy: Local`, so every peer address the driver learns reaches the right pod.
  - The OpenTelemetry Java agent is copied from the pinned, multi-arch `autoinstrumentation-java:<agent version>` image by an init container into an emptyDir. There is no AMI hostPath. It sends OTLP to the node's collector with `OTEL_SERVICE_NAME=cassandra` and the existing JMX rules (as a ConfigMap), so the series arrive under `job="cassandra"`.
  - Named install args: `--version` (default `5.0`, image `cassandra:<version>`), `--image` (any image reference; overrides `--version`), `--nodes` (default the db node count), `--heap`, `--dc` and `--storage-size` (default `10Ti`).
  - `start` first checks that the chosen image exists in its registry, through an anonymous manifest lookup with the workspace `curl` wrapper. It fails before any resource is created when the image is unknown, and when the AMI-based Cassandra is running on the cluster. It then waits until `nodetool status` reports the requested number of nodes as UN.
  - `stop` deletes the StatefulSet, both Services and the ConfigMap, and keeps the PersistentVolumeClaims, so data survives `stop` then `start`. `uninstall` deletes the volumes.
  - A metrics catalog, a README, and the kit appears in the existing `dashboards/cassandra/` dashboards through matching labels.
- **Kit-name collision handling** (bug fix): a workspace kit whose name matches a built-in command is reported at startup with an event that names the kit, instead of being dropped silently. `kit install` refuses an instance name that matches a built-in command before it writes anything.
- **Docs**: a user-guide page for the kit and its `docs/SUMMARY.md` entry, the kit list in `docs/user-guide/kits.md`, the init-container agent pattern and the reserved port in `docs/development/kits.md`, and the NodePort list and the collision rule in the root `CLAUDE.md`.

## Capabilities

### New Capabilities
- `cassandra-kit`: the `cassandra-pods` kit's install args, image check, topology, CQL access, persistence, telemetry, dashboards and docs.

### Modified Capabilities
- `kit-command-group`: a kit whose name matches a built-in command is reported, never silently skipped, and `kit install` refuses that name.

## Impact

- Resources: new `kits/cassandra-pods/` (`kit.yaml`, StatefulSet, headless Service and NodePort Service templates, README, `CLAUDE.md`, `metrics-catalog.json`).
- Kotlin: `CommandLineParser.kt` (collision reporting), `commands/install/KitInstallCommand.kt` (refuse a reserved name), a new event in `events/Event.kt`, `Constants.kt` (CQL NodePort and agent image).
- Build: `build.gradle.kts` copies `configuration/cassandra/cassandra-jmx-rules.yaml` into the kit's resources, so the rules have one source.
- Tests: unit tests for parsing and rendering, the agent image tag, and the collision; a shell test for the image check; a K3s TestContainers integration test for UN, CQL on the NodePort, and persistence across stop and start.
- No change to the AMI path, `packer/cassandra/bin`, or the SSH-based `cassandra` commands.
