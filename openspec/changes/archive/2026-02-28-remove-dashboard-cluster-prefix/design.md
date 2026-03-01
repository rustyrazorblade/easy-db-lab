## Context

Grafana dashboard titles currently use `__CLUSTER_NAME__ - ` as a prefix via TemplateService substitution. This produces titles like "test - System Overview". The prefix adds clutter and conflicts with planned multi-cluster dashboard work.

## Goals / Non-Goals

**Goals:**
- Remove `__CLUSTER_NAME__ - ` prefix from all dashboard JSON titles
- Dashboards show clean names like "System Overview", "EMR Overview"

**Non-Goals:**
- Removing `__CLUSTER_NAME__` from non-title uses (e.g., PromQL queries, panel labels)
- Changing dashboard UIDs or structure

## Decisions

- **Direct JSON edit**: Simply remove the `__CLUSTER_NAME__ - ` prefix from the `"title"` field in each dashboard JSON file. No code changes needed — this is purely a resource file change.

## Risks / Trade-offs

- [Users lose cluster name in dashboard title] → The cluster name is already visible in the Grafana branding title bar (`GF_BRANDING_APP_TITLE`), so removing it from individual dashboard titles doesn't lose information.
