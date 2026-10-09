## ADDED Requirements

### Requirement: Kit shell steps run with the workspace tool wrappers and an absolute kubeconfig

Every kit `type: shell` step, in `install:` and in lifecycle phases, MUST run with:

- `<workspace>/bin` first on `PATH`, ahead of the `PATH` it inherited, with the tool wrappers written before the step starts;
- `KUBECONFIG` set to the absolute path of the workspace kubeconfig, replacing any relative value from the cluster state variables.

The step MUST fail before it starts if the workspace kubeconfig does not exist. The workspace kubeconfig MUST be used as it is: no temporary or proxied copy of it is made. The `__KUBECONFIG__` template variable keeps its relative value.

#### Scenario: kubectl in an install shell step

- **GIVEN** a running cluster and a kit whose install shell step runs `kubectl get ns`
- **WHEN** the user runs `easy-db-lab kit install` for that kit
- **THEN** the command succeeds and `kit install` exits 0

#### Scenario: KUBECONFIG is absolute in install and phase steps

- **WHEN** any kit shell step runs, in install or in a phase
- **THEN** its `KUBECONFIG` is an absolute path to a file that exists

#### Scenario: Wrappers come first on PATH

- **WHEN** any kit shell step runs
- **THEN** the first entry of its `PATH` is `<workspace>/bin`
- **AND** the rest is the `PATH` the CLI inherited

#### Scenario: Missing kubeconfig fails the step

- **GIVEN** a workspace with no `kubeconfig` file
- **WHEN** a kit shell step is about to run
- **THEN** the step fails with a message naming the missing file and does not start

#### Scenario: No temporary kubeconfig

- **GIVEN** a SOCKS cluster with a recorded port
- **WHEN** a kit phase or install shell step runs
- **THEN** no temporary kubeconfig copy is created
- **AND** the workspace kubeconfig is unchanged
