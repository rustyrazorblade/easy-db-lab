## ADDED Requirements

### Requirement: init records the cluster namespace

`init` MUST accept `--namespace NAME` in every environment (EC2 + K3s, `--local`, `--kubeconfig`) and record it in the workspace. When `--namespace` is not given, `init` MUST record nothing, so the cluster contract supplies the environment default. `init` MUST refuse, before writing anything, a name that is not a DNS-1123 label of at most 63 characters (lower-case letters, digits and `-`, starting and ending with a letter or digit), and the names `kube-system`, `kube-public` and `kube-node-lease`. The help text MUST say the default is `default` on AWS and local and `easy-db-lab` on a cluster the user brings.

#### Scenario: Namespace recorded

- **WHEN** the user runs `init --namespace lab-a`
- **THEN** the workspace records the namespace `lab-a`

#### Scenario: No namespace given

- **WHEN** the user runs `init` with no `--namespace`
- **THEN** the workspace records no namespace and the cluster namespace is the environment default

#### Scenario: Invalid name refused

- **WHEN** the user runs `init --namespace Lab_A`
- **THEN** `init` fails with a message that names `Lab_A` and the DNS-1123 rule, and writes nothing

#### Scenario: Too long a name refused

- **WHEN** the user runs `init --namespace` with a 64-character name
- **THEN** `init` fails and writes nothing

#### Scenario: System namespace refused

- **WHEN** the user runs `init --namespace kube-system`
- **THEN** `init` fails with a message that names `kube-system` as reserved, and writes nothing

#### Scenario: Namespace accepted in a local workspace

- **WHEN** the user runs `init --local --namespace lab-a`
- **THEN** the workspace records the namespace `lab-a`
