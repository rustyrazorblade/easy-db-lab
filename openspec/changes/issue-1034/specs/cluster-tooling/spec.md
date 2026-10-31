## ADDED Requirements

### Requirement: helm and kubectl run through one cluster tooling seam
The CLI SHALL run every helm and kubectl command that `KubectlService` and `HelmService` issue through a single `ClusterTooling` seam with two forms: an SSH form that runs the tool on the control node, and a local form that runs the tool as a local process.  The services SHALL NOT take a target host; the SSH form SHALL resolve the control host from cluster state.

#### Scenario: Every typed step runs through the local form with no SSH
- **WHEN** a kit using helm-repo, helm, helm-uninstall, namespace, manifest, manifest-url, kustomize, wait, delete and delete-by-selector steps is installed and started with the local form bound
- **THEN** every step succeeds against the cluster in the workspace kubeconfig
- **AND** no SSH connection is attempted

#### Scenario: AWS keeps running the tools on the control node
- **WHEN** the same steps run with the SSH form bound
- **THEN** each command runs on the control node over SSH prefixed with `KUBECONFIG=/etc/rancher/k3s/k3s.yaml`
- **AND** the command strings are the same as before this change

#### Scenario: The SSH form is the default binding
- **WHEN** the CLI starts with no override
- **THEN** `ClusterTooling` resolves to the SSH form

### Requirement: Cluster tool failures carry the exit status and stderr
A helm or kubectl command that exits non-zero SHALL raise a `ClusterToolFailedException` naming the tool, its arguments, the exit status, and the tool's stderr, in both forms.

#### Scenario: Local failure reports status and stderr
- **WHEN** the local form runs `kubectl get nosuchkind`
- **THEN** a `ClusterToolFailedException` is raised with exit status 1
- **AND** its stderr names the unknown resource type

#### Scenario: SSH failure reports status and stderr
- **WHEN** a command run by the SSH form exits with status 2
- **THEN** a `ClusterToolFailedException` is raised with exit status 2 and the remote stderr

### Requirement: Cluster tool input leaves no temporary files behind
A command with input (a manifest or helm values) SHALL leave no temporary input file behind.  The local form SHALL pass input on stdin and write no file.  The SSH form SHALL remove its uploaded input file whether the command succeeds or fails.

#### Scenario: Local apply writes no temporary file
- **WHEN** a manifest is applied through the local form
- **THEN** no `easydblab-*` file exists in the temporary directory afterwards

#### Scenario: SSH input file removed after a failure
- **WHEN** the SSH form runs a command with input and the command fails
- **THEN** the uploaded input file is removed from the control node

### Requirement: The local form runs in the kit process environment
The local form SHALL run helm and kubectl with the same environment kit shell steps get: `<workspace>/bin` first on `PATH`, the absolute workspace `KUBECONFIG`, and helm's config, cache and data directories under the workspace.  It SHALL pass arguments as a list, never through a shell, and SHALL never set `socksProxyHost` or `socksProxyPort`.

#### Scenario: Local helm uses the workspace kubeconfig and helm home
- **WHEN** the local form runs `helm repo add`
- **THEN** the repository is recorded under the workspace, not in the user's home directory
- **AND** the command targets the cluster in `<workspace>/kubeconfig`

#### Scenario: SOCKS properties stay unset
- **WHEN** the local form runs any command
- **THEN** the `socksProxyHost` and `socksProxyPort` system properties are unset before and after
