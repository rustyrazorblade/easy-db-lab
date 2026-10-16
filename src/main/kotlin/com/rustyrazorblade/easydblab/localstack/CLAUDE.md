# Local Stack Package

A local observability stack is a docker compose project on the operator's machine that reads the shared
store (`observability local create <dir>`), or, later, an exported copy of it. Everything it writes comes
from classpath resources; nothing reads a source checkout. Nothing here needs a cluster workspace or
`TemplateService`'s cluster state.

## Flow

1. Build a `LocalStackSpec(mode, tenants, grafanaPort, documentsPort)`. `Mode.Live(bucket, region,
   awsProfile, home)` reads S3; `Mode.Offline(dataDir, home)` is for the export (task 7.5, not composed yet:
   `LocalStackComposer` throws `UnsupportedOperationException`). `tenants` is every tenant directory under
   `mimir/` (`TenantDirectory`); `tenantSet` adds the home tenant.
2. `LocalStackComposer.compose(spec)` returns `Map<relative path, bytes>`: `compose.yml` plus `config/...`.
   It joins three `ComposePart`s: `StoreAccessPart` (`store-signer`, `store-gate`, `store-check`),
   `BackendsPart` (`memcached`, `mimir`, `loki`, `tempo`) and `GrafanaViewPart` (`grafana`, `documents-web`).
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
   removes the old one. Sources: `PackagedDashboardTree` (the core catalog plus every packaged kit,
   `PackagedKitDashboards`, one folder per instance as a cluster installs it: `<kit>` and `<kit>-<extension>`)
   and `DirectoryDashboardTree` (`--from`, `<folder>/<file>.json` only).

## Commands and services

- `observability local create <dir> [--tenant]` → `LocalStackCreator`: tenant rule, `refuseForeign`, profile and
  bucket from `User`, `LocalStackStore.check` (SDK: bucket region, `HeadBucket`, one-key list of `mimir/`;
  `Event.LocalStack.StoreUnreadable(profile, awsErrorCode)` and `CommandFailedException` on failure, nothing
  written), `LocalStackStore.tenants` (`TenantDirectory`), compose and write, packaged dashboards, then
  `LocalStack.Written` (files, edited files, tenants). On a re-run only (`rewrite`), a Grafana answering on the
  manifest's port reloads its datasources and dashboards (`LocalStack.DatasourcesReloaded`).
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
- Mimir and Loki run the cluster's `mimir.yaml` and `loki.yaml` byte for byte with flag overrides
  (read-path targets, usage reporting off, the gate as endpoint, placeholder keys; Mimir's four
  `memcached:11211` cache addresses). Tempo runs as the single binary with `TempoLocalConfig.live`, the
  cluster's `tempo.yaml` patched as typed data through `YamlDocument`/`YamlPatch` (never string-built).
- The signer mounts `~/.aws` read-only at `/root/.aws` with `HOME=/root` (`Constants.LocalStack.SIGNER_HOME`)
  and `AWS_PROFILE`; its healthcheck lists the bucket every 60s. `store-check` (packaged `store-check.sh`,
  POSIX sh, tested by `./gradlew testLocalStoreCheck`) must pass before any backend starts; it treats the
  signer's 502 `unable to proxy request` as a credentials failure, apart from an S3 4xx.
- Grafana takes its `GF_*` settings from `GrafanaServerSettings` and its datasources from
  `GrafanaDatasourceSet.build(spec.tenantSet, BackendUrls.LOCAL_COMPOSE)`. Its dashboard provider sweeps
  `/var/lib/easy-db-lab/dashboards/current`, which is the stack directory's `dashboards/current`. The
  composer writes no dashboards: the command that writes the tree stages it beside `current` under
  `dashboards/` and renames it into place.
- Only Grafana and the documents web server are published, on `127.0.0.1`. Component state lives under
  `cache/<component>`, Grafana's under `grafana-data/`; the backends and Grafana run as root so they can
  write the directories docker creates.
- `compose.yml` strings are double-quoted: compose refuses a bare number where it wants a string.
