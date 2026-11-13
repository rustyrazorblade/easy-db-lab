| Source | Requirement | Covering scenario(s) | Status |
|--------|-------------|----------------------|--------|
| AC1 | WHEN `up` runs on AWS THEN the backends and Grafana land on the control node as today and every dashboard shows data | `observability: AWS placement and storage are unchanged`; `observability: The collector reaches the backends by Service DNS` | ✅ |
| AC2 | WHEN `up` runs on a cluster with no `control-plane=true` node THEN every backend and Grafana reaches Ready | `observability: Backends reach Ready without a control-plane node` | ⚠️ Off-AWS storage claim depends on #1035; stubbed until it lands. Readiness and placement proven here. |
| AC3 | WHEN Grafana queries a datasource THEN the URL is the backend's Service DNS name | `observability: Grafana datasources use Service DNS` | ✅ |
| AC4 | WHEN a backend Pod is rescheduled to another node in the volume's zone THEN Grafana and the collector still reach it | `observability: A rescheduled backend stays reachable` | ✅ (AC reworded for EBS zonal pinning, 2026-11-13) |
| AC5 | WHEN `down` runs THEN the flush contract is unchanged | `observability: The collector reaches the backends by Service DNS` (K8s-API scale-to-0 + collector-stop unchanged); HTTP shutdown calls repoint via #1090 | ✅ here; the HTTP shutdown repointing is #1090 |
| Scope | Backends and Grafana on the pod network behind ClusterIP Services, no hostNetwork | `observability: Backends reach Ready without a control-plane node` | ✅ |
| Scope | Placement from the environment; AWS keeps control-node, default elsewhere any node, user label allowed | `observability: AWS placement and storage are unchanged`; `observability: Placement follows the environment` (kube-state-metrics) | ✅ |
| Scope | Backend data placement follows #1035: hostPath where `HOST_PATH`, otherwise a claim | `observability: Off owned nodes, backend state is on a retained claim` | ⚠️ Storage-type selection owned by #1035 |
| Scope | kube-state-metrics selector swap | `observability: Placement follows the environment` | ✅ |
| Scope | Collector backend exporter endpoints to Service DNS | `observability: The collector reaches the backends by Service DNS` | ✅ |
| Scope | Grafana documents same-origin under one port-forward (decision B/K12) | `test-documents: Documents share Grafana's single connection`; `test-documents: A document is served` | ✅ |
| Risk | Collector `localhost` endpoint left unchanged → dashboards empty on AWS | `observability: The collector reaches the backends by Service DNS` | ✅ called out as the key AC1 regression |
| Risk | emptyDir loses Pyroscope metastore / unflushed samples on reschedule | `observability: Off owned nodes, backend state is on a retained claim` (emptyDir refused) | ✅ |
| Risk | CLI readers / teardown HTTP shutdown break when hostNetwork drops | — | ➡️ #1090 (service access); this change merges after it |
| Risk | Dropping hostNetwork breaks IMDS/instance-role access on AWS | — | ✅ Not a regression: `EC2InstanceService.kt:149-155` already sets IMDS hop limit 2 for pod-network pods |
