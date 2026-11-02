## ADDED Requirements

### Requirement: A Tests dashboard lists every test of the selected tenant

A core dashboard "Tests", uid `tests`, SHALL list every cluster of the tenant that the metrics picker selects, one row per cluster, with its start time, end time and duration.  The start and end SHALL be the first and last `up` sample of the cluster in Mimir, found with a subquery at a step of about 5 minutes over the `lookback` variable (custom, default `180d`).  The window is the whole life of the cluster.  The listing SHALL use data already in the store and SHALL work for clusters that are down.  The dashboard SHALL declare the datasource pickers, a single-select `cluster` variable for the selected test, `lookback`, and `doc_tenant`.

#### Scenario: Every cluster in the tenant is listed

- **WHEN** an operator opens the Tests dashboard
- **THEN** it lists every cluster in the selected tenant that has `up` samples within `lookback`
- **AND** each row shows a start time and an end time

#### Scenario: A past cluster is listed after down

- **WHEN** a cluster of the tenant ran 20 days ago and was torn down
- **THEN** the Tests dashboard lists it with its start time and end time

#### Scenario: Another tenant lists its own tests

- **WHEN** an operator selects tenant `acme` in the metrics picker
- **THEN** the Tests dashboard lists the clusters of `acme`

### Requirement: Each test row links to dashboards for that test

Each row of the Tests dashboard SHALL offer these links:

- System Overview and Cassandra Overview, with `var-cluster` set to the row's cluster and the time range set to the row's window, padded by one step on each side;
- "Compare with the current cluster", which opens `cluster-comparison` with only `baseline_cluster` set to the row's cluster, so the candidate keeps its install default, the current cluster, and the dashboard keeps its relative time range;
- "Show documents", which opens the Tests dashboard with `var-cluster` set to the row's cluster.

Each link SHALL carry the pickers and `doc_tenant`.

#### Scenario: A row link opens the test's window

- **WHEN** an operator follows a test's System Overview link on the Tests dashboard
- **THEN** System Overview opens with that cluster selected and the time range set to that test's window

#### Scenario: A row link keeps the tenant

- **WHEN** an operator on tenant `acme` follows a test's Cassandra Overview link
- **THEN** Cassandra Overview opens with the `acme` pickers selected

#### Scenario: Compare with the current cluster

- **WHEN** an operator follows a test's "Compare with the current cluster" link
- **THEN** `cluster-comparison` opens with that test as the baseline and the current cluster as the candidate

#### Scenario: Show documents selects the test

- **WHEN** an operator follows a test's "Show documents" link
- **THEN** the Tests dashboard opens with that test selected and shows its documents

### Requirement: The Tests dashboard shows the selected test's documents

The Tests dashboard SHALL show the `index.html` of the selected test, `reports/${doc_tenant}/${cluster}/index.html`, in an iframe inside a Text panel in HTML mode, loaded through the documents web server.  The index holds every document of the test, rendered, each in its own section headed with its name.

#### Scenario: Every document of the test is shown

- **WHEN** a test has documents
- **THEN** the Tests dashboard shows every document of the test, rendered, each in its own section headed with its name

#### Scenario: A new document shows with no dashboard change

- **WHEN** an operator uploads a document with a new name
- **THEN** it shows on the dashboards with no dashboard change

#### Scenario: A test with no documents

- **WHEN** the selected test has no uploaded documents and `up` wrote its empty index
- **THEN** the documents panel shows "No documents yet"
