# cluster-tooling Specification

## Purpose
How the CLI runs helm and kubectl for kits and services: one `ClusterTooling` seam, today run on the control node over SSH, with failures that name the command, the node, the exit status and the tool's output.
## Requirements
### Requirement: helm and kubectl run through one cluster tooling seam
The CLI SHALL run every helm and kubectl command that `KubectlService`, `HelmService` and `CiliumInspectionService` issue, and the Cilium release probe, through a single `ClusterTooling` seam.  Only the `cilium` CLI (`cilium install` / `cilium upgrade`) runs directly on the control node.  Its one form today, `SshClusterTooling`, runs the tool on the control node over SSH with `KUBECONFIG=/etc/rancher/k3s/k3s.yaml`.  The services SHALL NOT take a target host; the SSH form SHALL resolve the control host from cluster state on every call.  A form that runs the tools on the developer's machine is deferred to #1163.

#### Scenario: The tools run on the control node
- **WHEN** a typed kit step that runs helm or kubectl runs
- **THEN** the command runs on the control node over SSH prefixed with `KUBECONFIG=/etc/rancher/k3s/k3s.yaml`

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

### Requirement: Cluster tool input travels as the command's stdin
A command with input (a manifest or helm values) SHALL mark the position of that input with exactly one input argument, which reaches the tool as `-`.  The SSH form SHALL stream the input over the same exec channel as the command's stdin, in one round trip, and SHALL write no file for it on either machine.

#### Scenario: A namespace is created in one round trip
- **WHEN** a kit's namespace step runs
- **THEN** one `kubectl apply -f -` command runs on the control node with the namespace manifest as its stdin
- **AND** no input file is left on the control node or the developer's machine

#### Scenario: A failed command with input keeps its status
- **WHEN** the SSH form runs a command with input and the command exits with status 4
- **THEN** the failure carries exit status 4 and the command's stderr

### Requirement: A per-node kit step runs on every node and names each failure
A kit step that runs a command on every node of a type (deleting or creating the kit's data directory) SHALL run on those nodes concurrently, with a bounded number at once.  The `label` step SHALL label every node of the type through one cluster client.  Every per-node step SHALL act on every node even when some fail, with one exception: when the `label` step gets no answer from the API server at all (a transport failure) or is interrupted, it SHALL stop and report every node it did not reach as not tried, with that cause, so an unreachable API costs one request timeout and not one per node.  When any node fails, the step SHALL fail with one error that gives the count of failed nodes and names the first `Constants.Scale.MAX_NAMED_IN_ERROR` of them with their errors, then counts the rest ("and N more").

#### Scenario: One node of three fails
- **WHEN** a per-node step runs on three nodes and the second fails
- **THEN** the step ran on all three
- **AND** the step fails naming the second node and its error

#### Scenario: The nodes run at the same time
- **WHEN** a step that runs a command on every node runs on three nodes
- **THEN** all three commands are running at once before any of them finishes

#### Scenario: More nodes fail than the error names
- **WHEN** a per-node step fails on five more nodes than `MAX_NAMED_IN_ERROR`
- **THEN** the error names the first `MAX_NAMED_IN_ERROR` nodes with their errors and ends "and 5 more"
- **AND** the exception still holds every failed node

#### Scenario: The API server cannot be reached while labelling
- **WHEN** a `label` step's first node fails with no answer from the API server
- **THEN** the later nodes are not tried
- **AND** each of them is reported as not tried, with the first node's failure as the cause

