## Acceptance criteria → scenarios

| Acceptance criterion (#1092) | Covering scenario(s) |
|---|---|
| WHEN `up` runs on AWS THEN the same metrics, logs and traces arrive as today, and the comparison dashboards still work | "Every component deploys on AWS"; "Host processes keep their node endpoint"; "Host file logs reach Loki"; "Stress telemetry reaches the backends". Stress JVM `host_name` on dashboards is task 9.1: Live Grafana readback via the dashboard-editor agent is explicitly deferred to the first real-cluster run; this PR changes no dashboard JSON. |
| WHEN the environment lacks `PRIVILEGED` THEN the core collector reaches Ready, pod and application telemetry flows, and the host add-on, Beyla, ebpf_exporter and journald are each reported as skipped | "Core reaches Ready without PRIVILEGED"; "Privileged components skipped without PRIVILEGED"; "Pyroscope server deploys without the eBPF agent" |
| WHEN a kit pod sends OTLP THEN it uses the Service DNS name, and no kit template or kit script refers to `HOST_IP:43` or `CONTROL_HOST_PRIVATE:43` | "Kit pod uses the Service DNS name"; "No kit refers to a node OTLP address"; "Gauge push goes through the API server" |
| WHEN `down` stops the telemetry senders THEN both collector DaemonSets are deleted and waited for | "Both DaemonSets deleted and awaited"; "Host add-on stopped before the core"; "Missing add-on does not fail down" |

## Architect-surfaced risks → scenarios

| Risk | Covering scenario(s) |
|---|---|
| OS hostname on the pod network is the pod name, breaking `host.name`/`node_role` | "Node identity comes from the node name" |
| `internalTrafficPolicy: Local` drops traffic on a node with no core pod | "Every node runs a core pod"; "Traffic stays on the producer's node" |
| Service accidentally selects the host add-on's pods | "Service selects only core pods" |
| Host file logs inflate the `cassandra.log.*` counters | "Forwarded host logs are not counted as Cassandra logs" |
| Rejected Pyroscope eBPF Alloy fails the rollout wait | "Pyroscope server deploys without the eBPF agent" |
| `memory_limiter` creeping into either collector | "No collector has a memory limiter" |
| Static `localhost` scrapes break off hostNetwork | "Core collector scrapes a host-exposed metrics port at the node IP" |
| Pod logs require a hostPath | "Pod logs skipped without HOST_PATH" |
| The Service proxy spreads a kit's gauge pushes over core pods on every node, one series per node | "Every gauge push lands on one node" |
| A TERM during a gauge push leaves `kubectl proxy` running | "An interrupted gauge push leaves nothing running" |
| An add-on that does not drain gets the core deleted under it, or a fresh deadline | "An add-on that does not drain leaves the core running" |
| The core skips kubelet TLS verification, or can write its root filesystem | "Core runs unprivileged on the pod network"; "Core verifies the kubelet it reads stats from" |
| A node's core restart drops its host data (the add-on's exporter gives up after 300s) | "A core restart does not drop the node's host data" |
| A never-deployed add-on is reported as deleted by the save | "Missing add-on does not fail down" |

## Intentional exclusions

- **Port race on in-place update** (old core pods hold hostPorts while the add-on starts): an ordering concern, not observable behaviour; covered by task 8.1, not a scenario.
- **Stress JVM `host.name` shift on dashboards**: task 9.1, not a spec scenario. Live Grafana readback via the dashboard-editor agent is explicitly deferred to the first real-cluster run; this PR changes no dashboard JSON.
- **`k8s_events`**: deferred by owner decision D4.
