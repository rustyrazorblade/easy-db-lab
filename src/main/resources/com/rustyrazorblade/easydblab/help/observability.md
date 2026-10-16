---
name: observability
description: Access metrics, logs, traces, and profiles
---
# Observability

Full stack on control node: Grafana, Mimir (metrics), Loki (logs), Tempo (traces), Pyroscope (profiles). Run from workspace dir after `up`.

Every backend writes to S3 while the cluster runs, under the cluster's tenant: `mimir/<tenant>/`, `loki/`, `tempo/`, `pyroscope/`. Each uploads within minutes. `down` stops the OTel collector, then flushes Loki and Mimir and waits for Tempo's last blocks, all at once, before it removes anything; `down --force` lists what it will not save.

Access:
1. `easy-db-lab status` — prints Grafana URL and the Mimir/Loki/Tempo/Pyroscope endpoints. URLs work from your browser; cluster uses Tailscale VPN or SOCKS tunnel.
2. Grafana: port 3000. Dashboards auto-installed after kit start. Core dashboards exist at startup (system, profiling).
3. Direct backend queries (send `X-Scope-OrgID: <tenant>`): Mimir (port 9009, `/prometheus`), Loki (port 3100), Tempo (port 3200), Pyroscope (port 4040).

CLI access:
- Logs: `easy-db-lab logs query` — this cluster's logs from Loki; `-q '<LogQL>'` sends a raw query.
- Annotations: `easy-db-lab grafana annotate`, `easy-db-lab grafana backup`.

Reports:
- An experiment is one test: one or more clusters of one tenant, named at `init --experiment <name>` (default: the cluster's own `<name>-<id>`). A report belongs to the experiment, so every cluster of it shows the same report.
- `easy-db-lab report upload <dir>` — zips the directory (any file types) and stores the zip as `reports/<tenant>/<experiment>/report-<yyyyMMdd-HHmmss>.zip`, timestamped with the upload time in UTC. No upload ever overwrites an earlier one; every zip is kept. Works after `down`, with your own AWS credentials.
- Each upload is unpacked beside its zip, with an HTML copy of each `.md` and a `_files.html` list. The documents panels show the newest upload: its `index.md` (or `index.html`) at the root, else `_files.html`.
- The command checks the whole directory first and names every file that fails: path parts hold only letters, digits, `.`, `_`, `-` (no `..`), no symlinks, no root `_files.html` or `_files.md`, no `X.md` beside `X.html`. It takes one directory, never a list of files.

Local stack (no cluster needed; metrics, logs and traces of every tenant, torn-down clusters included; no profiles yet):
- `easy-db-lab observability local create <dir> [--tenant <home>]` — writes a docker compose stack (Grafana, Mimir with memcached, Loki, Tempo, the documents view) into `<dir>`, which is required. It reads the account bucket with your AWS profile and never writes to it: a read gate passes only GET and HEAD. Fails, writing nothing, if the profile cannot read the bucket (it prints `aws sso login --profile <p>`) or `<dir>` holds files that are not a stack. `--tenant` (default `default`) is the tenant of the stable `mimir`/`loki`/`tempo` datasources; every tenant gets `<signal>-<tenant>` and `<signal>--all` datasources, as on a cluster.
- Then `cd <dir> && docker compose up -d` and open http://127.0.0.1:3000. Put your own changes in `<dir>/compose.override.yml`; the tool never touches it, `cache/` or `grafana-data/`.
- Re-run `local create` on the same `<dir>` for new tenants: it rewrites only its own files, names any you edited, and a running Grafana reloads with no restart.
- `easy-db-lab observability local dashboards <dir> [--from <dashboards-dir>]` — replaces the running stack's dashboards and reloads them, no restart, no docker call. `--from` takes any `<folder>/<file>.json` tree; without it, the packaged dashboards ("Using packaged dashboards"). Fails and changes nothing when the stack's Grafana is not running.
- Credentials: `store-check` must pass before any backend starts; `docker compose ps` shows `store-signer` unhealthy once they expire. Fix with `aws sso login --profile <p>`.

Export (a zip that opens offline as the local stack; no profiles, #1016):
- `easy-db-lab observability export <path>.zip --tenant <t>` — copies tenant `<t>`'s metrics, logs, traces, annotation backups and reports, with the offline stack, the dashboards and a README, into a new zip. Reads the bucket with your AWS profile; writes nothing to S3. Works outside a workspace.
- Exactly one of `--tenant`, `--cluster <name-id>` (repeatable) or `--experiment <name>` (with `--tenant`, else the workspace's tenant, else `default`, and it says which); `--cluster` and `--experiment` are not available yet.
- Fails, leaving nothing at the path: an existing path (never overwritten), too little free disk (checked before copying), a tenant with no data (names the prefixes), or any copy error (names the signal and the step).
- Open it: pull the images the zip's `README.txt` lists (the zip has none), then `unzip <path>.zip && cd <dir> && docker compose up -d`; no network or AWS credentials needed. `data/` is mounted read-only and never changes.

Dashboards:
- `easy-db-lab grafana update-config` — redeploy all core dashboards.
- Kit dashboards: auto-installed from `dashboards/*.json` in kit resource dir after `start`.

Related: `provisioning`, `kits`.
