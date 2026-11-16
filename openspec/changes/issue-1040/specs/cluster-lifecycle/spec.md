## ADDED Requirements

### Requirement: init --local creates a local workspace with no AWS

`init --local` MUST create a workspace whose environment is `k3s-docker`, with no AWS API call and no AWS profile load or prompt.

`--data-dir PATH` sets the data directory:

- It defaults to `observability-data` under the workspace.
- A given relative path MUST be resolved against the current directory.
- `state.json` MUST record the absolute path.
- `--data-dir` without `--local` MUST be a usage error.

`init --local` MUST record:

- `tailscaleActive: false`;
- the local Docker capability set, with any capability overrides applied;
- Flannel as the CNI;
- the host's architecture for every role;
- `exposure: PortForward`, so the CLI reaches cluster Services by a port-forward on loopback, on a free port chosen on each use. CQL is a single-endpoint session.

It MUST write `EDL_NETWORK_MODE=direct` to the proxy env file.

AWS-only `init` options given explicitly with `--local` MUST make `init` fail and name each option, writing nothing. These are `--instance`, `--db.instance-type`, `--azs`, the `--ebs.*`, `--spark.*` and `--opensearch.*` options, `--vpc`, `--cidr`, `--cni`, `--ami` and `--open`.

`clean` MUST NOT remove the data directory. `init` MUST NOT refuse a directory because the data directory already exists. Nothing in easy-db-lab deletes the data directory.

#### Scenario: init --local with no AWS configuration

- **GIVEN** a machine with no `~/.aws` configuration and no easy-db-lab profile
- **WHEN** the user runs `init --local` in an empty directory
- **THEN** it succeeds, makes no AWS API call and shows no profile prompt
- **AND** `state.json` records the `k3s-docker` environment

#### Scenario: Default data directory is under the workspace

- **WHEN** the user runs `init --local` with no `--data-dir`
- **THEN** `state.json` records `<workspace>/observability-data` as an absolute path

#### Scenario: A given data directory is recorded

- **WHEN** the user runs `init --local --data-dir ../shared-data`
- **THEN** `state.json` records the absolute path that `../shared-data` names from the current directory

#### Scenario: Local init writes direct mode to the env file

- **WHEN** `init --local` completes
- **THEN** the proxy env file holds `EDL_NETWORK_MODE=direct`

#### Scenario: AWS-only options are refused with --local

- **WHEN** the user runs `init --local --instance r5.xlarge --azs a,b`
- **THEN** `init` fails with a message that names `--instance` and `--azs`
- **AND** no file is written

#### Scenario: --data-dir without --local

- **WHEN** the user runs `init --data-dir /tmp/x`
- **THEN** `init` fails with a usage error that says `--data-dir` requires `--local`

#### Scenario: AWS init is unchanged

- **GIVEN** a configured AWS profile
- **WHEN** the user runs `init` without `--local`
- **THEN** `state.json` records the `ec2-k3s` environment and every other recorded value is as today
- **AND** the env file holds no `EDL_NETWORK_MODE`

### Requirement: up and down need a provisioner for the environment

`up` and `down` MUST dispatch on the recorded environment. For an environment with no provisioner in this easy-db-lab, they MUST fail before any side effect and before any profile check, with an `Environment.ProvisionerUnavailable` event that names the command and the environment. On `ec2-k3s`, `up` and `down` MUST behave as today.

#### Scenario: up in a local workspace before the local provisioner exists

- **GIVEN** a `k3s-docker` workspace and an easy-db-lab with no K3s-in-Docker provisioner
- **WHEN** the user runs `up`
- **THEN** it fails with a message that names `up` and `K3s in Docker`
- **AND** no profile prompt appears and no AWS call is made

#### Scenario: up on EC2 + K3s is unchanged

- **GIVEN** an `ec2-k3s` workspace
- **WHEN** the user runs `up`
- **THEN** provisioning, the account bucket preparation, the tunnel and the observability stack behave as today
