## Context

Issue #1038 is a child of epic #1032 (local execution environment). The owner decided (Q6) that Cassandra becomes a kit that runs as pods on any environment, while the SSH-based `cassandra` commands and `packer/cassandra/bin` stay on VM-image environments. That is AWS today, and every VM cloud (GCP, Azure) under the multi-cloud epic #1062 decision D16, once #961 removes the Cassandra AMI. They never run on the local or bring-your-own Kubernetes environments. The spike on `explore/local-runtime` ran one Cassandra pod with OTel agent v2.31.1 on a local cluster, and 49 Cassandra series reached Mimir.

The reference kits are `neo4j` (a StatefulSet on a db node, Java-agent metrics, NodePort clients dial on the node IP), `postgres` (NodePort 30432) and `clickhouse`. The dashboards in `dashboards/cassandra/` filter on `job="cassandra"`, `cluster`, `host_name` and `cassandra_build`.

## Goals / Non-Goals

**Goals**
- One plan runs Cassandra the same way locally and on AWS: `kit install cassandra-pods`, then `cassandra-pods start`.
- CQL works from the operator's machine through the NodePort, over SOCKS today and over a direct connection later.
- Metrics and logs reach the stack, and the existing Cassandra dashboards show the kit's pods unchanged.
- Data survives `stop` then `start`.
- Works from a Homebrew install with no source checkout.
- Fix the silent kit-name collision.

**Non-Goals**
- `packer/cassandra/bin`, the SSH-based `cassandra` commands, and a containerized db node.
- An operator (cass-operator, k8ssandra), multi-DC, and rack-per-zone topology.
- Stress-tool integration (ask the owner first).
- Multi-arch GHCR images (#1036).

## Decisions

All nine decisions below were approved by the owner on 2026-10-31.

### D1. Kit name `cassandra-pods`
The name is descriptive and does not collide with the built-in `cassandra` command group.
- *Rejected:* `cass` (cryptic); merging into the built-in `cassandra` group (mixes the SSH and pod paths that Q6 keeps apart).

### D2. Collisions are reported and refused, fixed in this change
- `CommandLineParser.registerDynamicKitSubcommands` emits `Event.Kit.NameCollision(kit, command)` for a workspace kit whose name matches a built-in subcommand, instead of returning silently.
- `KitInstallCommand` refuses an instance name that matches a built-in subcommand before it writes anything.
- *Rejected:* a warning only (the kit would still be unusable).

### D3. A plain StatefulSet
- One pod per `type=db` node through required pod anti-affinity on `kubernetes.io/hostname`.
- `podManagementPolicy: OrderedReady`, so nodes join one at a time.
- A headless Service `cassandra-pods-gossip` for stable DNS; the seed is pod 0's DNS name.
- `--nodes` defaults to the db node count and is capped by it.
- *Rejected:* cass-operator or k8ssandra. They bring rack awareness and repair, but also an extra operator, CRDs, cert-manager (k8ssandra) and slower iteration. Revisit if multi-DC or rack topology comes into scope.

### D4. Clients connect to the node IP; the native port is the NodePort
- Each pod sets `broadcast_rpc_address` to `status.hostIP` and `native_transport_port` to 30942.
- The NodePort Service maps 30942 to 30942 with `externalTrafficPolicy: Local`.
- So every address the driver learns from `system.peers_v2` (node IP and 30942) lands on the pod on that node. This is the neo4j "clients dial the node" pattern; there is no `hostNetwork` and no `hostPort`.
- *Rejected:* advertising pod IPs, which works only with Cilium ENI over SOCKS and fails on flannel and locally.

### D5. The agent comes in through an init container
- An init container copies `javaagent.jar` from the pinned, multi-arch `ghcr.io/open-telemetry/opentelemetry-operator/autoinstrumentation-java:<version>` image into an emptyDir. `<version>` equals `libs.versions.opentelemetry-agent`, and `Constants` holds the image.
- Cassandra loads the jar through `JVM_EXTRA_OPTS` with flags copied from `packer/cassandra/cassandra.in.sh`.
- *Rejected:* the neo4j-style `/usr/local/otel` hostPath, which only the AMI provides (AWS-only until #1037).

### D6. The version check is a registry manifest lookup
- The first `start` step resolves `--image` (or `cassandra:<--version>`) to a registry, repository and tag.
- It gets an anonymous token (Docker Hub: `auth.docker.io`, official images under `library/`; other registries: their `/token` endpoint), then sends `HEAD /v2/<repo>/manifests/<tag-or-digest>` through the workspace `curl` wrapper, accepting OCI index, OCI manifest, Docker manifest list v2 and Docker manifest v2.
- A failed token request (not 401, 403 or 404) exits 1 naming the image. A 401 or 403 on the manifest (a private image) warns and continues.
- A 404 or any other failure exits 1 naming the image, before any resource is created.
- *Rejected:* local `skopeo` (Homebrew users may not have it); a per-kit allow-list (blocks branch builds).

### D7. Claims are kept on `stop`
- `stop` deletes the StatefulSet, both Services and the ConfigMap by the kit's label selector, never the PVCs.
- A restarted StatefulSet reattaches each claim by name, so data survives.
- `uninstall` runs `platform-pvs-delete`. This closes the Cassandra case of #985 (kit data lost on stop and start).
- *Rejected:* deferring to #985's general fix.

### D8. NodePort 30942
No kit uses 30942 today (kits use 30081, 30123, 30300, 30400, 30432, 30474, 30687, 30800, 30900, 30904, 30905 and 30936). `Constants` holds the port, and the root `CLAUDE.md` port list records it.

### D9. A single rack `rack1`
GossipingPropertyFileSnitch with `dc=--dc` (default `dc1`) and `rack=rack1`.
- *Rejected:* one rack per zone. It needs node labels copied into each pod: more moving parts for no current need.

## kit.yaml outline

- Header:
  - `name: cassandra-pods`, `type: db`, `collision-check: true`
  - `metrics: [{type: java-agent, service-name: cassandra}]`
  - `runtime: {type: pods, selector: "easydblab/kit=${KIT_NAME}"}`
  - endpoint `CQL`: `node-type: db`, port 30942, type `native`
- `args`: `--version` (`CASSANDRA_VERSION`, default `5.0`), `--image` (`CASSANDRA_IMAGE`, default empty), `--nodes` (`CASSANDRA_NODES`), `--memory` (`CASSANDRA_MEMORY`, default `2Gi`), `--heap` (`CASSANDRA_HEAP`, default half of `--memory`), `--dc` (`CASSANDRA_DC`, default `dc1`), `--storage-size` (`STORAGE_SIZE`, default `10Ti`). All are named options.
- `install`: `platform-pvs` on db nodes, `count: ${CASSANDRA_NODES}`, `volume-claim-template-name: data`.
- `start`:
  1. Shell step (sources `EDL_KIT_LIB`):
     - resolve the image;
     - run the registry manifest check;
     - check that the AMI Cassandra is not running: `ssh db<N> systemctl is-active cassandra` on every db node, through the workspace `ssh` wrapper (exit 0 fails start; 3 or 4 means inactive; anything else, such as an unreachable node, also fails start, naming the node and the ssh error);
     - fail fast on either.
  2. ConfigMap `cassandra-pods-jmx-rules` from the packaged rules.
  3. Manifests: headless Service, NodePort Service, StatefulSet.
  4. Shell step: wait, with a bounded timeout from `Constants`, until `nodetool status` on pod 0 lists `CASSANDRA_NODES` nodes as UN. On timeout, print the last pod events and exit 1.
- `stop`: delete by `-l easydblab/kit=${KIT_NAME}` for StatefulSet, Service and ConfigMap only.
- `uninstall`: `platform-pvs-delete`.

The kit creates no ad hoc pods. If a later script must create one, it uses a `<name>-$(date +%s)` name and the `easydblab/kit` and `easydblab/role` labels, per CLAUDE.md.

## Label mapping to `dashboards/cassandra/`

| Dashboard label | Source in the kit |
|---|---|
| `job="cassandra"` | `OTEL_SERVICE_NAME=cassandra` |
| `cluster` | the collector's resource attribute, unchanged (the pod sends to its node's collector) |
| `host_name` | `OTEL_RESOURCE_ATTRIBUTES=host.name=$(NODE_NAME)` from `spec.nodeName`; one pod per node keeps it unique |
| `cassandra_build` | the image tag (or the `--image` reference) |
| `node_role` | `db` |

## Risks / Trade-offs

- **Memory per pod.** The cassandra container requests and limits `--memory` (default `4Gi`), so the pod is Guaranteed for memory and the scheduler places it only where it fits. The image's `cassandra-env.sh` sizes the heap from the host's memory, not the cgroup limit, so the pod always sets the heap itself: `--heap` when given, else half the limit, leaving the rest for off-heap memory, metaspace and the agent. `4Gi` gives a 2 GiB heap. Measured: a Cassandra 5.0 container with a 1 GiB heap already used 1.8 GiB of a 2 GiB limit before the OTel agent loaded, and the pod was OOM-killed at 2Gi in K3s, so 2Gi has no headroom for heap pre-touch, metaspace, direct memory and the agent. 4Gi fits a K3s test node on a 7 GB CI runner and is small for a db node. `start` fails when `--heap` is not smaller than `--memory`.
- **Duplicate series if the kit and the AMI Cassandra both run.** They would mix under `job="cassandra"`. Mitigated: the start step fails when the AMI Cassandra is running.
- **Slow start.** With `OrderedReady`, start time grows linearly with node count.
- **Apple Silicon.** GHCR branch images are amd64-only until #1036. Docker Official images work.
- **Registry check needs network access** to the registry; the same is true of the image pull itself.

## Migration Plan

None. This is a new kit, and the collision fix only affects kits that were already unusable.

## Open Questions

None.
