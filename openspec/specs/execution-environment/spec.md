# execution-environment Specification

## Purpose
TBD - created by archiving change issue-1040. Update Purpose after archive.
## Requirements
### Requirement: The workspace records its execution environment

`state.json` MUST record the execution environment as `environment`, a typed record with a `type` discriminator. The record MUST name only the provisioner and its own parameters:

- `ec2-k3s`: EC2 nodes running K3s.
- `k3s-docker`, with `dataDir`, an absolute path: K3s in Docker on this machine.
- `kubeconfig`, with `source`: a cluster the user brings.

The record MUST NOT hold capability data. A state with no `environment` MUST read as `ec2-k3s`. `init` is the only writer of `environment`.

When `state.json` records a `type` this easy-db-lab does not know, every command that loads the state MUST fail with an error that names the `state.json` path, the recorded value and the known values. It MUST NOT show a stack trace.

#### Scenario: Each environment round-trips through state.json

- **WHEN** a state with each of the three environments is saved and loaded
- **THEN** the loaded environment equals the saved one, including `dataDir` and `source`

#### Scenario: A state with no environment reads as EC2 + K3s

- **GIVEN** a `state.json` with no `environment` field
- **WHEN** it is loaded
- **THEN** the environment is `ec2-k3s`

#### Scenario: An unknown environment fails clearly

- **GIVEN** a `state.json` whose `environment.type` is `"moon-base"`
- **WHEN** any command loads the state
- **THEN** the command fails with a message that names the file, `moon-base`, and `ec2-k3s, k3s-docker, kubeconfig`
- **AND** no stack trace is printed

### Requirement: Consumers read capability data resolved from the environment

One pure function, `ClusterContract.of(state)`, MUST resolve from the recorded environment and the recorded state:

- the cluster tooling form;
- the node access form;
- the observability store kind;
- the network mode;
- the node capabilities;
- the observability hosting and placement;
- the namespace;
- the datacenter name.

It MUST be the only place in main code that branches on the environment's type. Every consumer (the `ClusterTooling` binding, `Placement`, `ObservabilityHosting`, `NodeCapabilities`, the observability stack and OTel sync services, `status` and the server status cache) MUST read the contract. None of them may test an environment name. The contract MUST be resolved from the current state on each use, so an `init` inside the REPL or the server takes effect on the next command.

`NodeCapabilities.from(state)` MUST return the recorded capability set when one is recorded, and otherwise the set the environment declares.

#### Scenario: EC2 + K3s resolves to today's behaviour

- **GIVEN** a state whose environment is `ec2-k3s`
- **WHEN** the contract is resolved
- **THEN** tooling runs over SSH on the control node, node access is SSH, storage is the object store, the network mode is tunnel, placement is the control node, and the declared capabilities are the AWS set

#### Scenario: K3s in Docker resolves to local forms

- **GIVEN** a state whose environment is `k3s-docker`
- **WHEN** the contract is resolved
- **THEN** tooling runs locally through the workspace wrappers, node access is container exec, storage is a volume, the network mode is direct, and the declared capabilities are the local Docker set

#### Scenario: A cluster the user brings resolves to portable forms

- **GIVEN** a state whose environment is `kubeconfig`
- **WHEN** the contract is resolved
- **THEN** tooling runs locally, node access is none, the network mode is direct, placement is any node, and no capability is declared

#### Scenario: The cluster tooling form follows the recorded environment

- **GIVEN** a workspace whose environment is `k3s-docker`
- **WHEN** a command runs `helm` or `kubectl` through `ClusterTooling`
- **THEN** the local form runs the tool through `<workspace>/bin`, with no Koin override

#### Scenario: init inside the REPL switches the contract

- **GIVEN** a REPL session in an `ec2-k3s` workspace
- **WHEN** the user runs `init --local` and then a command that uses `ClusterTooling`
- **THEN** that command uses the local form

### Requirement: AWS-only commands fail fast outside AWS

The environment `ec2-k3s` MUST declare the `aws-account` capability. No other environment declares it, and capability overrides MUST NOT add or remove it.

A command MAY declare required capabilities. When the target environment lacks one, the command MUST fail before any side effect, and before any AWS profile check, with an `Environment.CommandUnavailable` event. The event names:

- the command;
- the environment's display name;
- the missing capabilities;
- an alternative, when one exists.

These commands require `aws-account`:

- `cassandra use`, `cassandra install`, `build-image`, `build-base`, `build-cassandra`
- every `spark`, `opensearch`, `tailscale`, `ingress`, `aws` and `observability compactor` subcommand
- `prune-amis`, `show-iam-policies`
- `configure-aws`, `cassandra build`

These commands require `node-ssh`, because they run on the database nodes over SSH and read the AxonOps settings from the AWS profile:

- `cassandra start` (the alternative is the Cassandra kit), `configure-axonops`

Outside a workspace, the target environment is `ec2-k3s`.

#### Scenario: cassandra use in a local workspace

- **GIVEN** a workspace whose environment is `k3s-docker`
- **WHEN** the user runs `cassandra use 5.0`
- **THEN** the command fails with a message that names `cassandra use`, `K3s in Docker` and `aws-account`, and suggests the Cassandra kit
- **AND** no SSH, AWS call or profile prompt happens

#### Scenario: spark in a local workspace

- **GIVEN** a workspace whose environment is `k3s-docker`
- **WHEN** the user runs any `spark` subcommand
- **THEN** it fails fast and names the environment

#### Scenario: build-image outside a workspace

- **GIVEN** a directory with no `state.json`
- **WHEN** the user runs `build-image`
- **THEN** the capability check passes and the command behaves as today

#### Scenario: aws-account cannot be overridden

- **WHEN** the user runs `init` with a capability override naming `aws-account`
- **THEN** `init` fails with a usage error and writes nothing

### Requirement: The AWS profile is required only where there is an AWS account

A command that requires profile setup MUST require it only when its target environment declares `aws-account`. The target is the environment `init` is about to record, for `init`, and the recorded environment for every other command. In any other environment, no AWS profile is loaded or prompted for. This covers `init`, `up`, `down`, kit commands and the observability commands. The observability stack and the OTel sync MUST read the region from cluster state, not from the AWS profile.

#### Scenario: Kit commands in a local workspace need no profile

- **GIVEN** a `k3s-docker` workspace on a machine with no easy-db-lab profile and no AWS configuration
- **WHEN** the user runs a kit command
- **THEN** no profile prompt appears and no AWS profile is loaded

#### Scenario: An AWS workspace still requires the profile

- **GIVEN** an `ec2-k3s` workspace on a machine with no easy-db-lab profile
- **WHEN** the user runs `up`
- **THEN** profile setup is required, as today

