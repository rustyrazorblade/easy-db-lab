# cqlite-flight kit (easy-db-lab)

Deploys the published [`cqlite-flight`](../../cqlite-flight/README.md) Arrow Flight
server as a **DaemonSet co-located with Cassandra** — one pod per `type=db` node,
each reading that node's own local SSTables read-only. This is the data plane the
`trino-connector` talks to.

## What it deploys

A single `DaemonSet/cqlite-flight` in the `default` namespace:

- **`nodeSelector: {type: db}`** — schedules exactly one pod per Cassandra node,
  matching the pattern used by the Cassandra sidecar and OTel collector DaemonSets
  already in this lab.
- **`hostNetwork: true`** — the Flight gRPC port is bound directly on the node's
  network namespace, so it is reachable from Trino (or any other in-cluster pod)
  at `<db-node-private-ip>:<flight-port>` — the same node-IP-addressing model the
  connector already needs from Sidecar-based topology discovery. It also means
  `localhost:4317` on the pod reaches the **node-local** OTel collector DaemonSet
  with no extra Service/DNS hop (see [Observability](#observability)).
- **Read-only hostPath mount** of the Cassandra data directory (default
  `/mnt/db1/cassandra/data`, matching `cassandra.service`'s
  `data_file_directories`), mounted at `/data` in the container and passed to
  `--data-dir`.
- **Image**: `ghcr.io/pmcfadin/cqlite-flight:<tag>` (default `latest`).

Default read path is the **live** Cassandra data directory — this kit does not
select a Sidecar snapshot. Snapshot-consistent reads (a stable file set while
compaction runs underneath) are driven by the **trino-connector's Flight ticket**
(`"snapshot": "<name>"`), not by this kit. See the
[flight ticket contract](../../cqlite-flight/README.md#flight-ticket-contract).

No `dashboards/` are shipped by this change — auto-discovery will pick up any
`.json` files placed there later without any kit.yaml change.

## Args

| Flag | Variable | Default | Description |
|------|----------|---------|--------------|
| `--tag` | `TAG` | `latest` | `cqlite-flight` image tag. |
| `--flight-port` | `FLIGHT_PORT` | `8815` | Arrow Flight gRPC port (host + container, via `hostNetwork`). |
| `--data-dir` | `CASSANDRA_DATA_DIR` | `/mnt/db1/cassandra/data` | Host path to the Cassandra data dir, mounted read-only. **Exactly one** — see [Multi-disk nodes](#multi-disk-nodes-single-data-dir-limitation-2114). |
| `--data-root` | `CASSANDRA_DATA_ROOT` | `/mnt` | Host path under which per-disk data dirs live; the `detect-multidisk` init container scans it read-only and warns on more than one candidate (#2114). |
| `--data-gid` | `CASSANDRA_DATA_GID` | `999` | Host GID that owns the Cassandra data dir; added as a pod `supplementalGroup`. |
| `--otel-endpoint` | `OTEL_ENDPOINT` | `http://localhost:4317` | OTLP gRPC endpoint (defaults to the node-local collector). |

## Multi-disk nodes: single-data-dir limitation (#2114)

**cqlite-flight serves exactly ONE data directory.** Its `--data-dir` flag is a
single path (`cqlite-flight/src/main.rs`: `data_dir: PathBuf`), so this kit mounts
one host `hostPath` (`--data-dir`, default `/mnt/db1/cassandra/data`) and passes
it as the only `--data-dir`.

On a **multi-disk node** where Cassandra spreads `data_file_directories` across
`/mnt/db1`, `/mnt/db2`, … the SSTables on every disk *other than the served one*
are invisible to the Flight server. Reads that touch that data return **partial
or empty results with no error** — a silent, latent misconfiguration. Every lab
run to date uses single-disk nodes, so this has never bitten; the kit's job is to
make the failure mode **visible** if it ever does.

**Detection — the `detect-multidisk` init container.** Each pod runs a non-fatal
init container (the same flight image, invoked as `/bin/sh`) that mounts
`--data-root` (`CASSANDRA_DATA_ROOT`, default `/mnt`) read-only and counts
**existing** `<root>/*/cassandra/data` dirs — candidacy is by existence,
regardless of contents, because an *empty* second data dir will still receive
SSTables later and must not report as single-disk today. If it finds more than
the one being served it prints a **loud, unmissable warning** naming each
unserved disk (annotated populated / currently empty), then exits 0 (it **never
blocks** the rollout). Review it per node:

```bash
kubectl logs -l app.kubernetes.io/name=cqlite-flight -c detect-multidisk --prefix
```

A single-disk node prints a one-line `OK` — exactly one existing candidate dir
**and it matches the served `--data-dir`** (compared in host-path form,
trailing slashes stripped) — and no warning, so the default behaviour is
unchanged. If the lone candidate is some *other* `*/cassandra/data` dir (wrong
`--data-root` or wrong `--data-dir`), the detector prints a loud
"lone candidate != served --data-dir" cannot-verify warning instead of a false
`OK` (still exit 0).

**Zero candidates is never a silent OK.** The detection volume uses `hostPath`
`type: DirectoryOrCreate` — deliberately, so a missing `--data-root` can never
block the rollout (the detector is non-fatal by contract). The benign side
effect is that a **mistyped `--data-root` is auto-created as an empty directory
on the node** and scanned as empty. The init script therefore treats zero
candidates as a *cannot-verify* condition and warns loudly, distinguishing two
cases:

- root has **no entries at all** → "data root ABSENT or EMPTY" — likely a wrong
  `--data-root`, or the hostPath was auto-created; the layout check is
  meaningless until it's fixed.
- root is **non-empty but no `<root>/*/cassandra/data` dir exists** → the
  node's layout doesn't match the convention the detector scans; point
  `--data-root` at the directory holding the per-disk mounts.

Both warn to the init container's log and still exit 0. In short: a wrong
`--data-root` shows up as a loud cannot-verify warning (plus a leftover empty
dir on the node), never as a rollout failure and never as a false single-disk
`OK`.

**Remedies** (the server side is out of scope for this kit — #2114 covers only
making the gap visible):

- Keep the queried tables on the served disk.
- Run one `cqlite-flight` DaemonSet per disk on distinct `--flight-port`s.
- There is deliberately **no way to silence the check entirely** — on a genuine
  single-disk node it is already a quiet one-line `OK`, and every other state
  is exactly the misconfiguration the detector exists to surface.

Genuinely spanning multiple dirs from one server would require a `--data-dir`
multi-value change to `cqlite-flight` itself — a server feature, out of this kit's
scope.

## Installing out-of-tree

**The issue's assumed syntax, `easy-db-lab kit install <kit> --from <dir>`, does
not exist.** Verified against `InstallTemplateResolver.kt` / `commands/kit/Install.kt`
in the easy-db-lab source: the real ad-hoc flag set is `--from <dir> --kit <name>
--size <size>`, and — more importantly — **`--from` never wires a kit's declared
`args:`**. `Install.execute()` calls `renderAndWrite(source, kitName, storageSize)`
with no `extraVars`, so only the fixed `TemplateVariables` set (`CLUSTER_NAME`,
`KIT_NAME`, `DB_NODE_IPS`, `KUBECONFIG`, etc.) gets substituted — **not**
`__TAG__`, `__FLIGHT_PORT__`, `__CASSANDRA_DATA_DIR__`, `__CASSANDRA_DATA_ROOT__`,
`__CASSANDRA_DATA_GID__`, or `__OTEL_ENDPOINT__`. `--from` also skips the
`type: db` node-pool guard and any
typed `install:` steps (irrelevant here — this kit has none).

**Recommended path — register the kits directory as a source, then install by name**
(this wires args correctly, exactly like a built-in kit):

```bash
easy-db-lab kit source add cqlite /path/to/cqlite/easy-db-lab-kits
easy-db-lab kit install cqlite-flight --tag v0.13.0 --flight-port 8815
easy-db-lab cqlite-flight start
```

**Fallback — ad-hoc `--from`** (only if you don't want to register a source).
This scaffolds the kit with placeholders **unresolved**; you must hand-edit the
written files before running `start`:

```bash
easy-db-lab kit install --from /path/to/cqlite/easy-db-lab-kits/cqlite-flight \
  --kit cqlite-flight --size 0Gi
# Edit the scaffolded files to replace remaining __TAG__ / __FLIGHT_PORT__ /
# __CASSANDRA_DATA_DIR__ / __CASSANDRA_DATA_ROOT__ / __CASSANDRA_DATA_GID__ /
# __OTEL_ENDPOINT__ tokens:
#   <workdir>/cqlite-flight/daemonset.yaml
#   <workdir>/cqlite-flight/bin/start.sh   (only __TAG__/__FLIGHT_PORT__/__CASSANDRA_DATA_DIR__/__CASSANDRA_DATA_ROOT__ appear here, for the echo lines)
easy-db-lab cqlite-flight start
```

## uid 10001 and the Cassandra data dir

The published image runs as a fixed non-root user (`uid 10001`, `useradd -r -u
10001 flight` — see `cqlite-flight/Dockerfile`). On the host, Cassandra's data
directory is owned by `cassandra:cassandra` at `uid/gid 999`
(`useradd -m -u 999 cassandra` in `packer/cassandra/install/install_cassandra.sh`,
"to match the cassandra-sidecar container image").

The manifest sets `runAsUser: 10001` / `runAsGroup: 10001` explicitly (issue
#2118) — it does **not** rely on the image's own `USER flight` directive to
satisfy `runAsNonRoot: true`. `USER flight` is a **name**, not a numeric uid;
with `runAsNonRoot: true` and no numeric `runAsUser`, the kubelet cannot
resolve "flight" to a uid to verify it's non-root and refuses to create the
container (`CreateContainerConfigError`: "container has runAsNonRoot and
image has non-numeric user (flight), cannot verify user is non-root"). Stating
the uid numerically in the pod spec is what actually satisfies the check; it
also adds `supplementalGroups: [<data-gid>]` (default `999`) so the uid-10001
process can read files owned by gid 999.

This assumes the host directory is at least group-readable
(`rwxr-x---` or looser) for gid 999. `fsGroup` was deliberately **not** used —
Kubernetes' `fsGroup` recursive-chown does not apply to `hostPath` volumes, so it
would silently do nothing here; `supplementalGroups` is the correct mechanism for
a pre-existing hostPath tree. **Open risk**: the exact host directory permissions
were not verified against a live cluster (no cluster was available while
authoring this kit) — if a real run shows permission-denied errors, either loosen
the host directory's group permissions or pass `--data-gid` to match whatever GID
actually owns it.

## Observability

`CQLITE_OTEL_ENABLED=true`, `CQLITE_OTEL_ENDPOINT` (default
`http://localhost:4317`), `CQLITE_OTEL_PROTOCOL=grpc`, and
`CQLITE_OTEL_SERVICE_NAME=cqlite-flight` are set as container env vars — see
`cqlite_core::observability::config` for the full `CQLITE_OTEL_*` contract.
Metrics/traces are **OTLP-push**; no Prometheus scrape config or `metrics:` block
is declared in `kit.yaml` (there is nothing for the OTel DaemonSet to scrape).

The default endpoint relies on this pod running `hostNetwork: true` **on the same
node** as the lab's OTel collector DaemonSet (`otel-collector-config.yaml`, OTLP
gRPC receiver on `0.0.0.0:4317`), so `localhost:4317` reaches it directly — the
same idiom the Cassandra sidecar DaemonSet already uses
(`OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317`). Override `--otel-endpoint`
if that topology ever changes (e.g. a control-node-only collector reachable via
the in-cluster `otel-collector.default.svc.cluster.local:4317` Service).

## Lifecycle

```bash
easy-db-lab cqlite-flight start   # kubectl apply the DaemonSet, wait for rollout
easy-db-lab cqlite-flight stop    # kubectl delete daemonset -l easydblab.com/kit=cqlite-flight
easy-db-lab cqlite-flight status  # built-in kit status command
```

`bin/start.sh` / `bin/stop.sh` are plain scripts (no typed `start:`/`stop:` steps
in `kit.yaml`), following the `sysbench`/`trino` bin-script pattern — this also
keeps them working identically regardless of which install path was used.
`stop.sh` deletes strictly by label selector (`easydblab.com/kit=cqlite-flight`),
never by resource name, matching the issue's requirement.

## Open risks for a real lab run

- **Host directory permissions** for the Cassandra data dir were not verified
  live (see [uid 10001](#uid-10001-and-the-cassandra-data-dir) above).
- **Trino reachability** assumes the trino-connector resolves each replica's
  `cqlite-flight` endpoint as `<db-node-private-ip>:<flight-port>` (consistent
  with the connector's stated Sidecar-based topology discovery). If the
  connector instead expects a stable DNS name, swap `hostNetwork` for a headless
  `Service` — not done here since it doesn't match the "replica's endpoint"
  model described in `cqlite-flight/README.md`.
- **No `kubectl apply --dry-run=client` against a live lab cluster** was
  possible while authoring this kit (no cluster available); it was validated
  against a throwaway local k3s control plane instead (see PR/validation notes).
  Re-run the dry-run against the real lab cluster before first use.
- **GHCR image pull**: the kit assumes the `ghcr.io/pmcfadin/cqlite-flight`
  package is public (no `imagePullSecrets` wired) per the image's own README.

## Fast iteration with a dev image (outside the release train)

When the harness surfaces a flight/core bug, iterate WITHOUT minting release
versions — the `flight-image.yml` workflow's free-form `image_tag` dispatch is
the dev channel, and it builds from any ref:

```bash
# 1. Build + push ghcr.io/pmcfadin/cqlite-flight:dev from your fix branch
gh workflow run flight-image.yml --repo pmcfadin/cqlite \
  --ref <fix-branch> -f image_tag=dev

# 2. Roll the DaemonSet onto the rebuilt image (imagePullPolicy is Always,
#    so a restart re-pulls the moving tag)
kubectl rollout restart daemonset/cqlite-flight
kubectl rollout status daemonset/cqlite-flight --timeout=180s
```

`dev` never touches `latest` or any `vX.Y*` tag; release images stay on the
tag-push / `version`-dispatch path. If two people iterate at once, use
distinct tags (`-f image_tag=dev-<yourname>`).
