## Overrides existing behavior

- `observability` "OTel Collector is reachable via ClusterIP Service": the Service now adds OTLP HTTP 4318 and `internalTrafficPolicy: Local`, selects only the core's pods, and is no longer pinned to the `default` namespace in the requirement text. Its old scenario "Existing host-network behaviour is unchanged" (Fluent Bit to `127.0.0.1:4318`, collector scrapes `localhost:<port>`) no longer holds for the core: Fluent Bit reaches the node listener owned by the host add-on, and the core scrapes host ports at the node IP. Rewritten as a MODIFIED delta.
- `observability` "OTel Collector collects logs from multiple sources including K8s pods": host file logs move to the host add-on (gated on `PRIVILEGED`) and pod logs to the core (gated on `HOST_PATH`, read from `/var/log/pods`). Rewritten as a MODIFIED delta.
- `observability` "K8s workloads use hostPort for external port access": its scenario "OTel DaemonSet scrapes workload metrics via hostPort" said the DaemonSet is hostNetwork and scrapes `localhost`. The core is now on the pod network and scrapes the node IP. Rewritten as a MODIFIED delta.

## Conflicts with other in-flight changes

- `issue-1037` (PR #1084, per-environment capabilities): this change widens its `Stage.requires: NodeCapability?` to `Set<NodeCapability>` and adds Beyla/ebpf_exporter/host-add-on/Pyroscope-eBPF gates. Its `tool-execution` journald requirement says the journald collector forwards to "the main OTel collector on localhost"; after this change localhost on the node is the host add-on, which forwards to the core — the endpoint Fluent Bit dials is unchanged.
- #1096 (capability probe): owns the `PRIVILEGED`/`HOST_PATH` enum entries; whichever change lands first adds them.
- #1091 (backends as Services): owns the collector's backend exporter endpoints. This change does not edit them.
