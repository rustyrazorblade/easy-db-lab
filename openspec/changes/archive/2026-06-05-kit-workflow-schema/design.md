## Context

Kit creation is a recurring activity with a fixed set of required deliverables. The existing `spec-driven` schema is designed for CLI feature development and produces artifacts (capability specs, design docs) that don't map to kit work. Kit authors need a checklist-driven workflow rather than a spec-first one.

OpenSpec supports custom schemas in `openspec/schemas/<name>/` — version-controlled with the project and available via `--schema kit-workflow`.

## Goals / Non-Goals

**Goals:**
- Provide a `proposal.md` template scoped to kit context (workload, node type, runtime, endpoints)
- Provide a `tasks.md` template with required sections for all kit deliverables
- Make integrations (Presto catalogs, other query engine connectors) a required checklist step, not optional
- Work both standalone (kit-only change) and as part of a larger change

**Non-Goals:**
- Replacing the `spec-driven` schema for regular feature work
- Automating any kit file generation
- Enforcing schema for existing kits

## Decisions

**Fork `spec-driven`, drop `specs` and `design` artifacts.**
The `spec-driven` dependency chain (proposal → specs + design → tasks) adds overhead for kit work. Kits don't introduce behavioral requirements for the CLI — they're self-contained bundles. A two-artifact chain (proposal → tasks) is sufficient.

**Metrics and dashboards are required sections, not optional.**
The value of a kit is that it works out of the box with full observability. Any kit missing metrics or dashboards is incomplete. The tasks template treats them as required sections with no "if applicable" language.

**Integrations section is required.**
When adding a new database kit, query engine connectivity (Presto catalogs, ClickHouse federated queries, etc.) is part of the kit's value. The tasks template always includes an integrations section prompting the author to investigate connector support.

**Store schema in `openspec/schemas/`.**
Version-controlled with the project. All contributors get the schema automatically. No per-developer setup required.

## Risks / Trade-offs

- The tasks template is a starting point — specific kits may need additional sections (e.g., operator CRDs, multi-tenant config). Authors should extend the checklist as needed.
- The schema does not enforce that all sections are completed — it relies on the author working through the checklist.
- Dashboard registration (`GrafanaDashboard` enum) is the one structural coupling between kit creation and the main Kotlin codebase. If the enum or its registration mechanism changes, the tasks template's dashboard section will need updating.
