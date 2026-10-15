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

Dashboards:
- `easy-db-lab grafana update-config` — redeploy all core dashboards.
- Kit dashboards: auto-installed from `dashboards/*.json` in kit resource dir after `start`.

Related: `provisioning`, `kits`.
