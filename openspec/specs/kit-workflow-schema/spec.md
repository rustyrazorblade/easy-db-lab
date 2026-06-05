## ADDED Requirements

### Requirement: kit-workflow schema exists at openspec/schemas/kit-workflow/

The project SHALL include a custom OpenSpec schema at `openspec/schemas/kit-workflow/` consisting of `schema.yaml` and templates for `proposal.md` and `tasks.md`.

#### Scenario: Schema is available via --schema flag
- **WHEN** a user runs `openspec new change my-kit --schema kit-workflow`
- **THEN** a new change is created using the `kit-workflow` schema with proposal and tasks artifacts

#### Scenario: Schema contains only proposal and tasks artifacts
- **WHEN** the kit-workflow schema is used
- **THEN** only `proposal.md` and `tasks.md` are generated — no `specs/**` or `design.md`

### Requirement: kit-workflow proposal template captures kit context

The `proposal.md` template SHALL prompt for kit-specific context: workload name and description, node type (db|app), runtime type (helm|pods), endpoints, args, and known integrations.

#### Scenario: Proposal template guides kit author
- **WHEN** OpenSpec generates a proposal for a kit-workflow change
- **THEN** the template sections cover workload identity, node type, runtime, endpoints, args, and integrations

### Requirement: kit-workflow tasks template includes required sections for all kit deliverables

The `tasks.md` template SHALL include required sections for: `kit.yaml`, K8s templates, metrics (`metrics-catalog.json`, `METRICS.md`, scrape config), dashboards (JSON + `GrafanaDashboard` enum), documentation (`README.md.template`), and integrations (Presto catalogs, other query engine connectors).

#### Scenario: Tasks checklist covers metrics as required
- **WHEN** a kit-workflow tasks.md is generated
- **THEN** metrics and dashboard sections are present and not marked optional

#### Scenario: Tasks checklist prompts for query engine integrations
- **WHEN** a kit-workflow tasks.md is generated
- **THEN** an integrations section prompts the author to check for Presto connector support and other query engine connectors
