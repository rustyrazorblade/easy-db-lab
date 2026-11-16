# Lab Plan: Issue 971 live QA (tenant pickers, Tests dashboard, comparison, documents)

## Objective

Verify issue 971 on a real cluster, built from branch `worktree-issue-971` (PR 984).  Cover tasks 13.2 to 13.7 of `openspec/changes/issue-971/tasks.md`:

- 13.2: Grafana applies variables in panel `timeShift` and `timeFrom` on the comparison dashboards.
- 13.3: the documents iframe renders with `disable_sanitize_html`.
- 13.4: the browser reaches the documents web server (port 3080), and the signing proxy signs for the account bucket's region.
- 13.5: `report upload` works, and the Tests dashboard shows the documents.
- 13.6: a short comparison against a past test.
- 13.7: live QA of every core and kit dashboard.

Success: every check below passes, and each dashboard has a recorded result.

## Cluster Name

qa971

## Datacenters

single

## Environment

- 3 db nodes, 1 app (stress) node, 1 control node.  All `i4i.xlarge`, Cilium, `us-west-2`, tenant `default`.  AWS profile `sandbox-admin`.
- Binary: `bin/easy-db-lab` in the worktree, after `./gradlew installDist`.
- Baseline: the past test `qa970` (cluster id `1384d412-8162-40ee-9d64-29d89bab4cc2`, tenant `default`, 2026-09-27, torn down).  If `qa970` has no metrics in the store, run a second short cluster first per task 13.6, and ask the lead before doing so.

## Steps

### 1. Provision

```bash
$EDB init qa971 --db 3 --app 1 --instance i4i.xlarge --tenant default --up
```

Check `$EDB --help` for the exact option names if one is rejected.

### 2. Start the database and a 20-minute stress run

```bash
$EDB cassandra start
```

Start a 20-minute stress workload on the app node with `cassandra stress start` (see its `--help`).  Let it finish.

### 3. Documents

Write two markdown files, `summary.md` and `notes.md`, with a heading, a table and a code block.  Upload them:

```bash
$EDB report upload summary.md notes.md
```

Check:
- `aws s3 ls s3://<account bucket>/reports/default/<name>-<id>/` shows `summary.md`, `summary.html`, `notes.md`, `notes.html` and `index.html`.
- `report upload index.md` and a name given twice are rejected, and nothing is uploaded.
- `curl http://<control privateIp>:3080/reports/default/<name>-<id>/index.html` returns the index.  A `PUT`, a path outside `/reports/`, `%0d%0a` and `%3F` paths are refused.
- The proxy signs for the account bucket's own region (`aws s3api get-bucket-location`).

### 4. Tests dashboard (browser)

Open the Tests dashboard.  Check:
- It lists `qa971` and `qa970`, each with a start, an end and a duration.
- The Test dropdown can select both.
- The documents iframe renders both documents, each under its own heading.  With `qa970` selected, it shows the S3 error or "No documents yet" as expected for a test with no index.
- Each row link opens its target with that cluster, that window, and the pickers carried.

### 5. Comparison dashboards (browser)

On `cluster-comparison`, `ab-comparison` and `system-ab-comparison`, select baseline `qa970` and candidate `qa971`.  Check:
- The overlay shows both runs from a common start; the longer run is covered in full, and the shorter one ends at its own length.
- The side-by-side panels show each run on its own real time range (`timeShift`/`timeFrom` applied).
- The summary table shows each run's figures and the difference in percent.
- Both runs' document iframes show next to the summary table.

### 6. Every dashboard (task 13.7)

For each of the 29 core dashboards, and for each kit's dashboards while that kit runs:
- The pickers show and default to the home tenant.
- `cluster` defaults to the current cluster (on dashboards that have one).
- Every panel query runs without an error (Grafana `/api/ds/query`).
- The annotations show.
- Links open the target with the pickers and `cluster` carried.

Kits: start each kit (clickhouse, flink, ignite3, kafka, memcached, neo4j, postgres, presto, sysbench, tidb, trino), one or two at a time.  Give it load if the kit has a load script.  QA its dashboards with live data, then stop it before the next.

Record one result line per dashboard in the lab report.

### 7. Hand over (no teardown)

Leave the cluster up for the owner's QA.  Report the Grafana URL and how to reach it.

### 8. Teardown (only when the owner says so)

```bash
$EDB down --auto-approve
```

Then run `report upload` once more from the workspace, and check that the new file is in S3.
