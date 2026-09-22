# Acceptance criteria coverage

| Source | Requirement | Covering scenario(s) | Status |
|--------|-------------|----------------------|--------|
| AC (819) | No `--cni` → Cilium | `networking: Default provision uses Cilium ENI native routing` | ✅ Covered |
| AC (819) | `--cni=flannel` still selectable | `networking: Flannel remains selectable`; `networking: Flannel cluster renders no Cilium scrape jobs` | ✅ Covered |
| AC (memcached) | Install and start bring up a ready pod | `memcached-kit: Install and start bring up a ready pod on a db node` | ✅ Covered |
| AC (memcached) | Another pod can set/get over node private IP + port | `memcached-kit: Another pod can set and get over the endpoint` | ✅ Covered |
| AC (memcached) | Endpoint declared in `kit.yaml` resolves via `kit info` | `memcached-kit: kit info resolves the endpoint` | ✅ Covered |
| AC (memcached) | `stop` removes everything by label | `memcached-kit: Stop removes all kit objects` | ✅ Covered |
| AC (memcached) | `uninstall` cleans up, including PVs | `memcached-kit: Uninstall leaves nothing behind`; `memcached-kit: No persistent volumes are created` | ✅ Covered — the kit creates no PVs, so there are none to clean up |
| AC (memcached) | Cache-size arg with a default | `memcached-kit: Default cache size`; `memcached-kit: Custom cache size` | ✅ Covered |
| AC (memcached) | Start/install fails clearly on conflict | `memcached-kit: Second install fails clearly` | ✅ Covered |
| AC (memcached) | Metrics and dashboard | `memcached-kit: Exporter series appear in VictoriaMetrics`; `memcached-kit: Dashboard shows live data` | ✅ Covered |
| AC (memcached) | Verified live on AWS | — | ⚠️ Excluded — verified by live-validation task group 6 (6.5), not a spec behaviour. The scenarios above are the behaviours that run checks |
| AC (neo4j) | Install succeeds | `neo4j-kit: Install succeeds` | ✅ Covered |
| AC (neo4j) | Start: Ready pod on a db node, reports Bolt + HTTP endpoints | `neo4j-kit: Start yields a Ready pod on a db node`; `neo4j-kit: Start reports both endpoints` | ✅ Covered |
| AC (neo4j) | Cypher over the Bolt NodePort returns a result | `neo4j-kit: Cypher over the Bolt NodePort returns a result`; `neo4j-kit: Advertised address is the node address` | ✅ Covered |
| AC (neo4j) | JVM + db metrics reach VictoriaMetrics via the agent and are in `metrics-catalog.json` | `neo4j-kit: Metrics arrive under job neo4j`; `neo4j-kit: Catalog reflects exported metrics` | ✅ Covered — JVM metrics unconditionally; database-level metrics only if Neo4j Community exposes them (requirement text says so; decided in task 4.4/4.5) |
| AC (neo4j) | Grafana folder with a dashboard showing live data | `neo4j-kit: Dashboard shows live data` | ✅ Covered |
| AC (neo4j) | `stop` removes pod and NodePort service by label | `neo4j-kit: Stop removes pod and NodePort services` | ✅ Covered |
| AC (neo4j) | `kit list` shows neo4j | `neo4j-kit: kit list shows neo4j` | ✅ Covered |
| AC (neo4j) | Docs | Requirement `Neo4j kit is listed and documented` (text) | ⚠️ Excluded from scenarios — documentation content is not an observable runtime behaviour; stated in the requirement text and task 4.9 |
| Risk | `hostPort` breaks under Cilium (Trino/Presto/Flink) | `networking: hostPort kit is reachable and scraped on a Cilium cluster`; `networking: portmap chaining survives an upgrade` | ✅ Covered |
| Risk | AMI predating the Cilium fixes | `networking: OS leaves Cilium's secondary ENIs unmanaged` | ✅ Covered — the scenario pins the AMI-baked drop-ins; tasks 1.8 and 6.1 require the rebuild |
| Risk | Neo4j Community lacks database-level metrics | `neo4j-kit: Metrics arrive under job neo4j`; `neo4j-kit: Dashboard shows live data` | ✅ Covered — requirement makes db-level metrics conditional; JVM panels guarantee live data |
| Risk | `OTEL_SERVICE_NAME` missing → `job` unknown | `neo4j-kit: Metrics arrive under job neo4j` | ✅ Covered |
| Risk | Neo4j config keys are version-specific | `neo4j-kit: Default version`; `neo4j-kit: Explicit supported version` | ✅ Covered — versions restricted to 5.x and 2025.x, which share the `server.*` keys |
| Risk | Collision check not firing | `memcached-kit: Second install fails clearly`; `neo4j-kit: Second install fails clearly` | ✅ Covered |
| Risk | No single db IP template variable | `neo4j-kit: Advertised address is the node address` | ✅ Covered — shell step reads the first db node IP |
| Risk | Half-applied manifest on a NodePort clash with another kit | — | ⚠️ Excluded from scenarios — prevented by the NodePort convention (task group 2) and distinct fixed ports (31211, 30687, 30474) that collide with no existing kit or Hubble UI (31234); not a runtime behaviour to specify |
