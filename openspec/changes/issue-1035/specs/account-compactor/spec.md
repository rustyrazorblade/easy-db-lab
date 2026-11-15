## ADDED Requirements

### Requirement: The compactor's permission does not depend on a cluster's store prefix
The compactor's delete allow SHALL cover `mimir/`, `loki/` and `tempo/` at the top of the store and under any prefix, as one document that does not vary by caller. A cluster's `up` with one store prefix SHALL NOT narrow the allow that another cluster's compactor, with a different prefix, relies on. The compactor SHALL compact the roots of its own store's prefix, and SHALL hold one lease per store prefix, at `<prefix>leases/compactor.json`.

#### Scenario: Two prefixes do not narrow each other
- **WHEN** the compactor allow is rendered for store prefix `a/`, for store prefix `b/`, and for no prefix
- **THEN** the three documents are identical
- **AND** each allows deletes under `mimir/`, `loki/` and `tempo/` at the top and under any prefix

#### Scenario: The compactor follows its store prefix
- **WHEN** the compactor is rendered for store prefix `a/`
- **THEN** its Mimir, Loki and Tempo roots and its lease object are under `a/`
- **AND** its rendered configuration contains no unreplaced store placeholder
