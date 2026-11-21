## Overrides existing behavior

### provider-errors: Provider failures are reported with a remedy
**Currently:** Kinds are `credentials`, `permission-denied`, `unavailable`; the event carries provider, kind, detail and remedy only.
**This change:** Adds kind `rejected` and the optional structured fields `request` and `policy`. Existing scenarios are unchanged.

### kubernetes-api-access: Kubeconfig loaded by path with an explicit context
**Currently:** Scenario "Exec auth plugin honoured"; a missing plugin is silently skipped by Fabric8.
**This change:** New requirement: a missing exec plugin fails at load, naming the plugin and `PATH`.

### services/CapabilityProbeService
**Currently:** Explains a 403 itself (cluster-admin requirement) inside a message-only `IllegalStateException`.
**This change:** Keeps the cause; the Kubernetes handler explains it.

### Kubernetes polls
**Currently:** `pollUntil` retries every exception, a 403 included, until the deadline.
**This change:** The four Kubernetes polls fail on the first 401/403.

## Conflicts with other in-flight changes

None found in `openspec/changes/` (issue-1064, 1130, 1157, 1158, 1199, 969 touch neither `provider-errors` nor `kubernetes-api-access`).
