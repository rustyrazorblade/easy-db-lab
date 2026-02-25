# OpenSpec Migration Report

**Date:** 2025-02-25
**Source:** `.specify/` (spec-kit) + `specs/` (feature specs)
**Destination:** `openspec/specs/` (OpenSpec domain-organized)

## Domain Mapping

| Spec-Kit Feature | OpenSpec Domain(s) |
|---|---|
| 001-project-overview (Story 1: Provisioning) | cluster-lifecycle |
| 001-project-overview (Story 2: Cassandra) | cassandra |
| 001-project-overview (Story 3: ClickHouse) | clickhouse |
| 001-project-overview (Story 4: Observability) | observability |
| 001-project-overview (Story 5: MCP) | mcp-server |
| 001-project-overview (Story 6: Spark/EMR) | spark-emr |
| 001-project-overview (FR-013: OpenSearch) | opensearch |
| 001-project-overview (FR-010: SSH access) | networking |
| 001-project-overview (Key Entity: AMI) | ami-building |
| 002-event-bus (all stories) | event-system |

## New Domains from Code Reconciliation

These domains were created from codebase functionality not covered by spec-kit features:

| OpenSpec Domain | Source |
|---|---|
| setup | Codebase: SetupProfile, ConfigureAWS, ShowIamPolicies, UserConfigProvider |
| networking | Codebase: Tailscale commands, SSH aliases, SOCKS proxy, Exec, Hosts/Ip |
| stress-testing | Codebase: Stress job commands, sidecar management (implied by 001 node roles) |

## Drift Items Found

| Item | Spec Says | Code Does | Resolution |
|---|---|---|---|
| Cassandra versions (FR-003) | 2.2 through trunk | 3.0 through trunk | Corrected to 3.0 in domain spec |
| Mixed-version clusters (FR-015) | Explicit feature | Implicit via per-host `use` command | Documented as-is — manual, per-host |
| OpenSearch.WaitProgress event | Structured fields (domainName, currentStatus) | Passthrough `message: String` | Noted as drift — fix later |
| Docker.ContainerOutput event | Not documented | Passthrough `message: String` | Noted as drift — fix later |

## Intentionally Dropped

| Item | Reason |
|---|---|
| Container registry (RegistryManifestBuilder, RegistryService) | Being removed in favor of AWS ECR |
| REPL command | Developer tooling, not core behavior |
| aws/Region, aws/S3Bucket commands | Scripting utilities, not core behavior |
| 002-event-bus data-model.md (per-event field listings) | Too granular for spec — event fields are an implementation concern maintained in code |
| 002-event-bus tasks.md | Implementation tracking, not specification |
| 002-event-bus research.md | One-time research, not specification |
| 002-event-bus quickstart.md | Developer onboarding doc, not specification |

## Constitution

The `.specify/memory/constitution.md` principles were carried forward into:
- `openspec/config.yaml` — tech stack constraints and rules
- Individual domain specs — behavioral requirements derived from the principles
- The constitution itself is not replicated in OpenSpec; its content is distributed across config and specs.

## Archive Note

`.specify/` and `specs/` directories can be archived once you are satisfied with the OpenSpec migration. They are not referenced by the new structure.
