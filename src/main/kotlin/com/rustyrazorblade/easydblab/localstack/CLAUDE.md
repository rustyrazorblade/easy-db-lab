# Local Stack Package

A local observability stack is a docker compose project on the operator's machine that reads the shared
store (`observability local create <dir>`), or, later, an exported copy of it. Everything it writes comes
from classpath resources; nothing reads a source checkout. Nothing here needs a cluster workspace or
`TemplateService`'s cluster state.

## Flow

1. Build a `LocalStackSpec(mode, tenants, grafanaPort, documentsPort)`. `Mode.Live(bucket, region,
   awsProfile, home)` reads S3; `Mode.Offline(dataDir, home)` reads an export's copy on disk (`export/`
   writes it). `tenants` is every tenant directory under `mimir/` (`TenantDirectory`); `tenantSet` adds the
   home tenant. The manifest names the modes `Mode.Live.MANIFEST_NAME` and `Mode.Offline.MANIFEST_NAME`.
2. `LocalStackComposer.compose(spec)` returns `Map<relative path, bytes>`: `compose.yml` plus `config/...`.
   Live joins three `ComposePart`s: `StoreAccessPart` (`store-signer`, `store-gate`, `store-check`),
   `BackendsPart` (`memcached`, `mimir`, `loki`, `tempo`) and `GrafanaViewPart` (`grafana`, `documents-web`).
   Offline joins only the last two. `LocalStackComposer.join(parts)` makes `compose.yml` and the files of any parts; the query
   export's reader and writer (`export/query/ExportStacks.kt`) use it. `ComposeService.networkMode` is set only for thanos (`none`). `BackendsPart` builds the same services over a `BackendStorage`:
   `GatedStore` (S3 through the gate; Mimir and Loki files byte for byte, flags for the gate and placeholder
   keys) or `ExportedFiles` (the data directory read-only at `/store` in every backend, no dependencies;
   Mimir `-blocks-storage.backend=filesystem -blocks-storage.filesystem.dir=/store`; Loki's file patched:
   `common.storage.s3` removed, filesystem directories `/store`, `schema_config.configs.0.object_store:
   filesystem`; Tempo `TempoLocalConfig.offline`: `backend: local` at `/store/tempo`, `s3` removed).
   `GrafanaViewPart.compose(spec, documents)`: `Documents.FromStore(bucket)` (the cluster's documents
   nginx forwarding to the gate) or `Documents.FromDisk(dataDir)` (`documents-nginx-offline.conf`, `root /data`
   over the data directory mounted read-only, GET only under `/reports/`).
3. `LocalStackWriter(toolVersion).write(dir, spec, files)` writes them atomically and then the manifest
   `.easy-db-lab-local-stack.json` (`LocalStackManifest`: tool version, mode, home tenant, tenants, Grafana
   and documents ports, sha256 per owned file). It returns `LocalStackWriteResult(written, edited, manifest,
   rewrite)`. It creates a missing directory, uses an empty one, and throws `LocalStackDirectoryRefused` for a
   file or a non-empty directory with no manifest before writing anything (`refuseForeign(dir)` makes the same
   check on its own and returns the existing manifest). It refuses (`IllegalArgumentException`) any owned path
   that is `compose.override.yml`, the manifest, or under `data/`, `cache/`, `grafana-data/` or `dashboards/`.
4. The dashboard tree goes in separately: `LocalStackDashboards.install(dir, manifest, source, reload)` renders a
   `DashboardTreeSource` with the live context (`cluster` and `experiment` null, the manifest's tenants, the
   documents URL on its port) through `GrafanaDashboardTreeWriter.render`, and `DashboardTreeInstaller` stages
   it in `dashboards/.staging-*`, renames the old `current` aside, renames the staged tree into place and
   removes the old one. When that rename fails it renames the old tree back; it first removes any
   `.previous-*` a stopped install left. Sources: `PackagedDashboardTree` (the core catalog plus every packaged kit,
   `PackagedKitDashboards`, one folder per instance as a cluster installs it: `<kit>` and `<kit>-<extension>`)
   and `DirectoryDashboardTree` (`--from`, `<folder>/<file>.json` only).

## Commands and services

- `observability local create <dir> [--tenant]` → `LocalStackCreator`: tenant rule, `refuseForeign`, profile and
  bucket from `User`, `LocalStackStore.check` (SDK: bucket region from `BucketRegion` with the profile-region
  client, then `HeadBucket` and a one-key list of `mimir/` with a client for the bucket's region from
  `S3ClientForRegion`, since S3 answers a `HeadBucket` in another region with a 301; an
  `S3Exception` is its error code, any other SDK exception its code or class and message, which covers an expired
  SSO session, the SSO OIDC service's `ExpiredTokenException`; `Event.LocalStack.StoreUnreadable(profile, awsErrorCode)` and `CommandFailedException` on failure, nothing
  written), `LocalStackStore.tenants(bucket, region, home)` (`TenantDirectory` over a client for the bucket's
  region), compose and write with `Mode.Live.region` the bucket's region (the signer signs for it), packaged
  dashboards, then
  `LocalStack.Written` (files, edited files, tenants). A re-run keeps the manifest's ports. On a re-run only
  (`rewrite`), a Grafana answering on the manifest's port reloads its datasources, then its dashboards
  (`LocalStack.DatasourcesReloaded`). A refused reload (`GrafanaReloadRefused` from `LocalGrafana`) is reported by
  `reportingReloadRefusal` as `LocalStack.ReloadRefused` (the files are rewritten; `docker compose restart grafana`
  loads them) and fails the command; `local dashboards` reports a refused reload the same way.
- `observability local dashboards <dir> [--from <dir>]` → `LocalDashboardsRefresher`: reads the manifest, checks
  `LocalGrafana.isRunning` on its port (`LocalStack.GrafanaNotRunning` and nothing changed when it does not
  answer), installs and reloads (`LocalStack.DashboardsInstalled`, "Using packaged dashboards" when no `--from`).
  It never calls docker.
- `LocalGrafanaHttp`: `GET /api/health`, and `POST /api/admin/provisioning/{datasources,dashboards}/reload` as the
  server admin (`Constants.Grafana.ADMIN_USER`, basic auth), direct with `Proxy.NO_PROXY`.
- Wiring: `localStackModule`.

## Rules the stack encodes

- Every image is a `LocalImages` pin: the cluster's own for Mimir, memcached, Loki, Tempo, Grafana, the
  documents nginx and the sigv4 proxy; `Constants.LocalStack` pins for the gate (also the store check, for
  its `curl`), the signer healthcheck's static busybox (an `image` mount: the signer image has no shell)
  and thanos. `LocalImagesTest` and `LocalStackComposerTest` are the drift tests.
- Every component reaches S3 only through `store-gate:8080`, an nginx that passes GET and HEAD to the
  signer and answers 403 to anything else; each refused request is logged a second time on a line
  starting `REFUSED`.
- The signer signs any method with the operator's credentials and Grafana runs anonymous Admin, so no
  service but the gate and the store check may reach it (`Constants.LocalStack.Networks`, typed
  `ComposeService.networks` / `ComposeFile.networks` / `ComposePart.networks`): `store-signer` is on the
  internal `store-access` network with `store-gate` and `store-check`, plus `store-egress`, which no other
  service joins; the gate is also on `default`, where everything else is. The export's reader uses the
  same part. `LocalStackComposerTest` and `ExportStacksTest` assert it with the test helper
  `ComposeNetworks`; `LocalStackReadOnlyIntegrationTest` probes from inside Grafana, Mimir and the
  documents view (a busybox in the container's network namespace) that the signer is unreachable by name
  and address. A test override that points the signer at MinIO puts MinIO on `store-egress`, never on
  `default`. Both local Grafanas also allow their data source proxy only the stack's backends
  (`GrafanaServerSettings.dataSourceProxyAllowList`, `GF_SECURITY_DATA_SOURCE_PROXY_WHITELIST`, from
  `BackendUrls.LOCAL_COMPOSE.hostPorts()`).
- Mimir and Loki run the cluster's `mimir.yaml` and `loki.yaml` byte for byte with flag overrides
  (read-path targets, usage reporting off, the gate as endpoint, placeholder keys; Mimir's four
  `memcached:11211` cache addresses). Tempo runs as the single binary with `TempoLocalConfig.live`, the
  cluster's `tempo.yaml` patched as typed data through `YamlDocument`/`YamlPatch` (never string-built).
- The signer mounts `~/.aws` read-only at `/root/.aws` with `HOME=/root` (`Constants.LocalStack.SIGNER_HOME`)
  and `AWS_PROFILE`; its healthcheck lists the bucket every 60s. `store-check` (packaged `store-check.sh`,
  POSIX sh, tested by `./gradlew testLocalStoreCheck`) must pass before any backend starts; it names the
  signer's 502 `unable to proxy request` by its text, apart from an S3 4xx: a network error (`dial tcp`, `i/o
  timeout`, `no such host`, TLS) is "the signer could not reach S3"; a credentials text (SSO, `NoCredentialProviders`,
  `ExpiredToken`, credentials) is a credentials failure with the `aws sso login` hint; anything else is the signer's text.
- A local Grafana, live or offline, downloads no plugin (`GrafanaViewPart` sets `GrafanaServerSettings.plugins` empty,
  so no `GF_INSTALL_PLUGINS`): offline the download fails and Grafana 13 exits 1, and live the download from
  grafana.com stalled Grafana's start for minutes. A cluster's Grafana keeps them (the ClickHouse kit uses its
  datasource). `LocalGrafanaPluginsTest` fails, per mode, if a packaged core or kit dashboard starts to use one of
  `GrafanaServerSettings.DOWNLOADED_PLUGINS`; that stack would then have to carry the plugin. Grafana's own async preinstall of its default apps still runs and logs failures offline,
  which do not stop it.
- `local create` refuses a directory whose manifest is offline (an opened export).
- Grafana takes its `GF_*` settings from `GrafanaServerSettings` and its datasources from
  `GrafanaDatasourceSet.build(spec.tenantSet, BackendUrls.LOCAL_COMPOSE)`. Its dashboard provider sweeps
  `/var/lib/easy-db-lab/dashboards/current`, which is the stack directory's `dashboards/current`. The
  composer writes no dashboards: the command that writes the tree stages it beside `current` under
  `dashboards/` and renames it into place.
- Only Grafana and the documents web server are published, on `127.0.0.1`. Component state lives under
  `cache/<component>`, Grafana's under `grafana-data/`; the backends and Grafana run as root so they can
  write the directories docker creates.
- `compose.yml` strings are double-quoted: compose refuses a bare number where it wants a string.
- Every configuration file sits in its component's directory, `config/<component>/`, and compose mounts the
  directory, never the file (`LocalStackComposer.configMount`): the writer replaces a file by renaming a new one over
  it, and a container that bind-mounts the file itself keeps the old one, so Grafana's provisioning reload failed
  with "no such file". Both nginx services read `/etc/easy-db-lab/nginx/nginx.conf` through `-c`
  (`LocalStackComposer.nginxCommand`).
- The live Loki runs with `AWS_RESPONSE_CHECKSUM_VALIDATION` and `AWS_REQUEST_CHECKSUM_CALCULATION` set to
  `when_required` (`GatedStore.lokiEnvironment`): its aws-sdk-go-v2 otherwise adds an `x-amz-checksum-mode` header
  the signer does not sign, and S3 refuses every read.
- Both local Grafanas wait `Constants.LocalStack.GRAFANA_DATAPROXY_TIMEOUT_SECONDS` (300s) for a datasource
  (`GF_DATAPROXY_TIMEOUT`): a cold query over older data outlasted the 30s default. A cluster's Grafana keeps it.
