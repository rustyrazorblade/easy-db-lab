## MODIFIED Requirements

### Requirement: Installed dashboards default to the current cluster

Every path that installs a dashboard SHALL apply one install-time pass to it: the core dashboard tree, kit dashboards on `start`, and `grafana install`.  The pass SHALL read the cluster state of the workspace.  It SHALL set only these values and SHALL NOT change any query:

- the `current` value of each datasource picker to the stable datasource of its type (`mimir`, `loki` or `tempo`), which reads the cluster's own tenant;
- the `current` value of `cluster`, `baseline_cluster` and `candidate_cluster` to the current cluster's `<name>-<id>`, as a one-element list when the variable is multi-select;
- the options of `doc_tenant` to the tenant list, and its `current` value to the cluster's own tenant;
- the documents host placeholder to nothing, so the documents URL is the relative path `/reports/...`, served on Grafana's own origin.

#### Scenario: Core dashboards default to the current cluster

- **WHEN** easy-db-lab installs the core dashboards on cluster `lab-<id>`
- **THEN** the `cluster` variable of each installed dashboard defaults to `lab-<id>`

#### Scenario: Kit dashboards default to the current cluster

- **WHEN** a kit `start` installs its dashboards on cluster `lab-<id>`
- **THEN** the `cluster` variable of each installed kit dashboard defaults to `lab-<id>`

#### Scenario: The current cluster shows with no selection by hand

- **WHEN** an operator opens a dashboard that has a `cluster` variable on a running cluster
- **THEN** it shows the current cluster with no selection by hand

#### Scenario: A multi-select variable gets a one-element list

- **WHEN** the pass sets the default of a multi-select `cluster` variable
- **THEN** the default is a list that holds only the current cluster

#### Scenario: Nothing else changes

- **WHEN** the pass runs on a dashboard
- **THEN** every query, every variable query and every panel is the same as in the file

#### Scenario: A dashboard without these variables is unchanged

- **WHEN** the pass runs on a dashboard that has no picker, no `cluster`, no run variable and no `doc_tenant`
- **THEN** the installed dashboard has the same content as the file


#### Scenario: The documents URL is relative

- **WHEN** the pass runs on a dashboard whose documents iframe points at `__DOCUMENTS_URL__/reports/<tenant>/<name>-<id>/index.html`
- **THEN** the installed iframe points at `/reports/<tenant>/<name>-<id>/index.html`, with no host and no port
