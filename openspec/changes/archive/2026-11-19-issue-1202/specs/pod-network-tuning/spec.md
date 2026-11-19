## ADDED Requirements

### Requirement: Pods in the pod network run with the tuned per-namespace network values

Every pod created in the pod network (`hostNetwork` false or unset), in any namespace, SHALL run with these network values, the same ones node tuning sets in the host network namespace:

| Key | Value |
|---|---|
| `net.core.somaxconn` | 65535 |
| `net.ipv4.tcp_max_syn_backlog` | 65535 |
| `net.ipv4.tcp_rmem` | `4096 131072 16777216` |
| `net.ipv4.tcp_wmem` | `4096 16384 16777216` |
| `net.ipv4.tcp_keepalive_time` | 60 |
| `net.ipv4.tcp_keepalive_intvl` | 10 |
| `net.ipv4.tcp_keepalive_probes` | 3 |
| `net.ipv4.tcp_slow_start_after_idle` | 0 |
| `net.ipv4.tcp_tw_reuse` | 1 |

The pod's spec SHALL carry them as `securityContext.sysctls`, so `kubectl get pod` shows them.  This SHALL hold for every kit's database pods, for pods an operator builds, for kit client pods (sysbench, clickhouse-benchmark), and for any later kit, with no sysctls block in any kit.  `net.ipv4.ip_local_port_range` and `net.ipv4.ip_local_reserved_ports` SHALL stay at the kernel defaults in a pod, because the host's reserved list names host ports, not the pod's own listener ports.  A pod that sets one of these keys itself SHALL keep its own value.  Host-network pods SHALL get no injected sysctls; they already run in the tuned host namespace.

#### Scenario: A kit database pod reports the tuned values
- **GIVEN** a cluster brought up by `up` and a kit started on it
- **WHEN** the operator reads the values inside the network namespace of any of the kit's pods
- **THEN** every key in the table has the value in the table
- **AND** the pod's `spec.securityContext.sysctls` lists every key in the table

#### Scenario: An operator-built pod gets the values
- **WHEN** an operator (CloudNativePG, Strimzi, TiDB, Altinity, Flink) creates a pod from a kit's custom resource
- **THEN** that pod has the tuned values, although the kit's resource names no sysctls

#### Scenario: A pod that sets a key keeps its value
- **GIVEN** a pod spec that sets `net.ipv4.tcp_keepalive_time` to 300
- **WHEN** the pod is created
- **THEN** it runs with `tcp_keepalive_time` 300 and the tuned value of every other key in the table

#### Scenario: A host-network pod is left alone
- **WHEN** a host-network pod (the OTel collector) is created
- **THEN** its spec has no injected sysctls
- **AND** it reads the host namespace's tuned values

#### Scenario: The pod port range is the kernel default
- **WHEN** the operator reads `net.ipv4.ip_local_port_range` inside a kit pod
- **THEN** it is the kernel default, `32768 60999`, and `ip_local_reserved_ports` is empty

#### Scenario: Each kit installs, starts and serves a workload
- **GIVEN** the injected values
- **WHEN** each packaged kit is installed and started on a real cluster and given a workload
- **THEN** every pod of the kit becomes Ready and the workload completes

### Requirement: One source for host and pod network values

The pod values SHALL be read from the packaged node tuning sysctl file, the file node setup installs on every host, from the distribution's classpath.  They SHALL NOT be repeated anywhere else, and SHALL NOT be read from a workspace or a source checkout.  Every `net.*` key in that file SHALL be classed as either injected into pods or host only.  The build SHALL fail when a key is in neither class or in both, and when an injected key is missing from the file.

#### Scenario: A changed host value reaches pods
- **WHEN** a value of an injected key changes in the packaged sysctl file
- **THEN** pods created on a cluster brought up by that build carry the new value, with no other change

#### Scenario: A new network key must be classed
- **WHEN** a `net.*` key is added to the packaged sysctl file and not classed
- **THEN** a unit test fails and names the key

#### Scenario: Installed from a package
- **GIVEN** the tool installed with Homebrew, with no source checkout
- **WHEN** `up` runs
- **THEN** pods get the values from the packaged file

### Requirement: Every kubelet permits the injected sysctls

Every node's kubelet (control, db and app) SHALL permit every injected key with `allowed-unsafe-sysctls`, safe keys included, from a configuration file written by node bring-up on every run, before the kubelet first starts.  A pod carrying the injected sysctls SHALL never fail with `SysctlForbidden`.  This SHALL be done in the K3s bootstrap step, so a cluster whose nodes the tool does not bootstrap is not affected.

#### Scenario: A db pod is admitted on every node type
- **WHEN** a pod-network pod with the injected sysctls is scheduled on a control, db or app node
- **THEN** the kubelet admits it and it starts

#### Scenario: A re-run of up keeps the allowlist
- **WHEN** `up` runs again on a live cluster
- **THEN** each node's allowlist file names every injected key

### Requirement: up applies the injection and proves it before any kit

`up` SHALL apply the cluster-wide injection before it deploys the observability stack, and before any kit can be installed.  It SHALL then prove the injection is in effect with a server-side dry-run pod that carries every injected value, waiting at most 30 seconds.  When the injection cannot be applied or the dry-run pod lacks a value, `up` SHALL fail and name the missing keys.  A pod-network pod that the injection cannot be applied to SHALL be refused, never created untuned.

#### Scenario: Fresh cluster
- **WHEN** `up` brings up a new cluster
- **THEN** a server-side dry-run pod carries every injected value before the observability stack is deployed

#### Scenario: Injection not in effect
- **WHEN** the dry-run pod lacks an injected key after 30 seconds
- **THEN** `up` fails and the failure names each missing key
