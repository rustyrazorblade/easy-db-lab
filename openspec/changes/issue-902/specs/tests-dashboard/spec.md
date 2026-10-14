## MODIFIED Requirements

### Requirement: A Tests dashboard lists every test of the selected tenant

A core dashboard "Tests", uid `tests`, SHALL list every experiment of the tenant view that the metrics picker selects, one row per experiment, identified by its tenant and its name.  Each row SHALL show the experiment's tenant, its name, its first start, its last end, its number of clusters, and the short names of its clusters.  The start and end SHALL come from the first and last `up` sample of each cluster in Mimir, found with a subquery at a step of about 5 minutes over the `lookback` variable (custom, default `180d`).  An experiment SHALL hold any number of clusters, run at different times over days or weeks.  The listing SHALL use data already in the store and SHALL work for clusters that are down.  A cluster from before the `experiment` label SHALL be listed as an experiment of one, named for its `<name>-<id>`, with its tenant from Mimir's `__tenant_id__` when the view spans tenants and from the home tenant otherwise.  The dashboard SHALL declare the datasource pickers, a single-select `experiment` variable for the selected experiment, `lookback`, and the hidden `home_tenant`.  It SHALL also list the clusters of the selected experiment, one row per cluster, with the start time, end time and duration of each.

#### Scenario: Every experiment in the tenant is listed
- **WHEN** an operator opens the Tests dashboard
- **THEN** each row is one experiment that has `up` samples within `lookback`
- **AND** each row shows the experiment's clusters, its first start and its last end

#### Scenario: A past experiment is listed after down
- **WHEN** every cluster of an experiment ran 20 days ago and was torn down
- **THEN** the Tests dashboard lists the experiment with its clusters

#### Scenario: A cluster from before the label is an experiment of one
- **WHEN** the store holds a cluster `lab-<id>` whose series carry no `experiment` label
- **THEN** the Tests dashboard lists it as its own experiment `lab-<id>`, with one cluster

#### Scenario: Another tenant lists its own experiments
- **WHEN** an operator selects tenant `acme` in the metrics picker
- **THEN** the Tests dashboard lists the experiments of `acme`

#### Scenario: The selected experiment's clusters are listed
- **WHEN** an operator selects experiment `acme/e1`
- **THEN** a table lists each cluster of `e1` with its start time, end time and duration

### Requirement: Each test row links to dashboards for that test

Each experiment row of the Tests dashboard SHALL offer these links:

- "Newest report", which opens the experiment's report pointer, `reports/<tenant>/<experiment>/index.html`, through the documents web server in a new tab;
- "Compare clusters", which opens Experiment: Cassandra with `var-experiment` set to the row's `<tenant>/<name>`;
- "Show here", which opens the Tests dashboard with `var-experiment` set to the row's experiment.

Each cluster row of the selected experiment SHALL offer:

- System Overview and Cassandra Overview, with `var-cluster` set to the row's cluster and the time range set to the row's window, padded by one step on each side;
- "Compare with the current cluster", which opens the cluster pair with only `cluster_a` set to the row's cluster, so `cluster_b` keeps its install default.

Each link SHALL carry the pickers and the experiment.

#### Scenario: The report link opens the newest report
- **WHEN** an operator follows an experiment's "Newest report" link
- **THEN** the experiment's newest report opens

#### Scenario: A pre-label cluster's report link opens its documents
- **WHEN** an operator follows the "Newest report" link of a cluster from before the label
- **THEN** its existing documents at `reports/<tenant>/<name>-<id>/` open

#### Scenario: A row link opens the test's window
- **WHEN** an operator follows a cluster row's System Overview link on the Tests dashboard
- **THEN** System Overview opens with that cluster selected and the time range set to that cluster's window

#### Scenario: A row link keeps the tenant
- **WHEN** an operator on tenant `acme` follows a cluster row's Cassandra Overview link
- **THEN** Cassandra Overview opens with the `acme` pickers selected

#### Scenario: Compare clusters opens the experiment
- **WHEN** an operator follows an experiment's "Compare clusters" link
- **THEN** Experiment: Cassandra opens with that experiment selected and all of its clusters

#### Scenario: Compare with the current cluster
- **WHEN** an operator follows a cluster row's "Compare with the current cluster" link
- **THEN** the cluster pair opens with that cluster as A and the current cluster as B

### Requirement: The Tests dashboard shows the selected test's documents

The Tests dashboard SHALL show the report of the selected experiment, `reports/${experiment}/index.html`, where the picker value is `<tenant>/<name>`, in an iframe inside a Text panel in HTML mode, loaded through the documents web server.  The Tests annotation markers SHALL show the annotations of the selected experiment's clusters.

#### Scenario: The selected experiment's report is shown
- **WHEN** an experiment has a report
- **THEN** the Tests dashboard shows its newest report

#### Scenario: A new upload shows with no dashboard change
- **WHEN** an operator uploads a newer report
- **THEN** it shows on the dashboards with no dashboard change

#### Scenario: An experiment with no report
- **WHEN** the selected experiment has no uploaded report and `up` wrote its empty pointer
- **THEN** the documents panel shows "No report yet"
