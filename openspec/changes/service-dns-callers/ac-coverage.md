| Source | Criterion / risk | Covering scenario(s) | Status |
|--------|------------------|----------------------|--------|
| AC1 | On AWS no backend or Grafana container declares a `hostPort` | observability: "On AWS no backend or Grafana container declares a host port" | Covered |
| AC2 | Every in-cluster producer reaches its backend by Service DNS; data lands as today | observability: "Pod producers render the Pyroscope Service address", "A host-network producer's profile lands in Pyroscope", "The collector reaches the backends by Service DNS", "Grafana datasources use Service DNS"; profiling: "Shipped chunks go to the relay"; observability: "A JFR pushed to the relay lands in Pyroscope" | Covered |
| AC3 | Nothing in the source builds a backend URL from a node IP | observability: "No source builds a backend URL from a node IP", "Pod producers render the Pyroscope Service address", "The stack and the collector sync need no control node IP" | Covered, with one named exception: EMR (outside the cluster) and operator-facing addresses use node IP + NodePort (D4) |
| Scope | Telemetry redirect | telemetry-redirect: "Base host derives all four endpoints", "A redirected cluster lands all four signals on another easy-db-lab cluster"; observability: "Pod producers render the redirect endpoint under redirect" | Covered |
| Scope | Alloy eBPF on AWS | observability: "Pod producers render the Pyroscope Service address" | Covered |
| Scope | Sidecar Pyroscope agents | observability: "Pod producers render the Pyroscope Service address" | Covered |
| Scope | Stress jobs (address + control-node lookup, #1101 D6) | observability: "Stress jobs need no control node to address Pyroscope" | Covered |
| Scope | Remove `publishOnNode` | observability: "On AWS no backend or Grafana container declares a host port" | Covered |
| Scope | Docs + specs | tasks 8.x | Not a behaviour; tracked as tasks |
| Risk | `down` flush breaks without host ports | service-access: "Teardown flush reaches the backends with no host ports" | Covered |
| Risk | CLI seam / status dial a removed port | service-access: "AWS reads go through the SOCKS tunnel to the NodePort", "Direct environment bypasses SOCKS"; networking: "Each NodePort reaches its backend" | Covered |
| Risk | Host process cannot resolve cluster DNS | observability: "The reconciler is configured with the relay"; profiling: "Shipped chunks go to the relay" | Covered |
| Risk | Alloy relay JFR support unverified | observability: "A JFR pushed to the relay lands in Pyroscope" (task 1.1 spike) | Covered |
| Risk | Relay down loses data | profiling: "Relay unreachable is a shipping failure, not data loss" | Covered |
| Risk | NodePort collision | networking: "Published ports do not collide", "Off owned nodes the Services stay ClusterIP" | Covered |
| Risk | EMR loses Pyroscope | spark-emr: "Pyroscope agent configured with correct server address" | Covered |
| Risk | Port numbers change for users | — | Intentional exclusion: ephemeral clusters, no migration; docs updated (task 8) |
| Risk | CoreDNS restart blips host-network lookups | — | Intentional exclusion: agents retry; already true off AWS |
| Risk | Config-hash rolls once | — | Intentional exclusion: expected behaviour of `ConfigHashAnnotator` |
