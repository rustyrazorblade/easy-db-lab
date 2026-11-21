| Source | Requirement | Covering scenario(s) | Status |
|--------|-------------|----------------------|--------|
| AC1 | 403 names verb, resource, namespace and user, no stack trace | `provider-errors: RBAC denial in a namespace`; `RBAC denial at the cluster scope and on a subresource`; `Real API server messages`; `Post-step failure on a cluster without RBAC` | Covered (user = the API-server principal, owner decision D7) |
| AC2 | Pod Security rejection names the policy level and the violating field | `provider-errors: Pod Security rejection`; `Real API server messages`; `Probe baseline rejected by Pod Security` | Covered for directly created Pods; controller-created Pods are follow-up #1228 |
| AC3 | Missing exec plugin names the plugin | `kubernetes-api-access: Exec plugin not on PATH`; `Install hint shown`; `Relative plugin path` | Covered |
| Scope | 401 unauthorized | `provider-errors: Anonymous 403 and 401 are credential failures` | Covered |
| Scope | Admission webhook denial | `provider-errors: Admission webhook denial` | Covered |
| Scope | Handler registered in Koin, works before a profile | Requirement text "no collaborators ... before a profile" | Covered |
| Owner | Typed domain event, never `Event.Message`/`Event.Error` | Requirement "Provider failures are reported with a remedy" (modified) | Covered |
| Owner | Never retry 401/403 | `provider-errors: Rollout wait meets a 403` | Covered |
| Risk | Message drift across distributions | `An unexplained 403 is the generic error`; `Real API server messages` | Covered |
| Risk | Over-claiming (transport, 404/409/422, dry-run 400) | `Other Kubernetes failures are not claimed` | Covered |
| Risk | Causes dropped before the reporter | `Wrapped Kubernetes failure`; `Post-step failure on a cluster without RBAC` | Covered |
| Risk | Fabric8 silently skips a missing exec plugin | `Exec plugin not on PATH` (fails before any API request) | Covered |
| Risk | Null or non-JSON status body | `An unexplained 403 is the generic error` | Covered |
| Owner | A 403 that is not RBAC gets no cluster-admin advice | `An unexplained 403 is the generic error`; `Namespace being deleted`; `Full ResourceQuota`; `LimitRange violation`; `Real API server messages` | Covered (decision D8) |
| Risk | A missing exec plugin retried or hidden in a note | `A poll meets a missing exec plugin`; `status`; `Kit stop wait`; `metrics stream`; `live-stream-metrics: Job list fails for good` | Covered (decision D9) |
| Risk | Secret leakage in detail | Requirement text (status message only, never URI query or credential) | Covered (`A message without a status keeps no URI secret`) |
| Risk | Plugin present or no exec user regress | `Plugin present, or no plugin` | Covered |
| Risk | Controller-created Pods rejected by Pod Security | none | Excluded: follow-up #1228 |
| Risk | K3s bring-up sites that swallow any `KubernetesClientException` | none | Excluded: run only with an admin kubeconfig |
