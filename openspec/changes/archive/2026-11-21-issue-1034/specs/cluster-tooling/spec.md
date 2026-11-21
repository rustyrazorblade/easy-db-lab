## ADDED Requirements

### Requirement: helm and kubectl run through one cluster tooling seam
The CLI SHALL run every helm and kubectl command that `KubectlService` and `HelmService` issue through a single `ClusterTooling` seam.  Its one form today, `SshClusterTooling`, runs the tool on the control node over SSH with `KUBECONFIG=/etc/rancher/k3s/k3s.yaml`.  The services SHALL NOT take a target host; the SSH form SHALL resolve the control host from cluster state on every call.  A form that runs the tools on the developer's machine is deferred to #1163.

#### Scenario: The tools run on the control node
- **WHEN** a typed kit step that runs helm or kubectl runs
- **THEN** the command runs on the control node over SSH prefixed with `KUBECONFIG=/etc/rancher/k3s/k3s.yaml`

#### Scenario: The SSH form is the default binding
- **WHEN** the CLI starts
- **THEN** `ClusterTooling` resolves to `SshClusterTooling`

### Requirement: Every argument reaches the remote tool as one argument
The SSH form SHALL join an argument made only of shell-safe characters as it is and single-quote any other argument, so a value holding a space, `;`, `$` or `^` (an interpolated kit variable, a helm filter) reaches the tool as exactly that one argument and runs nothing else.

#### Scenario: A kit variable with shell metacharacters
- **WHEN** a step field holds `a value; touch /tmp/x ${UNSET}`
- **THEN** the remote tool receives that value as one argument
- **AND** no other command runs and `${UNSET}` is not expanded

### Requirement: Cluster tool failures carry the exit status and stderr
A helm or kubectl command that exits non-zero SHALL raise a `ClusterToolFailedException` whose message names the command (with credentials redacted), the control node it ran on, the exit status, and the tool's stderr and stdout.  When the transport reports no exit status, the message SHALL say so instead of inventing one.

#### Scenario: SSH failure reports status and stderr
- **WHEN** a command run by the SSH form exits with status 1
- **THEN** a `ClusterToolFailedException` is raised with exit status 1, the remote stderr, and the control node's alias and address

### Requirement: Cluster tool input leaves no temporary files behind
A command with input (a manifest or helm values) SHALL take it on stdin through exactly one `-` argument.  The SSH form SHALL upload it to a temporary file on the control node, run the command against that path, and remove both the remote file and its local staging copy whether the command succeeds or fails.  A failed cleanup SHALL NOT replace the command's own result.

#### Scenario: SSH input file removed after a failure
- **WHEN** the SSH form runs a command with input and the command fails
- **THEN** the uploaded input file is removed from the control node
- **AND** the local staging copy is removed
