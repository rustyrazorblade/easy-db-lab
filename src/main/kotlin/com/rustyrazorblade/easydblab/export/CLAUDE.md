# Export Package

`observability export <path>` writes a zip of observability data that opens offline as the local stack
(`localstack/`, `Mode.Offline`) with plain `docker compose up`. It reads the account bucket and writes only the
local zip: nothing in S3 is written, deleted or uploaded. The zip carries no images (owner decision 15); its
`README.txt` lists them. Profiles are left out (issue 1016).

## Flow (tenant export)

1. `ExportSelection.of(ExportSelectorChoice, workspaceTenant)` checks the options into a sealed `ExportSelector`
   (`Tenant`, `Clusters`, `Experiment`). `--tenant` alone is the tenant selector; beside `--experiment` it names
   the experiment's tenant (else the workspace's, else `default`, recorded as a `TenantChoice` with its source).
   No kind or two kinds throw `ExportSelectorRefused` naming all three. The options are a non-exclusive picocli
   `@ArgGroup` (`ExportSelectorOptions`): an exclusive group would refuse `--experiment e --tenant t`.
2. `ExportTarget.of(path)`: a new `.zip` in an existing directory; the zip's one directory is the name without
   `.zip`.
3. `TenantExporter.export(target, tenant)`: `TenantStoreCopy.plan` lists everything (empty tenant →
   `Export.EmptyTenant`), the free-space check (`DiskSpace`; short → `Export.InsufficientSpace`, nothing written),
   `Export.SizeEstimated`, then `ExportZipWriter.write` streams `ExportStackFiles` and the copy into the zip, and
   `Export.Completed` (with `excluded = [profiles, 1016]`). Any `ExportStepFailed` becomes `Export.Failed(path,
   signal, step, reason)` and `CommandFailedException`.

## Pieces

- `ExportStore` — the bucket, list and read only (`S3ExportStore`, a client for the bucket's region from
  `exportModule`). It has no write call. `ExportStoreFactory` opens it.
- `ExportZipWriter` — streams to `<path>.partial-<random>` beside the path (`ZipOutputStream`, Zip64 automatic),
  moves it with a hard link (fails on an existing path; a plain no-replace move where links are unsupported), and
  removes the partial file on success and failure. `ExportZipSink.put` refuses a path that leaves the stack.
- `ExportRecorder` — wraps the sink, keeps every entry's size and source time and each `meta.json`'s bytes;
  `directory(prefix)` is a `BlockDirectory` of what is in the zip.
- `TenantStoreCopy` — the safe order (design D15): Loki index table by table, each table read into a scratch
  directory first and redone from a fresh listing when a listed file is gone (`Constants.Export.LOKI_TABLE_ATTEMPTS`;
  a table's files are its root files and `<table>/<tenant>/`); Tempo by one listing (blocks with `meta.json` and no
  `meta.compacted.json`), then `TempoTenantIndexWriter`; Mimir from one read of `bucket-index.json.gz` (blocks minus
  deletion marks; marker files left out), then `MimirBucketIndexWriter` last; then the immutable objects (Loki
  chunks, annotation backups, reports). Everything goes to `data/<key>`, except Loki chunks: the offline Loki
  reads a filesystem store, which names a v13 chunk `<tenant>/<fingerprint>/<base64 of from:through:checksum>`
  (`LokiFilesystemLayout.chunkPath`; S3 keeps the raw name), so the export writes them under that name. The
  index keeps its keys. Mimir blocks with no bucket index fail.
- `MimirBucketIndexWriter` / `TempoTenantIndexWriter` — built from a `BlockDirectory` (`FileBlockDirectory` on disk,
  for section 8; the recorder in the zip), never from a copy list, and checked with `requireExactBlocks`: an index
  that leaves out or adds a block, or a block with no `meta.json`, throws `IndexMismatchException`. Mimir: version 2,
  `BlockFromThanosMeta`'s fields, `uploaded_at` from the meta's write time. Tempo: `{created_at, meta, compacted: []}`.
- `ExportStackFiles` — the offline compose and configs, the dashboards under `dashboards/current` after the
  install-time pass for the export (tenant only; no cluster or experiment), `README.txt` (packaged template; one
  `docker pull` per image the compose names), and the local-stack manifest (mode `offline`), so `local dashboards`
  works on an opened export and `local create` refuses it.
- `ExportSignal` / `ExportStepFailed` / `exportStep` — every step names its signal and what it was doing.

## Not built yet

`--cluster` and `--experiment` (query exports, tasks section 8) are parsed and checked, and the experiment's tenant
is reported (`Export.TenantChosen`), then the command fails saying they are not available.

## Tests

Unit: `ExportSelectorTest`, `ExportZipWriterTest`, `MimirBucketIndexWriterTest`, `TempoTenantIndexWriterTest`,
`TenantStoreCopyTest` and `TenantExporterTest` (over `FakeExportStore`, an in-memory bucket whose `beforeOpen` hook
plays the compactor), `ExportStackFilesTest`, `ObservabilityExportTest`, `ExportModuleTest`. Integration:
`TenantExportIntegrationTest` (MinIO filled by the cluster's writers; listing unchanged; only the tenant's data;
the unzipped stack opened with `docker compose up` on an internal network and queried through its Grafana).
