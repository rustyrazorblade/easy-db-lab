# Kubernetes-forward architecture

This page describes the target architecture for running easy-db-lab on any Kubernetes cluster: one easy-db-lab builds on EC2, K3s in Docker on a laptop, or a cluster the user already has. It is the design for epic #1032 and the work that lands on the `local-k8s-edbl` integration branch. Every issue named here is a child of that epic.

Owner decisions, 2026-11-11: the defaults listed under [Decisions](#decisions) are adopted. The design reframes work already planned in #1062 (multiple cloud providers) and #1032. It replaces neither.

## The direction

The core contract of easy-db-lab is **a reachable Kubernetes API plus a place to keep observability data**.

- A **provisioner** may create that cluster: EC2 + K3s, K3s in Docker, or nothing when the user brings a cluster.
- Everything above the contract is the same on every environment: the observability stack, the kits, the `down` flush contract and the commands.
- That layer reaches the cluster only through the Kubernetes API and cluster Services. It does not use SSH, node IPs or host paths unless the environment declares the capability for them.
- Anything specific to one cloud is an adapter (provisioner, object store, identity) or a provider kit (`aws-opensearch`, `aws-spark`, `aws-aurora`).

## Principles

1. **One door.** Above the provisioner, all cluster access goes through the Kubernetes API and cluster Services.
2. **Capabilities, not environment names.** Code asks "are privileged pods allowed here?", never "is this AWS?". A new environment is new data, not new branches.
3. **One set of manifests.** AWS runs the portable form too. There is never an AWS path and a second generic path to keep in sync.
4. **Explicit credentials.** Pods get store credentials from a Secret or from workload identity. The node's identity is used only on nodes easy-db-lab owns.
5. **Data rules hold by construction.** Storage that holds owner data is retained. `down` only flushes, on every environment. Nothing deletes observability data automatically.
6. **Cloud pieces are opt-in.** A provider kit declares what it needs. It is hidden where the need is not met and fails fast if forced.

## What ties easy-db-lab to clusters it builds

Measured on `main` at `b03a066c`. Most of these are in manifests, YAML, scripts and names, which is why the AWS-SDK ArchUnit rule (#746) cannot catch them.

| Coupling | Evidence | Fixed by |
| --- | --- | --- |
| 9 core workloads require `node-role.kubernetes.io/control-plane: "true"`, which only K3s sets | `MimirManifestBuilder.kt:178`, `GrafanaManifestBuilder.kt:140` and 7 more builders | #1091 |
| 14 components use `hostNetwork`, and producers send telemetry to the node IP | `OtelManifestBuilder.kt:517-552`, `StressJobService.kt:525`, `edl-kit-lib.sh:247` | #1091, #1092 |
| The CLI reaches services by the control node's IP over SOCKS | `ObservabilityHttp.kt:87-117`, `GrafanaClient.kt:186`, `Status.kt:514` | #1090 |
| 149 `controlHost: ClusterHost` parameters in 37 files, threaded to a client that reads only the kubeconfig | `K8sClientProvider.kt:29-43` | #1089 |
| S3 credentials come only from the EC2 instance role; no code passes credentials to a pod | backend configs, `ClusterConfigData.kt:35-39` | #1061 |
| Dashboards, the compactor jar and registry certificates are pushed over SSH | `GrafanaDashboardTreeUploader.kt:56`, `CompactorAgentInstaller.kt:33-50` (PR #1071), `EC2RegistryService.kt` | #1093, #1048, #1080 |
| No pod sets a security context | no match for `runAsNonRoot` or `seccompProfile` under `src/main` | #1095 |
| Namespace `default` hard-coded in 12 builders and 137 kit lines | `Constants.kt:372`, kit templates | #1094 |
| 6 kits bind claims to easy-db-lab's local PVs with a label selector; TiDB names `local-path` | `cassandra-pods/statefulset.yaml.template:186-190`, `tidbcluster.yaml.template:21` | #1100 |
| Kits mount agents from AMI paths and use hostPort; one role per node | `neo4j/statefulset.yaml.template:56`, `presto/bin/start.sh.template:27`, `Constants.kt:25` | #1101 |
| The AWS region is the Cassandra datacenter name; the config uses `Ec2Snitch` | `CqlSessionService.kt:83`, `WriteConfig.kt:63` | #1097 |
| Only the instance type is recorded about where a run happened | `ClusterState.kt:135` | #1098 |

## Environments

| Environment | Provisioner | Node access | Reach | Observability data | Capabilities |
| --- | --- | --- | --- | --- | --- |
| AWS | EC2 + K3s | SSH (public IP, Tailscale or SSM) | SOCKS | S3 account bucket | all |
| K3s in Docker (#1041) | containers | `docker exec` (#1039) | direct | host directory, never deleted (#1035) | declared per #1037 |
| Your cluster (#1082) | none | none | port-forward | object store (required for durable data) or a retained volume labelled scratch | probed at `init` (#1096) |
| GCP, Azure VMs (#1050–#1054, #40) | later | SSH | SOCKS | GCS, Blob | all |

GCP and Azure VM provisioning is deferred behind running on GKE and AKS as your cluster. It is revived when a test needs VM-level control (the SSH `cassandra` commands, local-NVMe machine shapes, isolated nodes, guaranteed eBPF).

## The cluster contract

Resolved once per command from the workspace, as data. Consumers read the fields they need and never switch on the environment.

| Field | Meaning | AWS | K3s in Docker | Your cluster |
| --- | --- | --- | --- | --- |
| `api` | kubeconfig path and context, plus the cluster tooling form (#1078) | workspace kubeconfig | workspace kubeconfig | the user's context, copied and pinned at `init` |
| `observabilityStorage` | `ObjectStore(scheme, bucket, prefix, credentialSource)` or `Volume(storageClass)` (#1035, #1061) | S3 | host directory | object store, or retained volume |
| `kitStorage` | storage class and default size for kit claims (#1100) | local PVs | local PVs | the user's retained class |
| `exposure` | how the CLI reaches a Service (#1090) | SOCKS | direct | port-forward |
| `placement` | infra-pod placement, role-to-selector map, host networking on or off (#1091, #1101) | control node, `type=<role>` | `type=<role>` | anywhere, optional role labels |
| `capabilities` | `NodeCapability` set (#1037, #1096) | declared | declared | probed, overridable |
| `nodeAccess` | how to run a command on a node, or none (#1039) | SSH | `docker exec` | none |
| `imageSource` | where images and pull secrets come from | ECR + public | preloaded + public | public, or a registry the user supplies |
| `namespace` | where easy-db-lab installs (#1094) | `default` | `default` | `easy-db-lab` |

The sealed environment recorded in `state.json` (#1040) names only the provisioner. #1040 makes the kubeconfig case first-class from the start.

## Reaching the cluster

- **Kubernetes API.** Every K8s service uses the recorded kubeconfig and context. None takes a `ClusterHost` (#1089). The kubeconfig is loaded by path with an explicit context, so relative certificate paths and exec auth plugins work.
- **Cluster services.** One service-access seam opens a named Service and port, with three implementations (#1090):
  - `Socks`: through the SOCKS tunnel, per client, as today. The JVM-global proxy properties are never touched.
  - `Direct`: no proxy.
  - `PortForward`: a Fabric8 port-forward bound to `127.0.0.1`.
- Grafana runs anonymous Admin, so it is never exposed beyond `127.0.0.1`. There is no Ingress or LoadBalancer.

## Observability stack

- **Backends.** Mimir, Loki, Tempo, Pyroscope and Grafana run on the pod network behind ClusterIP Services. Datasources use Service DNS. Placement comes from the contract; AWS keeps the control node (#1091).
- **Collector.** An unprivileged core DaemonSet serves OTLP through the `otel-collector` Service with `internalTrafficPolicy: Local`, so each producer reaches its own node's collector. Host metrics, host logs, Beyla, ebpf_exporter, journald and Pyroscope eBPF are privileged add-ons, deployed only where `PRIVILEGED` is present and reported with `StageSkipped` otherwise (#1092).
- **Dashboards.** Core dashboards are ConfigMaps projected into Grafana's provisioning tree, not an SSH upload (#1093).
- **AWS-only components.** YACE, s3manager and the documents signing proxy are gated by capability (#1037), then move to `aws-*` provider kits.
- **Integration.** easy-db-lab always installs its own stack. `init --redirect-telemetry` stays as it is.

## Store and identity

- **Store adapter.** S3, GCS, Azure Blob or a retained volume, behind `ObjectStore` with a neutral `StorePath` (replacing `ClusterS3Path`) and a store prefix so one bucket can be shared (#1061).
- **Credentials.** A Secret or workload identity (EKS Pod Identity, GKE and Azure Workload Identity). The policies a user must grant are printed by the CLI.
- **Two identities.** The writer (backends, collectors, documents, CLI) cannot delete. The compactor can delete only under the roots it compacts, and only the objects it merged. On EC2 the node's role may serve as the writer.
- **No-delete rule.** It is enforced by the store identity on every environment, not by one AWS role. PR #1071 (in-cluster compactor) narrows the instance role's deny and relies on the store lease for `mimir/`, `loki/` and `tempo/`. Two identities restore the structural guard. That is follow-up work on #1048.
- **Durable data on your cluster.** An object store is required for data that must outlive the cluster. A retained volume is allowed and labelled as data that lives as long as the user's cluster. A StorageClass that reclaims with `Delete` is refused (#1035, #1100).

## Kit target contract

#1055, re-scoped. Its sub-issues are #1100 and #1101.

- **Storage.** Kit claims use the environment's kit class and size. Selector-bound local PVs exist only on owned nodes. `10Ti` stays correct on `local-storage`; a dynamic class gets a real default size.
- **Variables.** Neutral names: `STORE_*`, `DATACENTER`, `NAMESPACE` and Service DNS names. Provider variables (`ACCOUNT_BUCKET`, `REGION`, `VPC_CIDR`, `OPENSEARCH_ENDPOINT`) appear only where the environment provides them.
- **Host dependencies.** Agents come from an init container into an `emptyDir`. There is no hostPath to `/usr/local`, no hostPort and no hostNetwork. One node may hold several roles.
- **Requirements.** A kit declares `requires:` from the capability vocabulary plus `aws-account`, instead of `provider:` (decision D14 on #1062 is replaced). A kit is hidden from help where its requirements are not met, and `kit list --all` shows why.
- **Node SSH.** Kit steps that need node SSH run only where `NODE_SSH` is present.

## Capabilities

PR #1084 (#1037) adds per-environment `NodeCapability` gating for journald, eBPF, the registry, CloudWatch and S3. #1096 adds `PRIVILEGED`, `HOST_NETWORK`, `HOST_PATH` and `NODE_SSH`:

- AWS and local declare them.
- On your cluster they are probed at `init` with server-side dry-runs and can be overridden.
- The set is recorded in the workspace and published in `cluster-config`, so dashboards can explain absent panels (#1088).

## Tenancy and pod security

- Version 1 runs one easy-db-lab cluster per Kubernetes cluster and requires cluster-admin.
- The namespace is a parameter now. Cluster-scoped objects get the cluster id as a suffix and the labels `app.kubernetes.io/managed-by=easy-db-lab` and `easydblab/cluster=<id>`. `down` on your cluster removes only labelled objects and never PVs, pre-existing CRDs or data (#1094).
- Every unprivileged pod meets Pod Security "restricted", tested against a K3s TestContainer namespace that enforces it (#1095).
- Kubernetes failures (forbidden, unauthorized, admission, a missing exec plugin) are explained by a registered error handler (#1102).

## Cassandra

- **Two paths.** Cassandra runs on VMs (SSH `cassandra` commands) for performance and disk-latency work, and as the Cassandra kit (#1038) for correctness, upgrades, local and your-cluster runs.
- **One artifact.** The release tarball is the single Cassandra artifact on both paths. The kit uses a multi-arch JRE image and an init container that fetches the tarball and caches it on the volume by checksum. The pod renders the same packer config as the VMs. `--image` stays as an escape hatch. The kit stays a plain StatefulSet (#1099). This resolves the conflict between #961 (remove the Cassandra AMI), which deletes the GHCR Cassandra images, and #1036 (multi-arch images), which planned to build them.
- **Datacenter name.** It is explicit, not the AWS region. The snitch is chosen by environment (#1097).

## Result fidelity

Every kit `start` records an environment fingerprint (#1098) and grades the run:

- **A**: owned, tuned nodes.
- **B**: dedicated nodes on your cluster, with Guaranteed integer CPUs and a known storage class.
- **C**: functional only.

The fingerprint and grade are stored with the test documents and annotated in Grafana, and the comparison dashboards show them. Performance runs are allowed on every environment, but they are always graded.

## The promise to users

| Same on every environment | May differ | Stays VM-only or AWS-only |
| --- | --- | --- |
| Command names and flags | How the cluster comes to exist | The SSH `cassandra` commands, profiling over SSH, `build-image` |
| Kit names and the kit contract; a kit-only plan runs unedited | Node access | `spark` on EMR and `aws-*` kits, hidden where `requires:` is not met |
| Dashboards, datasource UIDs, cluster and tenant labels | The network path | SSM, Tailscale, `socks`, YACE, s3manager, instance-type flags |
| The `down` flush contract and the no-delete rule | Where data is stored, which privileged collectors run, the fidelity grade | Each fails before any side effect, naming the environment and the portable alternative |

## Phases

**Phase A: portable core.** Every item lands on AWS too.
- #1089: kubeconfig-only access.
- #1090: service access.
- #1091: backends and Grafana as Services.
- #1092: collector split.
- #1093: dashboards as ConfigMaps.
- #1080: registry TLS as a Secret.
- #1048: compactor agent as an image.
- #1061 and #1035: neutral store.
- #1055 with #1100 and #1101: kit target contract.
- #1096: capability probe.
- #1094: namespace.
- #1095: security contexts.
- #1097: datacenter name.

**Phase B: K3s in Docker.**
- #1040: environment in state.
- #1039: node commands without sudo.
- #1041: K3s-in-docker provisioner, built on the shared `up` tail that takes only the contract.

**Phase C: your cluster.**
- #1082 (`init` against an existing cluster) absorbs #1047 (bare K8s target).
- #1102: Kubernetes error handler.
- First milestone: kind and Docker Desktop with the full stack, postgres + sysbench and the Cassandra kit, from a Homebrew install with no AWS account. CI runs on kind.

**Cassandra and fidelity track, in parallel.** #1099 (tarball kit), #1098 (fingerprint and grade), then #961 (remove the Cassandra AMI) and #1036 (multi-arch images).

**Phase D: GCP and Azure VMs.** Deferred: #1050–#1054, #40, #702.

**Deferred:** #1056 (ClusterNetwork module).

## Decisions

Adopted 2026-11-11.

| # | Decision |
| --- | --- |
| K1 | The core contract is a Kubernetes API plus an observability store. The environment in state names only the provisioner. Consumers read capability data. |
| K2 | K3s in Docker (#1041) comes first. Your cluster (#1082) follows directly, as the acceptance test that the shared core is portable. |
| K3 | On your cluster, an object store is required for durable data. A retained volume is allowed, labelled as living as long as the cluster. A deleting StorageClass is refused. |
| K4 | GCP and Azure VM provisioning is deferred behind GKE and AKS as your cluster. |
| K5 | Store access uses two identities: a writer that cannot delete, and a compactor that deletes only what it merged. |
| K6 | The Cassandra kit runs the release tarball with the VM path's config. `--image` stays. It is a plain StatefulSet, not cass-operator. |
| K7 | Performance runs are allowed everywhere, with a fingerprint and an A/B/C grade. Privileged node tuning applies only where `PRIVILEGED` is present. |
| K8 | Kits declare `requires:` from the capability vocabulary plus `aws-account`, replacing `provider:`. |
| K9 | On your cluster, the registry is one the user supplies, with a pull secret. |
| K10 | Your cluster, version 1: cluster-admin required, one easy-db-lab cluster per Kubernetes cluster, namespace parameterized now. |
| K11 | easy-db-lab always installs its own observability stack. `--redirect-telemetry` stays as it is. |
| K12 | Grafana is reached only through a `127.0.0.1` port-forward on clusters easy-db-lab does not own. |

## Risks and unknowns

- **Data loss by default.** Default StorageClasses reclaim with `Delete`. Guarded by K3 and #1100.
- **Wrong cluster.** A user's kubeconfig often holds production contexts. The context is pinned at `init`, and the API server is printed (#1089).
- **Plans are not portable yet.** 16 of the 27 test plans use the VM `cassandra` commands. "Run any plan anywhere" holds only for kit-only plans until the Cassandra kit can do what the VM path does.
- **Laptop capacity.** The full stack may not fit Docker Desktop's default memory. Not measured.
- **Compactor lease and sleep.** The lease is timed on each pod's own clock, which may stop while a laptop sleeps. Keep kind and Docker Desktop out of compactor candidacy.
- **Not verified yet:**
  - the control-plane label value on kubeadm-family clusters;
  - port-forward throughput under load;
  - NodePorts on Docker Desktop;
  - multi-arch Beyla and ebpf_exporter images;
  - whether the operator charts pass Pod Security "restricted";
  - whether pods on the pod network can reach the instance metadata service under Cilium.
