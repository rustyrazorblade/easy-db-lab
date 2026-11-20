## Context

`CommandFailureReporter` (`services/`) walks a failure's cause chain and asks each registered `kernel/ProviderErrorHandler` to describe it; the first match becomes `Event.Command.ProviderError(provider, kind, detail, remedy)`, anything else `Event.Command.ExecutionError`. AWS (`providers/aws/AwsErrorHandler`) and Docker (`providers/docker/DockerErrorHandler`) register handlers; Kubernetes does not.

Fabric8 7.8.0 facts, checked on the cached jars:

- `KubernetesClientException` exposes `getCode()`, `getStatus()` and the request-URI getters `getGroup()`, `getVersion()`, `getResourcePlural()`, `getNamespace()`, `getName()`. `RequestMetadata` is package-private and there is no verb getter.
- The message is `Failure executing: <METHOD> at: <URI>. Message: <status.message>. Received status: ...`; that is the only place the HTTP method appears.
- `Status.details` holds `kind`, `name`, `group`, `causes`.
- Fabric8's own retry (`StandardHttpClient.shouldRetry`) retries only 429 and 5xx.
- A missing exec plugin raises nothing: `KubeConfigUtils.getAuthenticatorCommandFromExecConfig` runs it through `sh -c`, gets exit 127, logs a warning, and the client sends the request without credentials. The user sees a 401 or an anonymous 403 that does not name the plugin.
- About 13 sites rebuild a Fabric8 failure as `error("...: ${e.message}")` with no cause, so no handler can see it.

## Goals / Non-Goals

**Goals:** AC1 to AC3 of #1102, plus 401 and admission webhook denials, as structured fields on the existing typed event; no stack trace; no retry of 401/403.

**Non-goals:** Pod Security rejections of controller-created Pods (open question F); credential refresh or rotation; any change to the AWS or Docker handlers' classification.

## Decisions

Each decision below is **proposed, pending owner approval**. The alternatives are kept so the owner can redirect.

### D1. Event shape: optional structured fields on `Command.ProviderError` (option A2)

`ErrorDescription` and `Event.Command.ProviderError` gain `request: ProviderRequest? = null` and `policy: ProviderPolicy? = null`, two small `@Serializable` provider-neutral data classes in `kernel/`. `ProviderErrorKind` gains `REJECTED` (`@SerialName("rejected")`), because "grant RBAC" is the wrong remedy for a policy rejection. One reporter path and one event stay; MCP and Redis consumers get typed fields; AWS could fill `request` later.

```kotlin
@Serializable data class ProviderRequest(val verb: String, val resource: String,
    val namespace: String? = null, val name: String? = null, val principal: String? = null)
@Serializable data class ProviderPolicy(val name: String, val level: String? = null,
    val violations: List<String> = emptyList())
```

Alternatives:
- **A1.** Reuse `ProviderError` unchanged plus a `REJECTED` kind, facts only in `detail`. Smallest diff, but external consumers get free text, against the "structured fields" rule.
- **A3.** New `Event.K8s.RequestDenied` / `AdmissionRejected`. Most typed, but `kernel/` cannot depend on `events/`, so the reporter would need provider knowledge or `ErrorDescription` an event factory; it breaks the handler abstraction.

### D2. Facts come from the API server's message, with the request URI as fallback (option B1)

The RBAC message gives the real RBAC verb (`list`, `watch`, not `GET`) and the principal the server evaluated, which is what a grant must name. When it does not match, the handler uses the Fabric8 getters and the HTTP method from the failure message. The "user" reported is that principal, not the kubeconfig `users[].name`, so the handler keeps no collaborators and works during `init --kubeconfig`, when the probed kubeconfig is not yet the workspace one. A 401 carries no principal and names none. The exec-plugin check names the kubeconfig user, since it has the file in hand.

Alternative **B2**: getters only. No user and only the HTTP method as verb, so AC1 falls short. Alternative: resolve the kubeconfig user through `KubeApiLocator`; it reads the workspace kubeconfig, which is the wrong file during `init --kubeconfig`.

### D3. Location: `kubernetes/`, registered in `kubernetesModule` (option C1)

The Fabric8 adapter package already owns kubeconfig parsing (`KubeconfigFile.kt`, `KubeApi.kt`). Parsers live in `KubernetesDenial.kt` as pure functions over strings (`RbacDenial.parse`, `AdmissionRejection.parse` with `PodSecurity`, `Webhook`, `ValidatingAdmissionPolicy` cases, and `Throwable.isKubernetesAuthRejection()`). Registration order is docker, kubernetes, aws (`di/KoinModules.kt`); no exception is claimed by two handlers, since each claims only its own types. Alternative **C2**: `providers/kubernetes/`, symmetric with AWS and Docker but splits Fabric8 knowledge across two packages.

### D4. Exec-plugin check at `KubeApi.loadConfig()`

`ExecPluginCheck(path: String? = System.getenv("PATH")).check(kubeconfig, context)` resolves the plugin as Fabric8 does: a bare name on `PATH`, a name with a separator against the kubeconfig's directory. It throws `ExecPluginNotFoundException(plugin, user, context, searchedPath, installHint)`, which the handler claims as `credentials`. `PATH` is a constructor parameter for tests. This is the only way to meet AC3, because Fabric8 raises nothing.

### D5. Keep the cause at every Kubernetes wrapping site (option D1)

All 13 cause-dropping sites keep the Fabric8 exception as the cause (the `GrafanaDeployService.kt:133` pattern). Without this, AC1 and AC2 fail on exactly the commands that hit RBAC on a user's cluster (`up` post steps, `grafana update-config`). `CapabilityProbeService.explain` is deleted; the handler's remedy states the cluster-admin requirement. Capability-Pod rejections stay probe notes. Trade-off: the outer "Failed to apply ..." text is no longer what the user sees; `request` names the resource instead. Alternative **D2**: fix only the your-cluster sites; cheaper, inconsistent output.

### D6. Polls pass a not-transient predicate for 401/403 (option E1)

`pollUntil`'s default `isTransient = { true }` and `createPollUntilRetryConfig`'s default retry every exception until the deadline, including a 403. The four Kubernetes polls (`DefaultK8sNamespaceOperations` rollout status, `StressJobService`, `BackendWorkloads`, `TelemetrySenders`) pass `isTransient = { !it.isKubernetesAuthRejection() }`. `RetryUtil` (in `providers/aws`) stays free of Kubernetes imports. Alternatives: **E2** change the shared default (Kubernetes import in `providers/aws`, changes AWS polls); **E3** spec only (leaves the slow failure).

## Risks / Trade-offs

- **Message drift across server versions and distributions** (EKS and GKE append text). Mitigated by parsing only stable prefixes, falling back to the request URI, and K3s TestContainer tests against a real API server.
- **Over-claiming.** A webhook "does not support dry run" 400 or a code-0 transport wrapper must not be claimed; both are scenarios.
- **Secrets.** Detail uses `status.message`, never the request URI query or a token. `PATH` is printed in full, as the issue asks.
- **Precedence.** Outermost cause first: an `IllegalStateException` that keeps the cause does not shadow the handler, because no handler claims it.

## Open Questions

- **F. Controller-created Pods.** In enforce mode Pod Security rejects only Pods. A Deployment, StatefulSet or Job is accepted and its controller's Pod creation fails (`ReplicaFailure` on the ReplicaSet, `FailedCreate` on a Job); the CLI gets no exception and the rollout wait times out. This is probably the most common way AC2 appears on a user's cluster. Proposed: a follow-up issue that has `RolloutBlocker` read those conditions through the same admission parser. Alternative: include it in this change. Pending owner decision.
- Confirm that naming the API-server principal (not the kubeconfig user name) satisfies "the user from the kubeconfig" in the issue scope.
