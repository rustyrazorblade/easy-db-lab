## MODIFIED Requirements

### Requirement: Published tarball is consumable by the lab install path

The published tarball SHALL extract to a single top-level `*cassandra*` directory so that its release URL installs with `cassandra install <name> --url <tarball-url> --java <N>`.

#### Scenario: Tarball installs as a valid Cassandra
- **WHEN** the published tarball URL is installed with `cassandra install <name> --url <tarball-url> --java <N>`
- **THEN** it unpacks into a single Cassandra directory and runs as a valid Cassandra install
