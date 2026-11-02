| Source | Requirement | Covering scenario(s) | Status |
|---|---|---|---|
| AC: `up` starts a missing compactor, 1 task | cluster-lifecycle: up makes sure the account compactor runs | "up starts a missing compactor"; account-compactor "The service runs one task" | ✅ Covered |
| AC: `up` leaves a running service as it is | cluster-lifecycle: up makes sure the account compactor runs | "up leaves a running compactor alone" | ✅ Covered |
| AC: `down` for the last tagged cluster naming the bucket, any region, stops the service | cluster-lifecycle: down stops the account compactor after the last cluster | "The last cluster stops the compactor" | ✅ Covered |
| AC: `down` with another tagged cluster naming the bucket keeps it running | cluster-lifecycle: down stops the account compactor after the last cluster | "Another cluster keeps the compactor running" | ✅ Covered |
| AC: `observability compactor start`, `stop`, `status` | account-compactor: The compactor is controlled by hand | "Start by hand"; "Stop by hand"; "Status shows state, task and logs" | ✅ Covered |
| AC: compaction keeps every sample, log line and trace; no retention on | account-compactor: The compactor compacts and never deletes data by retention; observability-store: No observability backend deletes data automatically | "Retention is off in every tool"; "Compaction keeps every sample, log line and trace"; "The account compactor runs with retention off" | ✅ Covered |
| AC: only the compactor's task role can delete under `mimir/`, `loki/`, `tempo/` | observability-store: The cluster cannot delete observability objects; account-compactor: Only the compactor's task role can delete observability objects | "Only the compactor deletes while a cluster runs"; "A backend delete is denied"; "EMR cannot delete compacted roots"; "The task role may delete under the compacted roots" | ✅ Covered |
| AC: compactor stopped > 1 hour, metrics queries still succeed | observability-store: Mimir reads the whole shared store | "Queries succeed while the compactor is stopped" | ✅ Covered |
| AC: tenants `default` and `acme` give per-tenant and all-tenants datasources | observability-store: Grafana has a datasource per tenant and one for all tenants | "One datasource per tenant and one for all tenants" | ✅ Covered |
| AC: exactly one profiles datasource | observability-store: Grafana has a datasource per tenant and one for all tenants | "Exactly one profiles datasource" | ✅ Covered |
| AC: new tenant appears on `grafana update-config` | observability-store: Grafana has a datasource per tenant and one for all tenants | "A new tenant appears on update-config" | ✅ Covered |
| AC: same tenants, different URLs differ only in URLs | observability-store: Grafana has a datasource per tenant and one for all tenants | "Only the URLs depend on the backend URLs" | ✅ Covered |
| Scope: Mimir local block retention cut short (2h) | observability-store: Mimir reads the whole shared store | "Local blocks are kept for 2 hours" | ✅ Covered |
| Scope: bucket index rewritten every minute | account-compactor: One compactor service runs per account | "The task runs every compactor" | ✅ Covered |
| Owner decision 2: `down` only flushes and waits; seven verify checks removed | cluster-lifecycle: Cluster Teardown | "down runs no verify check"; "A flush that does not finish stops down" | ✅ Covered |
| Owner decision 1: Loki compacts today's table | account-compactor: One compactor service runs per account | "The task runs every compactor" (requirement text: every table, today's included) | ✅ Covered |
| Architect 0.2 / critic 7: Tempo retention off via unreachable value; empty tenant deletion locked | account-compactor: The compactor compacts and never deletes data by retention | "Retention is off in every tool" | ✅ Covered |
| Architect 0.3: Mimir partial-block deletion off | account-compactor: The compactor compacts and never deletes data by retention | "Retention is off in every tool" | ✅ Covered |
| Architect 0.4 / D2: service follows the bucket's region | account-compactor: One compactor service runs per account | "The service follows the bucket's region" | ✅ Covered |
| Architect 0.5 / critic 6: EMR roles can delete | observability-store: The cluster cannot delete observability objects | "EMR cannot delete compacted roots" | ✅ Covered |
| Critic 1: Loki compactor breaks `down`'s index-in-S3 check | cluster-lifecycle: Cluster Teardown | "down runs no verify check" | ✅ Covered |
| Critic 2: Tempo compaction drops large traces | account-compactor: The compactor compacts and never deletes data by retention | "Retention is off in every tool" | ✅ Covered |
| Critic 3: two profiles in one account share fixed names | — | — | ⚠️ Excluded — out of scope per owner (situation does not happen) |
| Critic 4: D7 bounds (13h vs Mimir S3 check; 2h gaps during long outage) | observability-store: Mimir reads the whole shared store | "Queries succeed while the compactor is stopped"; "Local blocks are kept for 2 hours" | ✅ Covered — 13h concern gone with the Mimir S3 check; owner chose 2h |
| Critic 5: D5 roll contradicts AC | cluster-lifecycle: up makes sure the account compactor runs | "up leaves a running compactor alone" | ✅ Covered |
| Critic 8: home tenant has two datasources per signal | observability-store: Grafana has a datasource per tenant and one for all tenants | "The stable UIDs read the cluster's own tenant"; "One datasource per tenant and one for all tenants" | ✅ Covered — owner kept stable UIDs on the home tenant; each tenant has one `<signal>-<tenant>` datasource |
| Architect: compactor VPC must survive `down --all` and not count as a cluster | account-compactor: The compactor's network stays out of cluster teardown | "Teardown of all clusters keeps the compactor VPC"; "The compactor VPC is not counted as a cluster" | ✅ Covered |
| Architect: Grafana 40-char UID limit | observability-store: Grafana has a datasource per tenant and one for all tenants | "A long tenant UID is shortened" | ✅ Covered |
| Architect risk: concurrent `up` races | — | — | ⚠️ Excluded — out of scope per owner |
| Architect risk: stop mid-compaction leaves inert partial Mimir blocks | — | — | ⚠️ Excluded — no data loss; not designed for per owner |
| Architect risk: shared Tempo work cache | — | — | ⚠️ Excluded — repeated work only, no data loss |
| Architect risk: store-gateway load, cost | — | — | ⚠️ Excluded — accepted trade-off, no behavior to specify |
