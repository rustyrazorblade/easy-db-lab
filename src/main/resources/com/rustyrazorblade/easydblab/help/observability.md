---
name: observability
description: Access metrics, logs, traces, and profiles
---
# Observability

Full stack on control node: Grafana, Mimir (metrics), Loki (logs), Tempo (traces), Pyroscope (profiles). Run from workspace dir after `up`.

Every backend writes to S3 while the cluster runs, under the cluster's tenant: `observabilitymetrics/<tenant>/`, `observability/logs/`, `observability/traces/`, `observability/profiles/`. `down` flushes Loki and Mimir and checks the rest is in S3 before it removes anything.

Access:
1. `easy-db-lab status` — prints Grafana URL and the Mimir/Loki/Tempo/Pyroscope endpoints. URLs work from your browser; cluster uses Tailscale VPN or SOCKS tunnel.
2. Grafana: port 3000. Dashboards auto-installed after kit start. Core dashboards exist at startup (system, profiling).
3. Direct backend queries (send `X-Scope-OrgID: <tenant>`): Mimir (port 9009, `/prometheus`), Loki (port 3100), Tempo (port 3200), Pyroscope (port 4040).

CLI access:
- Logs: `easy-db-lab logs query` — this cluster's logs from Loki; `-q '<LogQL>'` sends a raw query.
- Annotations: `easy-db-lab grafana annotate`, `easy-db-lab grafana backup`.

Dashboards:
- `easy-db-lab grafana update-config` — redeploy all core dashboards.
- Kit dashboards: auto-installed from `dashboards/*.json` in kit resource dir after `start`.

Related: `provisioning`, `kits`.
