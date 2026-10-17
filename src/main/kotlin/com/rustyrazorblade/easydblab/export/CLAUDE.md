# Export Package

`observability export <path>` writes a zip of observability data that opens offline as the local stack
(`localstack/`, `Mode.Offline`) with plain `docker compose up`. It reads the account bucket and writes only the
local zip: nothing in S3 is written, deleted or uploaded. The zip carries no images (owner decision 15); its
`README.txt` lists them. Profiles are left out (issue 1016).

## Flow (tenant export)

1. `ExportSelection.of(ExportSelectorChoice, workspaceTenant)` checks the options into a sealed `ExportSelector`
   (`Tenant`, `Clusters`, `Experiment`). `--tenant` alone is the tenant selector; beside `--experiment` it names
   the experiment's tenant (else the workspace's, else `default`, recorded as a `TenantChoice` with its source).
   No kind or two kinds throw `ExportSelectorRefused` naming all three. A `--cluster` value that is not a
   cluster's `<name>-<uuid>` (`Constants.Observability.CLUSTER_ID_PATTERN`) throws `ClusterIdRefused`, which the
   command reports as `Export.InvalidCluster`: the values go into PromQL, LogQL and TraceQL selectors unescaped,
   so nothing else may. The options are a non-exclusive picocli
   `@ArgGroup` (`ExportSelectorOptions`): an exclusive group would refuse `--experiment e --tenant t`.
2. `ExportTarget.of(path)`: a new `.zip` in an existing directory; the zip's one directory is the name without
   `.zip`.
3. `TenantExporter.export(target, tenant)`: `TenantStoreCopy.plan` lists everything (empty tenant →
   `Export.EmptyTenant`), the free-space check (`DiskSpace`; short → `Export.InsufficientSpace`, nothing written),
   `Export.SizeEstimated`, then the work directory (`ExportWorkDirectory`, `<path>.work-<id>/`), then
   `ExportZipWriter.write` streams `ExportStackFiles` and the copy into the zip, and `Export.Completed` (with
   `excluded = [profiles, 1016]`). Any `ExportStepFailed` becomes `Export.Failed(path, signal, step, reason, logs)`
   (`logs`: the split's `loki/split/compose.log` when it ran) and `CommandFailedException`. `Export.WorkDirectoryKept`
   is last, on success and failure; the work directory is never removed (owner decision D17).

## Pieces

- `ExportStore` — the bucket, list and read only (`S3ExportStore`, a client for the bucket's region). It has no
  write call. `ExportStoreFactory` opens it; `exportModule` binds `CheckedExportStoreFactory`, which runs
  `local create`'s read check first (`LocalStackStore.check`) and, when the credentials cannot read the bucket (an
  expired SSO session is the SSO OIDC `ExpiredTokenException`), emits `Export.StoreUnreadable(profile, awsErrorCode)`
  with the `aws sso login --profile` hint and throws `CommandFailedException`; when S3 cannot be reached at all (the
  check's `Unreachable`: a timeout, a refused connection, DNS, TLS) it emits `Export.StoreUnreachable(profile, reason)`,
  "could not reach S3", with no login hint, and throws. Every export opens its store before
  it writes anything, so this leaves no zip and no partial file.
- `ExportZipWriter` — streams to `<path>.partial-<random>` beside the path (`ZipOutputStream`, Zip64 automatic),
  moves it with a hard link (fails on an existing path; a plain no-replace move where links are unsupported), and
  removes the partial file on success and failure. A removal that fails never replaces the export's own failure: it
  is attached as suppressed (`PartialFileLeft`, naming the partial file left behind); after a complete move it fails the `stack` step
  with it as the cause. `Export.Failed` names the file on screen (`partialFile`, `partialFileError`, and `zipComplete`
  when the zip is in place).
  `ExportZipSink.put` refuses a path that leaves the stack.
- `ExportRecorder` — wraps the sink, keeps every entry's size and source time and each `meta.json`'s bytes;
  `directory(prefix)` is a `BlockDirectory` of what is in the zip.
- `TenantStoreCopy` — the safe order (design D15): Loki index first (`LokiIndexCopy`, below); Tempo by one listing (blocks with `meta.json` and no
  `meta.compacted.json`), then `TempoTenantIndexWriter`; Mimir from one read of `bucket-index.json.gz` (blocks minus
  deletion marks; marker files left out), then `MimirBucketIndexWriter` last; then the immutable objects (Loki
  chunks, annotation backups, reports). Everything goes to `data/<key>`, except Loki chunks: the offline Loki
  reads a filesystem store, which names a v13 chunk `<tenant>/<fingerprint>/<base64 of from:through:checksum>`
  (`LokiFilesystemLayout.chunkPath`; S3 keeps the raw name), so the export writes them under that name. The
  index keeps its keys. Mimir blocks with no bucket index fail.
- `LokiIndexCopy` — the Loki index of a tenant export (owner decision 22). Loki's ingesters upload shared multi-tenant
  TSDB files at each table's root, `<epoch>-<ingester id>-<nanos>.tsdb.gz` with the ingester id `<tenant>.<cluster>`,
  holding every tenant that ingester received; only Loki's compactor splits them into `<table>/<tenant>/`. Each table
  (its root files and `<table>/<tenant>/`) is read into `<work>/loki/store/loki/index/<table>/`, redone from a fresh
  listing when a listed file is gone (`Constants.Export.LOKI_TABLE_ATTEMPTS`). When any root file was copied,
  `LokiIndexSplit` runs; a root file left after it fails the `logs` step; then only `<table>/<tenant>/` files go into
  the zip. No root file copied, no container.
- `DockerLokiIndexSplit` — the split: one `docker compose` project (`<work>/loki/split/`, `edl-export-loki-<id>`) of
  `LocalImages.LOKI`, `-target=compactor`, the cluster's `loki.yaml` with the offline filesystem storage
  (`ExportedFiles.lokiConfig`) over the copy mounted read-write at `/store`, `network_mode: none`, no credentials, the
  operator's uid:gid (`ExportWorkDirectory.ownerOf`), `-compactor.retention-enabled=false`,
  `-compactor.deletion-mode=disabled`, memberlist on `127.0.0.1` (with no network Loki 3.7.8 finds no interface
  address and exits), compaction interval `Constants.Export.LokiSplit.COMPACTION_INTERVAL_SECONDS` (3.7.8 waits one
  interval before the first pass and has no run-once mode). Done at the log line `msg="compactor started"`, which 3.7.8
  writes after its first pass over every table; a container no longer running first, or the timeout, fails. Its logs
  are saved (`ProjectRemoval`) and the project removed whatever happened. Verified on 3.7.8: each tenant gets one
  `<table>/<tenant>/<nanos>-compactor-<from>-<through>-<checksum>.tsdb.gz` (merged with its existing per-tenant
  files), the root file is removed, a later pass leaves the split tables unchanged, and the offline read-path Loki
  answers the tenant's newest lines from the split files.
- `ExportWorkDirectory` — `<path>.work-<id>/` (`Constants.Export.WORK_INFIX`), its size and owner; kept, never
  removed. `QueryWorkDirectory` builds on it.
- `MimirBucketIndexWriter` / `TempoTenantIndexWriter` — built from a `BlockDirectory` (`FileBlockDirectory` on disk,
  for section 8; the recorder in the zip), never from a copy list, and checked with `requireExactBlocks`: an index
  that leaves out or adds a block, or a block with no `meta.json`, throws `IndexMismatchException`. Mimir: version 2,
  `BlockFromThanosMeta`'s fields, `uploaded_at` from the meta's write time. Tempo: `{created_at, meta, compacted: []}`.
- `ExportStackFiles` — the offline compose and configs, the dashboards under `dashboards/current` after the
  install-time pass for the export (tenant only; no cluster or experiment), `README.txt` (packaged template; one
  `docker pull` per image the compose names), and the local-stack manifest (mode `offline`), so `local dashboards`
  works on an opened export and `local create` refuses it. Both exports write it with `ExportStackFiles.put`, which
  also adds each `REQUIRED_DIRECTORIES` entry (`ExportZipSink.putDirectory`): `data/tempo/`, always, because the
  data directory is mounted read-only and the offline Tempo otherwise fails to create its store and restarts forever
  (`ExportWithoutTracesIntegrationTest`).
- `ExportSignal` / `ExportStepFailed` / `exportStep` — every step names its signal and what it was doing,
  listings, clean-up, the work directories and the zip file and its move (`stack`) included, so every
  failure becomes `Export.Failed`. A query export's `Export.Failed` also lists the stacks' saved `compose.log`s.

## Flow (query export: `--cluster`, `--experiment`), package `export.query`

`QueryExporter.export(target, selector, workspace)`, in order, stopping at the first failure:

1. `CheckedExportStoreFactory.openForQuery()` → `QueryStoreAccess(store, bucket, region, profile)`: the read check, and an
   AWS profile, which the reader's signer reads `~/.aws` with (`Export.AwsProfileRequired` for access keys).
   `QueryWorkDirectory.create(target)`: `<path>.work-<id>/` (`ExportWorkDirectory`, `Constants.Export.WORK_INFIX`) with `reader/`,
   `writer/{store,state/loki,state/tempo}`, `metrics/` and `projects/`, made by the operator. It is never removed;
   `Export.WorkDirectoryKept(path, bytes)` is the last event, on success and on failure (owner decision D17).
2. The reader (`QueryRuntime.reader`, `ExportReaderStack`: `StoreAccessPart` + `BackendsPart` of the live stack, no
   Grafana, backends published `127.0.0.1::<port>`) as `edl-export-<id>-reader`. Its Tempo reads through its memcached
   (`TempoLocalConfig.live`, see `localstack/CLAUDE.md`), so the trace fetches of one block read it from S3 once. `QueryPlanner` reads its Mimir
   (`MimirApi`, per tenant header, `|` for federation):
   - `ClusterTenantResolver` for `--cluster` (spike S7): the workspace's state for its own cluster, no query; one store
     tenant: that tenant, checked by a single-tenant `/api/v1/series?match[]=up{cluster="x"}&start=0&end=<now>`;
     several: the same query federated, tenant from `__tenant_id__`. Never `/api/v1/label/__tenant_id__/values`.
   - `ExperimentDiscovery`: `--experiment` clusters from `label/cluster/values` with `up{experiment="e"}` and the
     pre-label `up{experiment="",cluster="e"}` in the one tenant; every value must pass `ClusterIdRule` (the same
     `CLUSTER_ID_PATTERN` check `--cluster` gets), else `DiscoveredClusterIdRefused` → `Export.InvalidDiscoveredCluster`
     naming each bad value, before any query uses one; for `--cluster`, the covered experiments are the
     labelled `experiment` values plus each pre-label cluster's own id.
   - `ClusterTimeRanges`: each cluster's range from `count by (cluster) (count_over_time(up{...}[step]))` from the
     tenant's oldest block (`MimirStoreIndex`), widened by 10 minutes.
   Home tenant: the experiment's, or the first `--cluster`'s; the zip's tenants are the union. Refusals are typed:
   `ClusterNotFound`, `ClusterInSeveralTenants`, `ClusterTenantUnknown`, `ExperimentNotFound`, `InvalidDiscoveredCluster`; then
   `Export.ClustersSelected`, which for `--cluster` says where each tenant came from (`ClusterTenantResolver.resolveWithSources`:
   `workspace` or `query`; `QueryPlan.tenantSources`), as `TenantChosen` does for `--experiment`.
3. The writer (`ExportWriterStack`: a Loki with the offline stack's filesystem layout and `reject_old_samples: false`, a
   Tempo with the local backend and both WAL `ingestion_time_range_slack: 87600h`, never 0; both as the operator; no
   Mimir, whose shipper needs an xattr a macOS bind mount lacks). `LogExport` per cluster (forward pages of
   `query_range` with `X-Loki-Response-Encoding-Flags: categorize-labels`, a nanosecond tie-break, a one-nanosecond
   query when a page shares one, push with structured metadata), then the Loki flush (`LokiTailFlush.SHUTDOWN_PATH`)
   and `stop loki`. `TraceExport` per cluster (`{ resource.cluster = "x" }`, `limit` 20000 and `spss=1`, windows ≤ 168h split
   in half while full, searched in waves and then fetched and replayed on a fixed pool of `TRACE_EXPORT_PARALLELISM`,
   fetch by id, only the cluster's resource spans, base64 ids to hex, OTLP/JSON to the writer; the fetch phase emits
   `Export.TracesFetched` through a `ProgressThrottle` (`ProgressPolicy.TRACE_FETCH`: every 1000 traces or 30 seconds,
   and at the end)), then
   `WriterTempoDrain` (idle, then every completed live-store block `flushed`, listed in the work directory) and
   `stop tempo`.
4. `MetricsExport` per tenant: the live blocks overlapping the clusters' ranges staged at `metrics/mimir/<tenant>/`
   (no marker files), `ThanosBlockRewrite` (`tools bucket rewrite --prom-blocks --no-dry-run`, `FILESYSTEM` objstore at
   the tenant directory, `--tmp.dir` outside it, delete `{cluster!~"x|y"}`, one `--id` per block, `network_mode: none`,
   no environment, the operator's uid:gid, never `--delete-blocks` or `--hash-func`; a one-off `docker compose run`),
   originals and scratch removed, blocks with no series dropped, then `MimirBucketIndexWriter` last.
5. The zip (`QueryZipContents`): the stack (`ExportStackFiles.files(home, root, tenants, experiment)`), the writer's
   `store/loki/` as `data/loki/`, its Tempo blocks with the zip's own tenant index, the staged metrics, the selected
   clusters' annotation backups (`<ts>_<cluster>.json`) and every report of each covered experiment. Then
   `Export.SignalExported(signal, items, bytes)` for metrics (blocks), logs (lines) and traces, zero as zero, and
   `Export.QueryCompleted`.

Both stacks are `docker compose down`ed when the export ends (`DockerExportStack`, `ComposeCli`/`DockerComposeCli`).
Before every `down`, on close and after a failed start, `ProjectRemoval` saves `docker compose -p <project> logs
--no-color --timestamps` as `compose.log` in the project's directory (`Constants.Export.Query.STACK_LOG_FILE`); a
failed start names it, and so does `Export.Failed`. Every `down`'s exit code is checked: a non-zero one emits
`Export.StackNotRemoved` (with `docker compose -p <project> down --remove-orphans`) and never hides the export's own
outcome; after a failed start it is attached as a suppressed exception. A `/ready` timeout names the last status and
body or error. Each stack makes one direct `OkHttpClient` (`StackRuntime.client`) that every `http(tenant)` and
`/ready` look shares, and evicts its pool on close. thanos's `run` that outlasts its timeout (`ComposeResult.timedOut`)
is followed by `kill`, `down --remove-orphans` and `ps -a -q`; it fails either way, saying the container may still be
running unless all three confirm it is gone, and `MetricsExport` then deletes no source block and writes no index.
`StackRuntime.beforeStart` is where a test writes a project's `compose.override.yml`; production writes none. The
reader's override puts MinIO on the signer's `store-egress` network (see `localstack/CLAUDE.md`).

## Tests

Unit: `ExportSelectorTest`, `ExportZipWriterTest`, `MimirBucketIndexWriterTest`, `TempoTenantIndexWriterTest`,
`TenantStoreCopyTest` and `TenantExporterTest` (over `FakeExportStore`, an in-memory bucket whose `beforeOpen` hook
plays the compactor, and a fake `LokiIndexSplit` that plays Loki's), `DockerLokiIndexSplitTest` (over a fake
`ComposeCli`), `ExportStackFilesTest`, `ObservabilityExportTest`, `ExportModuleTest`. Integration:
`TenantExportIntegrationTest` (MinIO filled by the cluster's writers; listing unchanged; only the tenant's data;
the Loki index split by the real compactor from one shared file of both tenants, no file naming or holding the other;
the unzipped stack opened with `docker compose up` on an internal network and queried through its Grafana).
Query export unit tests in `export/query/` (over `FakeBackends`, one `ObservabilityHttp` per tenant header): the
resolver, discovery, ranges, log, trace and metrics copies, the stacks, thanos, the drain and `QueryExporterTest`.
Integration: `QueryExportIntegrationTest` (`QueryStoreSeed`: four clusters in two tenants, a days-old `up`, line and
span; the real stacks and thanos; listing unchanged; only the selected clusters; mixed tenants; a same-named experiment
in two tenants; the old span by id and by search from the opened stack's Tempo, which holds only the zipped blocks).
