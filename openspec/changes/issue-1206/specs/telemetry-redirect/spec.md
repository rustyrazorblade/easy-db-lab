## MODIFIED Requirements

### Requirement: A redirecting cluster deploys collectors but no local backends

WHEN a cluster is brought up in redirect mode, its node-level collectors SHALL be deployed and running, and no Mimir, Loki, Tempo, Pyroscope server, or Grafana workload SHALL be stood up on that cluster's control node.  The Pyroscope eBPF agent and its profile relay SHALL still be deployed, and the relay SHALL forward to the external Pyroscope; only the Pyroscope server is skipped.

#### Scenario: Redirect bring-up runs collectors without local storage

- **WHEN** a cluster is brought up in redirect mode with all four endpoints supplied
- **THEN** the OTel Collector, Fluent Bit, Grafana Alloy with its profile relay, Beyla, ebpf_exporter, YACE, and OTel Java agent are deployed and running
- **AND** no Mimir, Loki, Tempo, Pyroscope server, or Grafana workload exists on the control node

#### Scenario: The profile relay forwards to the external Pyroscope

- **WHEN** a redirecting cluster's eBPF agent sends a profile
- **THEN** Alloy sends it to the relay on the node, and the relay forwards it to the redirect's profiles URL and not to the control node

#### Scenario: Normal bring-up is unchanged

- **WHEN** a cluster is brought up without redirect mode
- **THEN** the full local stack including Grafana is stood up and the collectors ship to the in-cluster backends, exactly as before
