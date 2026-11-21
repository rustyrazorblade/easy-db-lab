## 1. Kernel and event

- [x] 1.1 `kernel/ProviderErrorHandler.kt`: add `ProviderErrorKind.REJECTED` (`rejected`), `ProviderRequest`, `ProviderPolicy`, and optional `request`/`policy` on `ErrorDescription`
- [x] 1.2 `events/Event.kt` `Command.ProviderError`: optional `request`/`policy`; headline names the request and policy when present, and a `rejected` headline; serialization test that AWS and Docker events have neither field
- [x] 1.3 `services/CommandFailureReporter.kt`: pass `request`/`policy` through

## 2. Parsers (test first, pure strings)

- [x] 2.1 `kubernetes/KubernetesDenial.kt` tests: RBAC namespaced, cluster scope, subresource, object name; `system:anonymous`; Pod Security with several violations and commas inside parentheses; webhook; ValidatingAdmissionPolicy; dry-run-not-supported not matched; unmatched text
- [x] 2.2 Implement `RbacDenial.parse`, `AdmissionRejection.parse`, `Throwable.isKubernetesAuthRejection()`

## 3. Handler

- [x] 3.1 `KubernetesErrorHandler` tests on `KubernetesClientException` built from real `Status` objects (no mocks): every claimed case and kind, fallback to URI getters and HTTP method, null status, 404/409/422 and code-0 transport not claimed, wrapped in `IllegalStateException`/`CompletionException` through the reporter
- [x] 3.2 Implement the handler; bind it in `kubernetesModule` as `ProviderErrorHandler`

## 4. Exec plugin check

- [x] 4.1 `ExecPluginCheck` tests with `@TempDir` kubeconfig and injected `PATH`: missing bare plugin, install hint, relative path present and missing, plugin present, no exec user
- [x] 4.2 Implement `ExecPluginCheck` and `ExecPluginNotFoundException`; call it from `KubeApi.loadConfig()`; handler claims the exception as `credentials`

## 5. Keep causes

- [x] 5.1 Keep the Fabric8 cause at `UpPostSteps.kt`, `GrafanaUpdateConfig.kt`, `ObservabilityStackService.kt` (5 sites), `KitStorageResolver.kt`, `PlacementCheck.kt`, `ObservabilityDeployInputs.kt`, `PlatformCreatePvs.kt`, `StressLogs.kt`
- [x] 5.2 `CapabilityProbeService`: delete `explain`, keep the baseline Pod's exception as the cause; update its tests

## 6. No retry of 401/403

- [x] 6.1 Pass `isTransient = { !it.isKubernetesAuthRejection() }` at the polls in `DefaultK8sNamespaceOperations`, `StressJobService`, `BackendWorkloads`, `TelemetrySenders`; test that a 403 fails after one attempt

## 7. Integration

- [x] 7.1 `src/integrationTest/`: K3s TestContainer with a ServiceAccount-token kubeconfig with no RBAC (real 403) and a `pod-security.kubernetes.io/enforce=restricted` namespace with a privileged Pod (real Pod Security message); assert the descriptions

## 8. Docs and verification

- [x] 8.1 `events/CLAUDE.md`, `providers/CLAUDE.md`, `kubernetes/CLAUDE.md` files table; user docs section on permission and admission errors on your own cluster
- [x] 8.2 `./gradlew ktlintFormat detekt test integrationTest` (in a subagent), all green, no detekt loosening

## 9. Review fixes and owner decisions (2026-11-20)

- [x] 9.1 `ExecPluginNotFoundException` in its own file; `ExecPluginCheck` takes the kubeconfig parsed once, finds only an executable file, and uses the JVM `PATH`
- [x] 9.2 `Constants.HttpStatus.UNPROCESSABLE_ENTITY`; strip userinfo as well as the query from a URI in the detail
- [x] 9.3 Classify the namespace-terminating, ResourceQuota and LimitRange 403s; leave any other non-RBAC 403 to `ExecutionError` (D8); K3s tests for the quota and terminating cases
- [x] 9.4 `isPermanentKubernetesFailure()` at every Kubernetes poll and the kit stop wait; `kubernetesFailureReason()` at `status`, `Kit.StopUnverified` and `metrics stream` (`Stress.JobListFailing`) (D9)
- [x] 9.5 Integration: a poll's 403 reported through the reporter as permission-denied; the fallback request asserted from a real Fabric8 exception
- [x] 9.6 Design: D1 to D6 approved, D7 to D9 recorded, open questions closed (#1228)
