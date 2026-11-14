## ADDED Requirements

### Requirement: Pod-security and node-access capabilities

The capability set SHALL include `PRIVILEGED` (privileged pods are admitted), `HOST_NETWORK` (pods on the host network are admitted), `HOST_PATH` (hostPath volumes are admitted) and `NODE_SSH` (easy-db-lab can run commands on the nodes over SSH). AWS SHALL declare all four. The local Docker environment SHALL declare `PRIVILEGED`, `HOST_NETWORK` and `HOST_PATH`, and SHALL NOT declare `NODE_SSH`. Code SHALL ask for a capability and SHALL NOT test an environment name.

#### Scenario: AWS declares the four capabilities

- **GIVEN** a cluster on AWS
- **WHEN** its capabilities are decided
- **THEN** they include `PRIVILEGED`, `HOST_NETWORK`, `HOST_PATH` and `NODE_SSH`

#### Scenario: Local Docker has no node SSH

- **GIVEN** the local Docker environment
- **WHEN** its capabilities are decided
- **THEN** they include `PRIVILEGED`, `HOST_NETWORK` and `HOST_PATH`
- **AND** they do not include `NODE_SSH`

### Requirement: The effective capability set is recorded in the workspace

`init` SHALL record the effective capability set in cluster state. Every later command SHALL take the capabilities from that record. A cluster state with no recorded set SHALL be read as AWS. Only the effective set SHALL be recorded, not where each value came from.

#### Scenario: Recorded set is used

- **GIVEN** a cluster state whose recorded set lacks `PRIVILEGED`
- **WHEN** a command decides the capabilities
- **THEN** `PRIVILEGED` is absent and every other recorded capability is present

#### Scenario: No recorded set reads as AWS

- **GIVEN** a cluster state written before capabilities were recorded
- **WHEN** a command decides the capabilities
- **THEN** every capability is present

### Requirement: init overrides each capability

`init` SHALL accept a repeatable `--capability <name>=<bool>` option, where `<name>` is any capability's serial name and `<bool>` is `true` or `false`. The recorded capability SHALL follow the override. An unknown name, a value other than `true` or `false`, or one name given with two different values SHALL fail `init` before anything is recorded, listing the valid names.

#### Scenario: Override removes a capability

- **WHEN** `init --capability privileged=false` runs
- **THEN** the recorded set lacks `PRIVILEGED` and keeps every other declared capability

#### Scenario: Override adds a capability the probe found absent

- **GIVEN** a probe result in which `HOST_PATH` is absent
- **WHEN** the override `host-path=true` is applied
- **THEN** the recorded set includes `HOST_PATH`

#### Scenario: Unknown capability name

- **WHEN** `init --capability gpu=true` runs
- **THEN** `init` fails, naming `gpu` and listing the valid capability names
- **AND** no cluster state is written

#### Scenario: Conflicting overrides

- **WHEN** `init --capability privileged=true --capability privileged=false` runs
- **THEN** `init` fails, naming `privileged`

### Requirement: init reports what an absent capability disables

For every absent capability, `init` SHALL print the capability, the reason it is absent, and every component that needs it. The component list SHALL come from the same stage declarations that `up` gates on. A capability that no component needs SHALL be printed as such.

#### Scenario: Baseline namespace disables privileged components

- **GIVEN** the recorded set lacks `PRIVILEGED`, `HOST_NETWORK` and `HOST_PATH`
- **WHEN** `init` finishes
- **THEN** the output names each of the three, and under each, every stage that declares it as required

#### Scenario: Component list matches up's gating

- **GIVEN** a stage that declares it requires `PRIVILEGED`
- **WHEN** `init` runs with `PRIVILEGED` absent and `up` then runs
- **THEN** `init` names that stage
- **AND** `up` reports it with `StageSkipped`, naming `PRIVILEGED`

### Requirement: Admission capabilities are probed with server-side dry-run Pods

easy-db-lab SHALL decide `PRIVILEGED`, `HOST_NETWORK` and `HOST_PATH` for a namespace by submitting Pods to the API server with `dryRun=All`. A baseline Pod that meets Pod Security "restricted" SHALL be submitted first, then one Pod per capability, each differing from the baseline only in the field it tests. The probe SHALL create the namespace when it does not exist. A capability whose Pod is rejected SHALL be absent, with a note carrying the API server's message. A rejection because a webhook does not support dry run SHALL make the capability absent, with a note naming the webhook. A rejected baseline Pod or any other error SHALL fail the probe, naming the namespace and the error. `NODE_SSH` SHALL always be absent in a probed set. The probe SHALL NOT run on AWS or local Docker, and SHALL NOT dry-run a workload controller in place of a Pod.

#### Scenario: Namespace enforcing baseline

- **GIVEN** a namespace labelled `pod-security.kubernetes.io/enforce=baseline`
- **WHEN** the probe runs against it
- **THEN** `PRIVILEGED`, `HOST_NETWORK` and `HOST_PATH` are absent, each with a note naming Pod Security
- **AND** no Pod exists in the namespace afterwards

#### Scenario: Unrestricted namespace

- **GIVEN** a namespace with no Pod Security labels on a cluster with no admission policy
- **WHEN** the probe runs against it
- **THEN** `PRIVILEGED`, `HOST_NETWORK` and `HOST_PATH` are present
- **AND** `NODE_SSH` is absent

#### Scenario: Missing namespace

- **GIVEN** the target namespace does not exist
- **WHEN** the probe runs
- **THEN** the namespace is created and the probe returns a result instead of a not-found error

#### Scenario: Webhook refuses dry run

- **GIVEN** a validating webhook with side effects that matches privileged Pods
- **WHEN** the probe runs
- **THEN** `PRIVILEGED` is absent with a note naming that webhook

#### Scenario: Baseline Pod rejected

- **GIVEN** credentials or a policy that admit no Pod in the namespace
- **WHEN** the probe runs
- **THEN** the probe fails, naming the namespace and the API server's message, and reports no capability set

### Requirement: Capabilities are published to the cluster and to kits

The `cluster-config` ConfigMap SHALL carry a `capabilities` key: the serial names of the present capabilities in a fixed order, comma-joined. Every kit process SHALL get `EDL_NODE_SSH=true` when `NODE_SSH` is present and `EDL_NODE_SSH=false` otherwise, independent of whether the workspace has an `sshConfig`.

#### Scenario: cluster-config lists present capabilities

- **GIVEN** a recorded set lacking `PRIVILEGED`
- **WHEN** `cluster-config` is built
- **THEN** its `capabilities` value lists every present capability's serial name and not `privileged`

#### Scenario: Unchanged set keeps the config hash

- **GIVEN** the same recorded set built twice
- **WHEN** `cluster-config` is built each time
- **THEN** the two `capabilities` values are identical

#### Scenario: Kit variable follows NODE_SSH

- **GIVEN** a workspace that has an `sshConfig` and a recorded set lacking `NODE_SSH`
- **WHEN** a kit process starts
- **THEN** it sees `EDL_NODE_SSH=false`

#### Scenario: AWS kit processes keep node SSH

- **GIVEN** an AWS cluster with no recorded set
- **WHEN** a kit process starts
- **THEN** it sees `EDL_NODE_SSH=true`

#### Scenario: Unreadable state fails the kit launch

- **GIVEN** a workspace whose `state.json` exists but cannot be read or parsed
- **WHEN** a kit process is about to start
- **THEN** the launch fails with a message naming the state file, and no kit process starts

### Requirement: status lists the capabilities

`status` SHALL list every capability: present, or absent with its reason.

#### Scenario: status shows present and absent

- **GIVEN** a recorded set lacking `HOST_PATH`
- **WHEN** the user runs `status`
- **THEN** the output lists every capability, with `HOST_PATH` marked absent and its reason
