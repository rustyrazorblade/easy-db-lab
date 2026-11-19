## ADDED Requirements

### Requirement: init records extra client networks

`init --client-network <cidr>` SHALL be repeatable and SHALL take an IPv4 CIDR or address (a bare address means `/32`), parsed by the strict IPv4 CIDR parser before anything is cleaned or written. On the nodes easy-db-lab builds the values SHALL follow the VPC CIDR and the cluster CIDR in the client networks, each network once.

#### Scenario: Extra networks follow the environment's
- **WHEN** `init --cidr 172.16.0.0/16 --client-network 192.168.0.0/16 --client-network 203.0.113.7` runs
- **THEN** the client networks are `172.16.0.0/16`, `10.224.0.0/12`, `192.168.0.0/16` and `203.0.113.7/32`

#### Scenario: A malformed network is refused
- **WHEN** `init --client-network 10.0.0.1/8` runs
- **THEN** it fails naming the value and the network form, and writes nothing

### Requirement: init reports the client networks the kits trust

After `init` records the workspace, it SHALL emit `Setup.ClientNetworksRecorded` listing every client network the passwordless database kits will accept, in order. For each `--client-network` with a prefix from `/1` to `/7` (`Constants.ClientNetworks.WIDE_PREFIX_WARNING_MAX`) it SHALL first emit `Setup.WideClientNetwork`, naming the network and how many addresses it covers, and still record it. A `/0` stays refused.

#### Scenario: The recorded networks are listed
- **WHEN** `init --cidr 172.16.0.0/16 --client-network 192.168.0.0/16` runs
- **THEN** it reports `172.16.0.0/16, 10.224.0.0/12, 192.168.0.0/16` as the networks the database kits accept

#### Scenario: A very wide network is warned about
- **WHEN** `init --client-network 10.0.0.0/7` runs
- **THEN** it warns that `10.0.0.0/7` gives 33554432 addresses passwordless access, and records it
