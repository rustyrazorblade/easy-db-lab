## Why

Creating a new kit requires a consistent set of deliverables — `kit.yaml`, K8s templates, metrics config, dashboards, documentation, and query engine integrations — that are easy to miss across multiple sessions. A dedicated OpenSpec schema gives kit authors a structured checklist and proposal template tuned to kit creation, rather than the general feature-development workflow.

## What Changes

- New custom schema `kit-workflow` added at `openspec/schemas/kit-workflow/`
- Schema forks `spec-driven` but drops `specs` and `design` artifacts — only `proposal` and `tasks` remain
- `proposal.md` template captures kit-specific context: workload name, node type, runtime type, endpoints, args
- `tasks.md` template is a comprehensive required checklist covering all kit deliverables including integrations (Presto catalogs, other query engine connectors)

## Capabilities

### New Capabilities

- `kit-workflow-schema`: Custom OpenSpec schema for creating easy-db-lab kits. Provides a proposal template and a required-section tasks checklist that covers kit.yaml, K8s templates, metrics, dashboards, documentation, and query engine integrations.

### Modified Capabilities

## Impact

- New directory `openspec/schemas/kit-workflow/` (version-controlled with the project)
- No changes to existing CLI behavior or existing kits
- Dashboard registration in the tasks template requires a Kotlin code change (`GrafanaDashboard` enum) each time a new kit is created using this schema
- No changes to existing OpenSpec changes or specs
