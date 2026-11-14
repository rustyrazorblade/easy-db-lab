## ADDED Requirements

### Requirement: Kubernetes API reached through a recorded kubeconfig and context

The system SHALL reach the Kubernetes API only through a kubeconfig file path and a context resolved once per command from the workspace. A K8s service and `K8sClientProvider` SHALL NOT take a `ClusterHost` to reach the cluster; the control node's identity plays no part in creating the client.

#### Scenario: Client created without a control host

- **WHEN** any K8s service creates a Kubernetes client
- **THEN** it obtains the client from `K8sClientProvider.createClient()`, which takes no `ClusterHost`, and the client connects to the API server named by the recorded context in the workspace kubeconfig

#### Scenario: Missing kubeconfig fails fast

- **WHEN** a K8s service creates a client and the workspace has no kubeconfig file
- **THEN** the command fails with an error naming the expected kubeconfig path and telling the user to run the command from the cluster's workspace

### Requirement: Kubeconfig loaded by path with an explicit context

The system SHALL load the kubeconfig from its file path with an explicit context, preserving the file location so that relative certificate paths and exec auth plugin paths resolve against the kubeconfig file's directory. The system SHALL NOT load the kubeconfig through any mechanism that reads the JVM-global `kubeconfig` system property or the `KUBECONFIG` environment variable.

#### Scenario: Relative certificate path resolved against the kubeconfig

- **WHEN** the kubeconfig names a client certificate, client key or certificate authority by a relative path
- **THEN** the client loads that file relative to the kubeconfig file's directory, not the process working directory

#### Scenario: Exec auth plugin honoured

- **WHEN** the recorded context's user authenticates through an exec auth plugin
- **THEN** the client invokes that plugin to obtain credentials

#### Scenario: User's KUBECONFIG does not redirect the client

- **WHEN** the user's shell sets `KUBECONFIG` to a different file
- **THEN** the client still loads the workspace kubeconfig and ignores the environment variable

#### Scenario: Recorded context absent from the kubeconfig

- **WHEN** the recorded context does not exist in the kubeconfig
- **THEN** the command fails fast with an error naming the missing context and the kubeconfig path

### Requirement: Context recorded in the workspace and pinned

The system SHALL record the Kubernetes context in the workspace cluster state (`ClusterState.kubeContext`) and SHALL use that recorded context for every later command, regardless of the kubeconfig's `current-context` or the user's current kubectl context. When the workspace records no context, the system SHALL fall back to the kubeconfig's `current-context`.

#### Scenario: Recorded context differs from current-context

- **WHEN** the kubeconfig names a `current-context` other than the context the workspace records
- **THEN** every command uses the recorded context

#### Scenario: Changing the user's kubectl context has no effect

- **WHEN** the user switches their kubectl current context after the workspace has recorded one
- **THEN** later easy-db-lab commands still target the recorded context

#### Scenario: Provisioning records the context

- **WHEN** `up` writes the workspace kubeconfig for a provisioned cluster
- **THEN** it records that kubeconfig's `current-context` as `ClusterState.kubeContext`

#### Scenario: No recorded context falls back to current-context

- **WHEN** the workspace cluster state records no context
- **THEN** the client uses the kubeconfig's `current-context`

### Requirement: Per-client SOCKS tunnel preserved

The system SHALL route Kubernetes API traffic through the SOCKS tunnel only by setting the per-client `httpsProxy` on the client's own configuration, and only in `KubernetesClientFactory`. Loading the kubeconfig by path SHALL NOT change this, and the system SHALL NOT set, clear, or read-and-restore the `socksProxyHost` or `socksProxyPort` JVM system properties.

#### Scenario: Tunnel active

- **WHEN** the SOCKS tunnel's port is published under the private proxy-port property and a client is created
- **THEN** that client's `httpsProxy` points at `socks5://127.0.0.1:<port>` and no JVM-global proxy property is set

#### Scenario: Tunnel inactive

- **WHEN** no proxy port is published (Tailscale active)
- **THEN** the client connects directly with no proxy configured

### Requirement: No control host returns to K8s services

The system SHALL enforce, with an ArchUnit rule over main classes, that no method or constructor in a `services` class that depends on the Kubernetes client (`KubernetesClient` or `K8sClientProvider`) takes a `ClusterHost` parameter. Classes that still need the host for SSH or host-IP work outside this change SHALL be listed by name on the rule's allow-list; the allow-list SHALL only shrink.

#### Scenario: A ClusterHost parameter is reintroduced

- **WHEN** a non-allow-listed `services` class that depends on the Kubernetes client gains a method or constructor taking `ClusterHost`
- **THEN** the architecture test fails and names that class and member

#### Scenario: Allow-listed class keeps its host

- **WHEN** an allow-listed class (for example `TempoTailFlush` or `GrafanaClient`) takes a `ClusterHost` for SSH or host-IP work
- **THEN** the architecture test passes

#### Scenario: Stale allow-list entry

- **WHEN** an allow-listed class no longer takes a `ClusterHost` or no longer depends on the Kubernetes client
- **THEN** the architecture test fails, telling the author to remove the entry

### Requirement: AWS behaviour unchanged

On AWS, `up`, `down`, `status` and a kit `start` SHALL behave as they did before this change: the same workspace kubeconfig, the same API server, and the same per-client SOCKS routing.

#### Scenario: AWS lifecycle and kit start

- **WHEN** `up`, `down`, `status` and a kit `start` run on an AWS cluster
- **THEN** they reach the same API server through the same tunnel and produce the same results as before the change
