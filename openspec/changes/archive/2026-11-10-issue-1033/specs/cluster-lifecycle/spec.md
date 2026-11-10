## ADDED Requirements

### Requirement: Workspace files resolve against the workspace

Every workspace-owned file the CLI opens SHALL resolve against the workspace directory, never against the process's current directory. This covers `state.json`, `kubeconfig`, `environment.sh`, `setup_instance.sh`, `cassandra-jmx-rules.yaml`, `axonops-workbench.json`, the local Cassandra version conf directories, `profiles/` and `logs/`. The workspace directory SHALL be absolute. A path the user types as an argument SHALL resolve against the process's current directory, as before. A command SHALL create no file in the process's current directory unless that directory is the workspace.

#### Scenario: State is read and written in the workspace

- **GIVEN** a workspace W and a process whose current directory is a different directory D
- **WHEN** a command saves and then reloads cluster state
- **THEN** it writes and reads `W/state.json`
- **AND** nothing is created in D

#### Scenario: Kubernetes client uses the workspace kubeconfig

- **GIVEN** a workspace W holding `kubeconfig`, and a process whose current directory is D
- **WHEN** a command builds a Kubernetes client
- **THEN** the client is configured from `W/kubeconfig`

#### Scenario: Other workspace files resolve against the workspace

- **GIVEN** a workspace W and a process whose current directory is D
- **WHEN** `setup-instance`, `cassandra update-config`, `cassandra start`, the profiling fetch, or Spark log download opens or writes its workspace file
- **THEN** each path is under W

#### Scenario: Today's normal case is unchanged

- **GIVEN** the process's current directory is the workspace
- **WHEN** any command runs
- **THEN** it reads and writes the same files as today

#### Scenario: Missing state names its absolute path

- **GIVEN** a workspace W with no `state.json`
- **WHEN** a command loads cluster state
- **THEN** the error names the absolute path of `W/state.json` and tells the user to run the command from the cluster's workspace or run `init` there

#### Scenario: Uploading authorized keys leaves no file behind

- **WHEN** the user runs `upload-keys`
- **THEN** no `authorized_keys_extra` file remains in the workspace or the current directory

#### Scenario: A typed path resolves where it was typed

- **GIVEN** a process whose current directory is D
- **WHEN** the user runs `upload-keys keys` with a relative directory
- **THEN** it reads `D/keys`
