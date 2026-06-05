## 1. Fork spec-driven and create schema structure

- [x] 1.1 Run `openspec schema fork spec-driven kit-workflow` to create `openspec/schemas/kit-workflow/`
- [x] 1.2 Verify the fork created `schema.yaml` and `templates/` with proposal, spec, design, tasks templates

## 2. Rewrite schema.yaml

- [x] 2.1 Remove the `specs` artifact entry from `schema.yaml`
- [x] 2.2 Remove the `design` artifact entry from `schema.yaml`
- [x] 2.3 Update `proposal` artifact: remove `Capabilities` section from instruction, rewrite instruction to be kit-focused (workload identity, node type, runtime, endpoints, args, collision-check, integrations)
- [x] 2.4 Update `tasks` artifact: change `requires` to `[proposal]` only (remove `specs` and `design` dependencies)
- [x] 2.5 Update `tasks` artifact instruction — include this requirement verbatim: "Every deliverable in the template MUST be a `- [ ] N.M description` checkbox. Do not use prose lists or nested bullets — the OpenSpec apply tracker only recognises checkbox format."
- [x] 2.6 Verify the `apply` block still reads `requires: [tasks]` after removing specs/design; update if needed
- [x] 2.7 Delete the forked `spec.md` and `design.md` template files (they are no longer referenced)
- [x] 2.8 Run `openspec schema validate kit-workflow` and fix any errors

## 3. Write the proposal.md template

- [x] 3.1 Replace the forked `templates/proposal.md` with a kit-focused template containing these sections:
  - `## Kit` — name, description, node type (db|app), collision-check (true if kit owns persistent volumes, false otherwise)
  - `## Workload` — what system this kit installs, link to upstream docs
  - `## Runtime` — helm or pods, chart/operator name and source
  - `## Endpoints` — list of ports/protocols the workload exposes
  - `## Args` — key configuration flags the kit will accept
  - `## Integrations` — known query engine connectors (Presto catalog, ClickHouse federated, etc.)

## 4. Write the tasks.md template

Replace the forked `templates/tasks.md` with a kit implementation checklist. Every item MUST be a `- [ ] N.M description` checkbox where N is the section number and M is the item number within that section (e.g. `1.1`, `1.2`, `2.1` — NOT the 4.x numbering used in this change's task list). The sections and checkboxes to include:

- [x] 4.1 Section 1 — kit.yaml identity: name, type (db|app), description, version, collision-check
- [x] 4.2 Section 1 — kit.yaml runtime block: type (helm|pods), release/chart or selector/namespace
- [x] 4.3 Section 1 — kit.yaml metrics block: scrape type, port, path (if non-default)
- [x] 4.4 Section 1 — kit.yaml endpoints list: name, node-type, port, type (http|jdbc|native) per endpoint
- [x] 4.5 Section 1 — kit.yaml args list: flag, variable, description, default, type per arg
- [x] 4.6 Section 1 — kit.yaml capabilities list: sql with user and driver-class if applicable
- [x] 4.7 Section 1 — kit.yaml hooks: post-workload-start and post-workload-stop if needed
- [x] 4.8 Section 1 — kit.yaml install steps: helm-repo and helm entries
- [x] 4.9 Section 1 — kit.yaml start steps: platform-pvs if needed, manifest templates, shell wait loops
- [x] 4.10 Section 1 — kit.yaml stop steps: delete resources
- [x] 4.11 Section 1 — kit.yaml uninstall steps: delete CRDs, helm-uninstall, platform-pvs-delete if used
- [x] 4.12 Section 2 — K8s Templates: values.yaml.template parameterized with kit args as env vars
- [x] 4.13 Section 2 — K8s Templates: additional manifest templates as needed (operator CRDs, nodeport services, etc.)
- [x] 4.14 Section 3 — Metrics: confirm scrape port/path in kit.yaml
- [x] 4.15 Section 3 — Metrics: write metrics-catalog.json (all exported metric names with descriptions)
- [x] 4.16 Section 3 — Metrics: write METRICS.md (human-readable metrics reference)
- [x] 4.17 Section 4 — Dashboards: create dashboards/ directory with Grafana dashboard JSON
- [x] 4.18 Section 4 — Dashboards: register dashboard in `GrafanaDashboard` enum (Kotlin code change — requires compile + test)
- [x] 4.19 Section 4 — Dashboards: push dashboard to a running cluster and verify it loads
- [x] 4.20 Section 5 — Integrations: investigate whether this workload has a Presto connector
- [x] 4.21 Section 5 — Integrations: if Presto connector exists, add connector jar to Presto kit and create a catalog template for this workload
- [x] 4.22 Section 5 — Integrations: investigate whether this workload supports ClickHouse federated queries or other cross-engine access
- [x] 4.23 Section 5 — Integrations: investigate any other relevant query engine connectors (Spark JDBC, etc.)
- [x] 4.24 Section 5 — Integrations: document supported integrations in README.md.template
- [x] 4.25 Section 6 — Documentation: write README.md.template (overview, args reference, endpoints, known integrations)

## 5. Validate

- [x] 5.1 Run `openspec schema validate kit-workflow` — all checks pass
- [x] 5.2 From the project root, run `openspec new change test-kit --schema kit-workflow` and verify only `proposal.md` and `tasks.md` artifacts are generated (no `specs/**` or `design.md`)
- [x] 5.3 Delete the test change
