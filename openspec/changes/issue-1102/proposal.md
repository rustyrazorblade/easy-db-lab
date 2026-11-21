## Why

On a cluster easy-db-lab did not build (EKS, GKE, AKS through `init --kubeconfig`, #1082 (init against an existing cluster)), the first failures are permissions and admission: a 403 from RBAC, a 401, a Pod Security rejection, an admission webhook denial, or a kubeconfig exec auth plugin that is not installed. Fabric8 raises them as `KubernetesClientException` chains, and today the user gets `Failure executing: POST at: https://... Message: ...` text or a bare cause chain. Decision D5 on #1062 (multi-cloud epic) has each provider register a `ProviderErrorHandler` that `CommandFailureReporter` asks to describe a failure (#746, merged as PR #1083). Kubernetes has none yet. Part of #1032 (Kubernetes-forward epic), phase C.

## What Changes

- **A Kubernetes provider error handler** (`kubernetes/KubernetesErrorHandler`), registered in `kubernetesModule` with `bind ProviderErrorHandler::class`. It has no collaborators and claims by status code and the API server's message:
  - 403 RBAC denial: kind `permission-denied`, with the verb, resource, namespace, name and principal parsed from the server's message (falling back to the request URI and HTTP method).
  - 401, or 403 as `system:anonymous`: kind `credentials`.
  - Pod Security rejection: new kind `rejected`, with the policy level and version and one entry per violating field.
  - Admission webhook or ValidatingAdmissionPolicy denial: kind `rejected`, naming the webhook or policy.
  - Anything else (404, 409, 422, transport failures) stays `Command.ExecutionError`.
- **Structured facts on the existing event (proposed option A2, pending owner approval).** `ErrorDescription` and `Event.Command.ProviderError` gain two optional, provider-neutral fields: `request` (`ProviderRequest`: verb, resource, namespace, name, principal) and `policy` (`ProviderPolicy`: name, level, violations). `ProviderErrorKind` gains `rejected`. AWS and Docker events serialize as before. The console headline names the request and policy when present.
- **Missing exec auth plugin check** in `KubeApi.loadConfig()`, the one place every client loads its kubeconfig. Fabric8 does not fail on a missing plugin (it logs a warning and sends no credentials), so the check looks the plugin up the way Fabric8 does and throws `ExecPluginNotFoundException` naming the plugin, the user, the context and the `PATH` searched; the handler reports it with the kubeconfig's `installHint` as remedy.
- **Causes kept.** About 13 sites rebuild a Fabric8 failure as `error("...: ${e.message}")` with no cause, which hides it from the reporter. They keep the cause, after the existing `GrafanaDeployService` pattern. `CapabilityProbeService` drops its own 403 explanation (`explain`) and keeps the baseline Pod's exception as the cause.
- **No retry of 401/403.** The four Kubernetes polls that use resilience4j through `pollUntil` with the retry-everything default (rollout status, stress job, backend workloads, collector stop) pass a predicate that treats 401/403 as not transient. The shared defaults are unchanged. Fabric8 itself retries only 429 and 5xx.
- **Docs:** `events/CLAUDE.md`, `providers/CLAUDE.md`, `kubernetes/CLAUDE.md`, and a short user-docs section on permission and admission errors on your own cluster.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `provider-errors`: the kind list gains `rejected` and the event gains optional `request` and `policy`; new requirements for the Kubernetes handler, for keeping Kubernetes causes, and for not retrying 401/403.
- `kubernetes-api-access`: a missing exec auth plugin fails fast at kubeconfig load, naming the plugin and `PATH`.

## Out of scope

- **Pod Security rejection of controller-created Pods** (a Deployment, StatefulSet or Job is accepted, and its controller's Pod creation fails; the rollout wait times out). Reading `ReplicaFailure` and Job `FailedCreate` in `RolloutBlocker` is proposed as a follow-up issue, pending owner decision (design, Open question F).
- Swallowing sites on the K3s bring-up path (`ControlPlaneSampler`, `Fabric8ControlPlaneReader`), which run only with an admin kubeconfig.

## Impact

- New: `kubernetes/KubernetesErrorHandler.kt`, `kubernetes/KubernetesDenial.kt` (pure parsers), `kubernetes/ExecPluginCheck.kt`, `kubernetes/ExecPluginNotFoundException.kt`.
- Changed: `kernel/ProviderErrorHandler.kt`, `services/CommandFailureReporter.kt`, `events/Event.kt` (`Command.ProviderError`), `kubernetes/KubeApi.kt`, `kubernetes/KubernetesModule.kt`, `services/CapabilityProbeService.kt`, the cause-dropping sites (`commands/UpPostSteps.kt`, `commands/grafana/GrafanaUpdateConfig.kt`, `services/ObservabilityStackService.kt`, `services/KitStorageResolver.kt`, `services/PlacementCheck.kt`, `services/ObservabilityDeployInputs.kt`, `commands/platform/PlatformCreatePvs.kt`, `commands/cassandra/stress/StressLogs.kt`), and the four polls (`DefaultK8sNamespaceOperations.kt`, `StressJobService.kt`, `BackendWorkloads.kt`, `TelemetrySenders.kt`).
- Tests: unit tests on `KubernetesClientException` built from real `Status` objects (no mocks), `ExecPluginCheck` with `@TempDir` and an injected `PATH`, and K3s TestContainer integration tests for a real RBAC 403 and a real Pod Security rejection.
