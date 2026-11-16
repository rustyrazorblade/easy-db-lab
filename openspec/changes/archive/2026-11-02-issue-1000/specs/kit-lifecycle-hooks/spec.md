## ADDED Requirements

### Requirement: Hook scripts run with the workspace tool wrappers and an absolute kubeconfig

Every kit hook script MUST run with `<workspace>/bin` first on `PATH`, with the tool wrappers written before it starts, and with `KUBECONFIG` set to the absolute path of the workspace kubeconfig. The hook MUST fail before it starts if the workspace kubeconfig does not exist.

#### Scenario: Hook gets the wrappers and an absolute kubeconfig

- **WHEN** a kit hook script runs after another kit starts or stops
- **THEN** the first entry of its `PATH` is `<workspace>/bin`
- **AND** its `KUBECONFIG` is an absolute path to a file that exists

#### Scenario: Hook on a SOCKS cluster

- **GIVEN** a SOCKS cluster with a recorded port
- **WHEN** a hook script runs `kubectl`
- **THEN** the call goes through the wrapper and through the tunnel
