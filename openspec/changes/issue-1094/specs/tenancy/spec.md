## ADDED Requirements

### Requirement: The cluster namespace is resolved from the workspace

Every cluster has one Kubernetes namespace, the cluster namespace. The cluster contract SHALL resolve it from the recorded state on each use: the namespace recorded by `init --namespace` when one is recorded, otherwise `default` on EC2 + K3s and on K3s in Docker, and `easy-db-lab` on a cluster the user brings. The contract SHALL be the only place that decides the default. Because it is resolved on each use, an `init --namespace` inside the REPL or the server SHALL take effect on the next command.

#### Scenario: EC2 with no recorded namespace

- **GIVEN** an `ec2-k3s` workspace whose `init` named no namespace
- **WHEN** the contract is resolved
- **THEN** the cluster namespace is `default`

#### Scenario: Local with no recorded namespace

- **GIVEN** a `k3s-docker` workspace whose `init` named no namespace
- **WHEN** the contract is resolved
- **THEN** the cluster namespace is `default`

#### Scenario: Brought cluster with no recorded namespace

- **GIVEN** a `kubeconfig` workspace whose `init` named no namespace
- **WHEN** the contract is resolved
- **THEN** the cluster namespace is `easy-db-lab`

#### Scenario: A recorded namespace wins

- **GIVEN** a workspace in any environment whose `init` recorded `--namespace lab-a`
- **WHEN** the contract is resolved
- **THEN** the cluster namespace is `lab-a`

#### Scenario: init inside the REPL changes the namespace

- **GIVEN** a REPL session in an `ec2-k3s` workspace with no recorded namespace
- **WHEN** the user runs `init --namespace lab-a` and then a command that applies a Kubernetes object
- **THEN** that command applies the object in `lab-a`

### Requirement: Everything easy-db-lab installs goes into the cluster namespace

Every namespaced object the CLI applies (the observability stack, Grafana, the collectors, the sidecar, stress jobs, the metrics registry ConfigMaps, `cluster-config`) SHALL be created in the cluster namespace. Every reference to the namespace inside an object (a RoleBinding or ClusterRoleBinding subject, a scrape job's namespace list, a Service DNS name in ConfigMap data) SHALL name the cluster namespace. No K8s read or write SHALL fall back to a namespace that was not resolved from the cluster contract. The `cluster-config` ConfigMap SHALL hold the cluster namespace under the key `namespace`, next to the `tenant` key.

#### Scenario: Stack rendered for a non-default namespace

- **GIVEN** a cluster whose namespace is `edl-ns`
- **WHEN** every observability stage is rendered
- **THEN** every namespaced object has namespace `edl-ns`
- **AND** every RoleBinding and ClusterRoleBinding subject names namespace `edl-ns`
- **AND** no rendered ConfigMap data contains `.default.svc`

#### Scenario: cluster-config carries the namespace

- **GIVEN** a cluster whose namespace is `edl-ns`
- **WHEN** `up` deploys the stack
- **THEN** the `cluster-config` ConfigMap in `edl-ns` holds `namespace: edl-ns`

### Requirement: In-cluster callers use Service DNS names in the cluster namespace

Every in-cluster address of an easy-db-lab Service (the observability backends, the OTel collector, and a kit's own Services) SHALL be the short form `<service>.<namespace>.svc` with the cluster namespace. No address SHALL name `.default.svc` literally or use the `.svc.cluster.local` suffix, because the cluster DNS domain is not assumed to be `cluster.local`.

#### Scenario: Grafana and collector addresses follow the namespace

- **GIVEN** a cluster whose namespace is `lab-a`
- **WHEN** the Grafana datasources and the collector exporters are rendered
- **THEN** every backend address has the form `<service>.lab-a.svc`

#### Scenario: Kit Service addresses follow the namespace

- **GIVEN** a cluster whose namespace is `lab-a`
- **WHEN** a kit whose template names one of its own Services (for example the `cassandra-pods` seed, a `ferrosa` peer, an `ignite3` node, a presto or trino catalog) is rendered
- **THEN** that address has the form `<service>.lab-a.svc`

### Requirement: Kits receive the cluster namespace as NAMESPACE

The CLI SHALL give every kit the cluster namespace as the template variable `NAMESPACE`: `${NAMESPACE}` in `kit.yaml`, `__NAMESPACE__` in templates and `$NAMESPACE` in the environment of every kit process (phase scripts, `shell` steps and hooks). `NAMESPACE` SHALL be a reserved name, so a kit argument cannot shadow it. A kit's `runtime.namespace` and a step's `namespace` SHALL be optional; when absent they SHALL mean the cluster namespace. A built-in kit SHALL NOT name `default` as a namespace. A kit operator that watches namespaces (Strimzi) SHALL watch the cluster namespace.

#### Scenario: kit.yaml step uses the namespace

- **GIVEN** a cluster whose namespace is `lab-a`
- **WHEN** a kit `start` runs a step that declares no `namespace`
- **THEN** the step acts in `lab-a`

#### Scenario: Kit script sees NAMESPACE

- **GIVEN** a cluster whose namespace is `lab-a`
- **WHEN** a kit phase script or `shell` step runs
- **THEN** its environment holds `NAMESPACE=lab-a`

#### Scenario: A kit argument cannot shadow NAMESPACE

- **WHEN** a kit declares an argument whose variable is `NAMESPACE`
- **THEN** loading the kit fails with an error naming the reserved variable

#### Scenario: Strimzi watches the cluster namespace

- **GIVEN** a cluster whose namespace is `lab-a`
- **WHEN** the `kafka` kit's operator values are rendered
- **THEN** `watchNamespaces` is `{lab-a}`

### Requirement: Shell tools find the namespace in the proxy env file

The CLI SHALL write the cluster namespace to the workspace proxy env file as `EDL_NAMESPACE` at `init` and `up`. The `env.sh` helpers that address a pod SHALL read it from that file. The `kubectl` wrapper SHALL NOT add `-n` or otherwise change kubectl's default namespace.

#### Scenario: env.sh helper uses the namespace

- **GIVEN** a workspace whose cluster namespace is `lab-a`
- **WHEN** the user sources `env.sh` and runs the `clickhouse-client` helper
- **THEN** it addresses the ClickHouse pod in `lab-a`

#### Scenario: kubectl wrapper keeps kubectl's own default

- **GIVEN** a workspace whose cluster namespace is `lab-a`
- **WHEN** the user runs `kubectl get pods` through the workspace wrapper
- **THEN** the wrapper passes no `-n` and kubectl uses the namespace of its current context

### Requirement: The cluster namespace is created or adopted

Before the stack is applied, `up` SHALL ensure the cluster namespace exists. When it is absent, the CLI SHALL create it with the managed-by and cluster labels. When it exists, the CLI SHALL adopt it and add the labels, because the user named it. The `default` namespace already exists and SHALL be labelled in the same way. The capability probe at `init --kubeconfig` SHALL use the same creation path.

#### Scenario: Absent namespace is created with labels

- **GIVEN** a cluster with no namespace `lab-a`
- **WHEN** `up` runs with cluster namespace `lab-a`
- **THEN** namespace `lab-a` exists and carries `app.kubernetes.io/managed-by=easy-db-lab` and `easydblab/cluster=<clusterId>`

#### Scenario: Existing namespace is adopted

- **GIVEN** a cluster that already has a namespace `lab-a` with no easy-db-lab labels
- **WHEN** `up` runs with cluster namespace `lab-a`
- **THEN** `up` proceeds, and namespace `lab-a` carries both labels and keeps its other labels

### Requirement: What counts as an easy-db-lab object

An object counts as created by easy-db-lab when it is in the cluster namespace, or it carries both `app.kubernetes.io/managed-by=easy-db-lab` and `easydblab/cluster=<clusterId>`, or it belongs to a Helm release easy-db-lab installed (its `meta.helm.sh/release-name` names that release). After `up` and a kit `start`, every new object in the Kubernetes cluster SHALL meet one of these.

#### Scenario: up and a kit start in namespace easy-db-lab

- **GIVEN** a Kubernetes cluster whose objects were listed before the run
- **WHEN** `up` deploys the stack and the `cassandra-pods` kit `start` runs with namespace `easy-db-lab`
- **THEN** every object that was not there before is in `easy-db-lab`, or carries both labels, or belongs to a Helm release easy-db-lab installed

### Requirement: Operators install into their own namespaces

A kit's operator chart SHALL install into a namespace of its own (for example `cnpg-system`, `strimzi`, `tidb-admin`, and one for the ClickHouse operator), never into `kube-system` or another namespace easy-db-lab did not create. The `namespace` step SHALL create that namespace with the managed-by and cluster labels.

#### Scenario: ClickHouse operator leaves kube-system

- **WHEN** the `clickhouse` kit installs its operator
- **THEN** the operator's objects are in the ClickHouse operator's own namespace and none is in `kube-system`

#### Scenario: Operator namespace is labelled

- **WHEN** a kit's `namespace` step creates an operator namespace
- **THEN** that namespace carries `app.kubernetes.io/managed-by=easy-db-lab` and `easydblab/cluster=<clusterId>`

### Requirement: CRDs present at init are recorded

`init --kubeconfig` SHALL list the CustomResourceDefinitions present in the cluster and record their names in the workspace as the pre-existing CRDs. On EC2 and local nothing is recorded.

#### Scenario: CRD baseline recorded

- **GIVEN** a brought cluster that already has the CRD `clusters.postgresql.cnpg.io`
- **WHEN** the user runs `init --kubeconfig`
- **THEN** the workspace records `clusters.postgresql.cnpg.io` among the pre-existing CRDs

#### Scenario: No baseline on EC2

- **WHEN** the user runs `init` for an `ec2-k3s` workspace
- **THEN** no pre-existing CRDs are recorded

### Requirement: The owned-object sweep removes only labelled objects and never data

The CLI SHALL provide an owned-object sweep that deletes the objects carrying `easydblab/cluster=<clusterId>`: namespaced kinds in the cluster namespace and cluster-scoped kinds cluster-wide, found through API discovery, in dependency order (workloads, then RBAC and Services, then ConfigMaps). It SHALL NEVER delete a PersistentVolumeClaim, a PersistentVolume, a CustomResourceDefinition, a StorageClass or the namespace, whatever their labels, and SHALL NEVER delete an object without the cluster label. Calling the sweep from `down` is not part of this requirement.

#### Scenario: Sweep removes labelled workloads and RBAC only

- **GIVEN** a cluster with a labelled Deployment, an unlabelled Deployment in the same namespace, a labelled PVC, a labelled PV, a CRD recorded as pre-existing, and a ClusterRole without the cluster label
- **WHEN** the sweep runs
- **THEN** the labelled Deployment and the labelled RBAC are gone
- **AND** the unlabelled Deployment, the PVC, the PV, the CRD and the foreign ClusterRole remain

#### Scenario: Sweep never deletes the namespace or a StorageClass

- **GIVEN** a labelled cluster namespace and a labelled `edl-local-<kit>` StorageClass
- **WHEN** the sweep runs
- **THEN** both remain

### Requirement: No literal default namespace in main code or resources

No file under `src/main` (Kotlin, kit files, the kit shell library, `env.sh`, help and dashboard resources) SHALL name the namespace `default` literally, except the single environment default the cluster contract owns. A unit test SHALL enforce this by matching namespace-shaped uses (`namespace: default`, `-n default`, `--namespace default`, `--namespace=default`, `.default.svc`, `namespaces/default/`, `{default}`, `withNamespace("default")`, `inNamespace("default")`, `NAMESPACE = "default"`, `namespace: String = "default"`). It SHALL NOT match other uses of the word `default`, such as the `default` ServiceAccount, AWS profile, tenant, ClickHouse database or storage policy, or a kit argument's `default:` key.

#### Scenario: Guard passes on the tree

- **WHEN** the guard test scans `src/main`
- **THEN** it finds no namespace-shaped `default` other than the contract's environment default

#### Scenario: Guard fails on a planted literal

- **GIVEN** a fixture file that contains `-n default`
- **WHEN** the guard test scans it
- **THEN** the test reports the file and the line

#### Scenario: Other uses of default are not flagged

- **GIVEN** a fixture file that names the `default` ServiceAccount and a kit argument with `default: 4`
- **WHEN** the guard test scans it
- **THEN** it reports nothing

### Requirement: AWS lands in default as today

On EC2 + K3s with no recorded namespace, `up` and every kit `start` SHALL put every object where they put it before this change, in `default` and in the operators' namespaces. The only differences SHALL be the added managed-by and cluster labels, the cluster-id suffix on cluster-scoped names, and the ClickHouse operator's own namespace in place of `kube-system`.

#### Scenario: up on AWS uses default

- **GIVEN** an `ec2-k3s` workspace whose `init` named no namespace
- **WHEN** the user runs `up`
- **THEN** every namespaced stack object is in `default`
- **AND** the in-cluster backend and collector addresses are `<service>.default.svc`

#### Scenario: Rendered AWS stack differs only by labels and names

- **GIVEN** an `ec2-k3s` cluster scope with namespace `default`
- **WHEN** every observability stage is rendered
- **THEN** each object's namespace is the one it had before this change, and the only differences are the two labels and the `-<clusterId>` suffix on cluster-scoped names
