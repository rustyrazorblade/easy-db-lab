## MODIFIED Requirements

### Requirement: report upload stores documents in the test's folder

A command `report upload <dir>`, run from a cluster workspace, SHALL zip the directory and upload the zip, unchanged, as `reports/<tenant>/<experiment>/report-<yyyyMMdd-HHmmss>.zip` in the account bucket, where the timestamp is the upload time in UTC.  The command SHALL read the tenant and the experiment from the workspace's cluster state and SHALL take no experiment option.  It SHALL write the zip only if no object exists at its key; if that second is taken, it SHALL use the next free second.  No upload SHALL overwrite or remove an earlier upload.  The command SHALL use the operator's own AWS credentials and SHALL need only the workspace's cluster state, so it works before and after `down`.  The report belongs to the experiment, never to a single cluster, so every cluster of an experiment shows the same report.  On success the command SHALL emit one typed event that names the zip's key, the unpacked prefix, whether the pointer moved, and the files.

#### Scenario: The directory is stored as one zip
- **WHEN** an operator runs `report upload results/` in the workspace of a cluster in tenant `acme` with experiment `e1`
- **THEN** `reports/acme/e1/report-<yyyyMMdd-HHmmss>.zip` exists in the account bucket
- **AND** its bytes are identical to the zip the command built

#### Scenario: A second upload keeps the first
- **WHEN** an operator runs `report upload` a second time for the same experiment
- **THEN** both zips are in S3, unchanged
- **AND** the documents view shows the newer one

#### Scenario: Upload after down
- **WHEN** the operator runs `report upload` after `down`
- **THEN** the upload succeeds with the operator's credentials

#### Scenario: Two clusters of one experiment show one report
- **WHEN** two clusters of experiment `e1` open their documents panels
- **THEN** both show the same report

### Requirement: report upload renders each document and rebuilds the test's index

After it stores the zip, `report upload` SHALL unpack the same zip to `reports/<tenant>/<experiment>/report-<yyyyMMdd-HHmmss>/` next to it, SHALL store an HTML copy of each `.md` file, and SHALL write a generated `_files.html` that lists the upload's files.  Every generated HTML file SHALL declare `<meta charset="utf-8">`.  The pointer `reports/<tenant>/<experiment>/index.html` SHALL lead to the newest upload: to its `index.html` when the upload holds an `index.md` or `index.html` at its root, and to its `_files.html` otherwise.  The pointer SHALL only move forward: an upload SHALL leave a pointer that already names an equal or newer upload, and SHALL replace an older one only with a conditional write on the pointer's version, re-reading and retrying when another writer changed it first.  The pointer is derived and is the only object under the experiment's folder that is ever rewritten.

#### Scenario: The newest upload is unpacked and rendered
- **WHEN** an upload of a directory with `results.md` and `graph.png` completes
- **THEN** the upload's folder holds `results.md`, `results.html`, `graph.png` and `_files.html`
- **AND** the documents panels show the upload on a cluster and in the local stack

#### Scenario: An index.md is the front page
- **WHEN** the uploaded directory holds `index.md` at its root
- **THEN** the pointer leads to the upload's `index.html`

#### Scenario: Without an index the file list is the front page
- **WHEN** the uploaded directory holds no `index.md` or `index.html` at its root
- **THEN** the pointer leads to the upload's `_files.html`

#### Scenario: An older upload does not move the pointer back
- **WHEN** an upload named for an earlier second finishes after an upload named for a later second
- **THEN** the pointer still leads to the later upload

### Requirement: up writes an empty index for the test

`up` SHALL write the experiment's pointer, saying "No report yet", only when no pointer exists, with a conditional write, using the operator's credentials.  `up` SHALL NOT change an existing pointer, so a cluster that joins an experiment never hides the report of another cluster of that experiment.

#### Scenario: A new experiment has an empty report
- **WHEN** `up` runs on the first cluster of a new experiment
- **THEN** `reports/<tenant>/<experiment>/index.html` exists and says "No report yet"

#### Scenario: A second cluster keeps the report
- **WHEN** `up` runs on a second cluster of an experiment that already has a report
- **THEN** the pointer still leads to that report

## ADDED Requirements

### Requirement: report upload takes one directory whose files the documents server can serve

`report upload` SHALL take exactly one argument, a directory.  Before it builds or uploads anything it SHALL check the whole directory and SHALL upload nothing if any check fails, naming every file that fails:

- the directory SHALL exist and SHALL hold at least one file;
- every segment of every relative path SHALL match `[A-Za-z0-9._-]+`, and no path SHALL hold `..`;
- no entry SHALL be a symbolic link;
- the root SHALL NOT hold `_files.html`, which the command generates;
- no `X.md` SHALL have a sibling `X.html` at any depth, because the HTML copy would collide; both files SHALL be named.

The file-list form `report upload FILE...` SHALL NOT exist.

#### Scenario: A file the documents server cannot serve is rejected
- **WHEN** the directory holds `my notes.md` and `ok.md`
- **THEN** the command fails, names `my notes.md`, and uploads nothing

#### Scenario: Every offending file is named
- **WHEN** the directory holds `a b.md`, a symbolic link `link.md`, and both `x.md` and `x.html`
- **THEN** the command fails, names each of `a b.md`, `link.md`, `x.md` and `x.html`, and uploads nothing

#### Scenario: A reserved name is rejected
- **WHEN** the directory holds `_files.html` at its root
- **THEN** the command fails, names `_files.html`, and uploads nothing

#### Scenario: A missing directory is rejected
- **WHEN** an operator runs `report upload missing/` and the directory does not exist
- **THEN** the command fails, names `missing/`, and uploads nothing

#### Scenario: An empty directory is rejected
- **WHEN** an operator runs `report upload empty/` and the directory holds no file
- **THEN** the command fails, names `empty/`, and uploads nothing

#### Scenario: The file-list form is rejected
- **WHEN** an operator runs `report upload a.md b.md`
- **THEN** the command fails and says it takes one directory

## REMOVED Requirements

### Requirement: report upload accepts only markdown files with safe names

**Reason**: `report upload` now takes a directory of any file types, zipped as one report. The new rule is "report upload takes one directory whose files the documents server can serve".

**Migration**: Pass the directory that holds the files. Clusters are ephemeral; nothing migrates.

### Requirement: A doc_tenant variable selects the documents' tenant folder

**Reason**: Dashboards now read the tenant from the data (`edl_tenant`) and from the experiment picker's `<tenant>/<name>` value. A hidden `home_tenant` variable, filled at install, is the fallback for clusters from before the label.

**Migration**: None. The install pass no longer fills `doc_tenant`.
