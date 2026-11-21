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

**Non-goals:** Pod Security rejections of controller-created Pods (split off to follow-up #1228); credential refresh or rotation; any change to the AWS or Docker handlers' classification.

## Decisions

D1 to D6 were **approved by the owner on 2026-11-20**, as the options named in their headings (A2 for D1, B1, C1, D1 for D5, E1; D4 has no alternative). D7 and D8 record the owner's decisions of the same day on the principal and on the 403s that are not RBAC denials. The alternatives are kept for the record.

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

`ExecPluginCheck(path: String? = System.getenv("PATH")).check(kubeconfig, parsed, context)` takes the kubeconfig `loadConfig` parsed once for its context check (Fabric8 still loads the file itself, keeping its location) and resolves the plugin as Fabric8 does: a bare name on `PATH`, a name with a separator against the kubeconfig's directory; either is found only when it is a file with the execute bit. It uses the JVM's own `PATH`, because Fabric8's `sh -c` inherits it. It throws `ExecPluginNotFoundException(plugin, user, context, searchedPath, installHint)`, which the handler claims as `credentials`. `PATH` is a constructor parameter for tests. This is the only way to meet AC3, because Fabric8 raises nothing.

### D5. Keep the cause at every Kubernetes wrapping site (option D1)

All 13 cause-dropping sites keep the Fabric8 exception as the cause (the `GrafanaDeployService.kt:133` pattern). Without this, AC1 and AC2 fail on exactly the commands that hit RBAC on a user's cluster (`up` post steps, `grafana update-config`). `CapabilityProbeService.explain` is deleted; the handler's remedy states the cluster-admin requirement. Capability-Pod rejections stay probe notes. Trade-off: the outer "Failed to apply ..." text is no longer what the user sees; `request` names the resource instead. Alternative **D2**: fix only the your-cluster sites; cheaper, inconsistent output.

### D6. Polls pass a not-transient predicate for 401/403 (option E1)

`pollUntil`'s default `isTransient = { true }` and `createPollUntilRetryConfig`'s default retry every exception until the deadline, including a 403. The four Kubernetes polls (`DefaultK8sNamespaceOperations` rollout status, `StressJobService`, `BackendWorkloads`, `TelemetrySenders`) pass `isTransient = { !it.isPermanentKubernetesFailure() }`: a 401, a 403, or an `ExecPluginNotFoundException` anywhere in the chain. The kit stop wait (`KitWorkloadProbe.awaitGone`) passes it too. `RetryUtil` (in `providers/aws`) stays free of Kubernetes imports. Alternatives: **E2** change the shared default (Kubernetes import in `providers/aws`, changes AWS polls); **E3** spec only (leaves the slow failure).

### D7. The principal is the one the API server evaluated (owner decision, 2026-11-20)

The user a denial names is the principal in the API server's message (`alice`, `system:serviceaccount:lab:runner`), never the kubeconfig `users[].name`. That principal is the one a grant has to name, and the kubeconfig entry name is only a local label for it. This closes the question of whether it meets "the user from the kubeconfig" in the issue.

### D8. Only parsed denials are classified; any other 403 is the generic error (owner decision, 2026-11-20)

A 403 is not always RBAC, so the handler claims a 403 only when it can say what refused it:

- An RBAC denial is `permission-denied`, with the cluster-admin remedy. It is the only case that gets that remedy.
- A 403 for `system:anonymous` and every 401 are `credentials`.
- A namespace that is being deleted (`unable to create new content in namespace X because it is being terminated`) is `rejected`, policy `NamespaceLifecycle`. The remedy is to wait until the namespace is gone.
- A full ResourceQuota (`exceeded quota: Q, requested: ...`, or `failed quota: Q: ...`) is `rejected`, policy `ResourceQuota Q`. The remedy is to free some of the quota or have it raised.
- A LimitRange violation (`maximum cpu usage per Container is ...`) is `rejected`, policy `LimitRange`, with one violation per limit. The remedy is to fit the LimitRange.
- Pod Security, webhook and ValidatingAdmissionPolicy denials are `rejected` as before.
- Any other 403 (a gateway's page, an unknown message, no status) is not claimed. The user gets `Command.ExecutionError` with the server's message and no cluster-admin advice.

The request URI fallback (the HTTP method and the URI's resource, namespace and name from Fabric8's `Failure executing:` message) now fills `request` only for a 401 and for a classified rejection, whose message gives no verb.

### D9. A missing exec plugin is never retried and never swallowed

`ExecPluginNotFoundException` fails on every look, so the polls treat it as permanent (D6). The sites that turn a Kubernetes failure into a note keep its description, through `Throwable.kubernetesFailureReason()` (the handler's detail and remedy, or the plain reason for an unclaimed failure): the `status` pod and stress job sections (`KubernetesInfo.error`), `Kit.StopUnverified`, and `metrics stream`, which emits `Stress.JobListFailing` once per run of permanent list failures. A transient list failure there is still only logged. `CapabilityProbeService` and `ClientNetworkProbe` already let it reach the reporter.

## Risks / Trade-offs

- **Message drift across server versions and distributions** (EKS and GKE append text). Mitigated by parsing only stable prefixes, falling back to the request URI, and K3s TestContainer tests against a real API server.
- **Over-claiming.** A webhook "does not support dry run" 400 or a code-0 transport wrapper must not be claimed; both are scenarios.
- **Secrets.** Detail uses `status.message`, never the request URI's userinfo or query or a token; a URI in a message without a status is written without its userinfo and query. `PATH` is printed in full, as the issue asks.
- **Precedence.** Outermost cause first: an `IllegalStateException` that keeps the cause does not shadow the handler, because no handler claims it.

## Open Questions

None. F (Pod Security rejections of controller-created Pods) was split off to follow-up #1228. The principal question is closed by D7.
