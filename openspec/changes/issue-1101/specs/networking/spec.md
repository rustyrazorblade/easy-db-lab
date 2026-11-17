## ADDED Requirements

### Requirement: K3s runs on an explicit cluster CIDR

The K3s server SHALL start with `--cluster-cidr=Constants.K3s.CLUSTER_CIDR` (`10.42.0.0/16`), on Flannel and on Cilium alike, so the pod network easy-db-lab builds is a known value that database kits list as a client network beside the VPC CIDR.

#### Scenario: Flannel server
- **WHEN** the K3s server starts on a Flannel cluster
- **THEN** `start-k3s-server.sh` receives `--cluster-cidr=10.42.0.0/16`

#### Scenario: Cilium server
- **WHEN** the K3s server starts on a Cilium cluster
- **THEN** `start-k3s-server.sh` receives `--cluster-cidr=10.42.0.0/16 --flannel-backend=none`

### Requirement: init records extra client networks

`init --client-network <cidr>` SHALL be repeatable and SHALL take an IPv4 CIDR or address (a bare address means `/32`), parsed by the strict IPv4 CIDR parser before anything is cleaned or written. On the nodes easy-db-lab builds the values SHALL follow the VPC CIDR and the cluster CIDR in the client networks, each network once.

#### Scenario: Extra networks follow the environment's
- **WHEN** `init --cidr 172.16.0.0/16 --client-network 192.168.0.0/16 --client-network 203.0.113.7` runs
- **THEN** the client networks are `172.16.0.0/16`, `10.42.0.0/16`, `192.168.0.0/16` and `203.0.113.7/32`

#### Scenario: A malformed network is refused
- **WHEN** `init --client-network 10.0.0.1/8` runs
- **THEN** it fails naming the value and the network form, and writes nothing
