## ADDED Requirements

### Requirement: The OpenSearch domain name is derived from the cluster name

The OpenSearch domain name SHALL be derived from the cluster name by one shared rule, used by both `opensearch start` and cluster provisioning.  The rule SHALL lowercase the name, SHALL turn every character other than `a-z`, `0-9` and `-` into `-` (so `_` becomes `-`), SHALL trim the base so the whole name fits in 28 characters, and SHALL then append `-os`.  The `-os` suffix SHALL never be cut.  The base SHALL NOT end in `-`.

#### Scenario: An underscore becomes a hyphen
- **WHEN** a cluster named `unthrottled_cursor` provisions an OpenSearch domain
- **THEN** the domain name is `unthrottled-cursor-os`

#### Scenario: A long name keeps its suffix
- **WHEN** a cluster's name is 40 characters long
- **THEN** the domain name is at most 28 characters and ends in `-os`

#### Scenario: Both paths agree
- **WHEN** `opensearch start` and cluster provisioning derive a domain name for the same cluster name
- **THEN** they derive the same name
