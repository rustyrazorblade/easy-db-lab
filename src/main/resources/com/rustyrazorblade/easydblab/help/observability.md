---
name: observability
description: Access metrics, logs, traces, and profiles
---
# Observability

Full stack on control node: Grafana, Mimir (metrics), Loki (logs), Tempo (traces), Pyroscope (profiles). Run from workspace dir after `up`.

Every backend writes to S3 while the cluster runs, under the cluster's tenant: `mimir/<tenant>/`, `loki/`, `tempo/`, `pyroscope/`. Each uploads within minutes. `down` stops the OTel collector, then flushes Loki and Mimir and waits for Tempo's last blocks, all at once, before it removes anything; `down --force` lists what it will not save.

Access:
1. `easy-db-lab status` — prints Grafana URL and the Mimir/Loki/Tempo/Pyroscope endpoints. URLs work from your browser; cluster uses Tailscale VPN or SOCKS tunnel.
2. Grafana: NodePort 31300 on the control node (Service port 3000 in-cluster). Dashboards auto-installed after kit start. Core dashboards exist at startup (system, profiling).
3. Direct backend queries (send `X-Scope-OrgID: <tenant>`): on the control node's NodePorts: Mimir (31009, `/prometheus`), Loki (31100), Tempo (31200), Pyroscope (31040). In-cluster they are `<name>.<namespace>.svc`, in the cluster namespace (`default` on AWS unless `init --namespace` named another), on 9009, 3100, 3200 and 4040.

CLI access:
- Logs: `easy-db-lab logs query` — this cluster's logs from Loki; `-q '<LogQL>'` sends a raw query.
- Annotations: `easy-db-lab grafana annotate`, `easy-db-lab grafana backup`.

Dashboards:
- `easy-db-lab grafana update-config` — redeploy all core dashboards.
- Kit dashboards: auto-installed from `dashboards/*.json` in kit resource dir after `start`.

Related: `provisioning`, `kits`.
