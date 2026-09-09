---
name: observability
description: Access metrics, logs, traces, and profiles
---
# Observability

Full stack on control node: Grafana, VictoriaMetrics, VictoriaLogs, Tempo, Pyroscope. Run from workspace dir after `up`.

Access:
1. `easy-db-lab status` — prints Grafana URL, VictoriaMetrics/Logs/Tempo/Pyroscope endpoints. URLs work from your browser; cluster uses Tailscale VPN or SOCKS tunnel.
2. Grafana: port 3000. Dashboards auto-installed after kit start. Core dashboards exist at startup (system, profiling).
3. Direct backend queries: VictoriaMetrics (port 8428), VictoriaLogs (port 9428), Tempo (port 3200), Pyroscope (port 4040).

CLI access:
- Logs: `easy-db-lab logs query '<LogsQL>'` — queries VictoriaLogs.
- Backup/restore: `easy-db-lab metrics backup`, `easy-db-lab logs backup` — export to S3.

Dashboards:
- `easy-db-lab grafana update-config` — redeploy all core dashboards.
- Kit dashboards: auto-installed from `dashboards/*.json` in kit resource dir after `start`.

Related: `provisioning`, `kits`.
