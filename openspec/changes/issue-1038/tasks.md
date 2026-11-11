## 1. Kit-name collision (bug fix)

- [x] 1.1 Write a `CommandLineParser` test: a workspace kit directory named after a built-in command emits a collision event naming both, the built-in stays, and a non-colliding kit registers with no event.
- [x] 1.2 Add `Event.Kit.NameCollision(kit, command)` in `events/Event.kt` with KDoc, and emit it from `registerDynamicKitSubcommands` instead of returning silently.
- [x] 1.3 Write a `KitInstallCommand` test: an instance name matching a built-in command exits non-zero, names the command, and writes no directory.
- [x] 1.4 Refuse that name in `KitInstallCommand` before any write.

## 2. Constants and build

- [x] 2.1 Add `Constants.Kit` entries: the CQL NodePort 30942, the `autoinstrumentation-java` image name, and the UN wait timeout.
- [x] 2.2 In `build.gradle.kts`, copy `configuration/cassandra/cassandra-jmx-rules.yaml` into the kit's resources at build time, so the rules have one source.
- [x] 2.3 Write a test that the agent image tag the kit renders equals `libs.versions.opentelemetry-agent`.

## 3. Kit resources

- [x] 3.1 Write a test that parses `kits/cassandra-pods/kit.yaml`: type `db`, the seven named args and their defaults, the `CQL` endpoint on 30942, and Java-agent metrics with service name `cassandra`.
- [x] 3.2 Add `kits/cassandra-pods/kit.yaml` per the design outline, with `install`, `start`, `stop` and `uninstall`.
- [x] 3.3 Write a rendering test for the StatefulSet template:
  - `--nodes` replicas, `OrderedReady`, required anti-affinity on `kubernetes.io/hostname`, node selector `type=db`;
  - `broadcast_rpc_address` from `status.hostIP` and native port 30942;
  - the init container image and an emptyDir;
  - no hostPath under `/usr/local/otel`;
  - the `host.name`, `node_role` and `cassandra_build` attributes;
  - the claim template selecting the kit's PVs.
- [x] 3.4 Add the StatefulSet, headless Service and NodePort Service (`externalTrafficPolicy: Local`) templates.
- [x] 3.5 Write a shell test for the start check, with a stubbed `curl`:
  - a known image passes;
  - an unknown image exits 1 and names it;
  - `--image` overrides `--version`;
  - `--nodes` above the db node count exits 1;
  - a running AMI Cassandra exits 1.
- [x] 3.6 Write the start-check and UN-wait scripts. They source `EDL_KIT_LIB`, and the UN wait prints recent events on timeout.
- [x] 3.7 Add `metrics-catalog.json`, `README.md.template` and the kit's `CLAUDE.md`.

## 4. Integration

- [x] 4.1 Write a K3s TestContainers integration test on a node labelled `type=db`:
  - install and start the kit;
  - reach UN;
  - answer `SELECT release_version FROM system.local` on the NodePort;
  - write a row;
  - stop, and check that no pod, StatefulSet, Service or ConfigMap remains while the claims do;
  - start again and read the row back.
- [x] 4.2 Check that the kit resolves from the classpath with no `appHome` (Homebrew install).

## 5. Docs

- [x] 5.1 Add `docs/user-guide/cassandra-pods.md` (args, `--image` with branch builds, CQL access, persistence, the AMI-Cassandra check) and its `docs/SUMMARY.md` entry.
- [x] 5.2 Add `cassandra-pods` to the kit list in `docs/user-guide/kits.md`.
- [x] 5.3 In `docs/development/kits.md`, describe the init-container agent pattern and reserve port 30942.
- [x] 5.4 In the root `CLAUDE.md`, add 30942 to the kit NodePort list, and add the rule that a kit name must not match a built-in command.

## 6. Verify

- [x] 6.1 Ran `./gradlew ktlintFormat detekt test` fresh on JDK 21 (all pass). `integrationTest` was not run locally; CI is its gate.
- [x] 6.2 Run `openspec validate issue-1038 --strict`.
