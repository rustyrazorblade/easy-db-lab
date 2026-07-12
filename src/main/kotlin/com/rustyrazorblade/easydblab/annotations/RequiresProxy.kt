package com.rustyrazorblade.easydblab.annotations

/**
 * Marks a command whose execution path reaches the private Kubernetes API (Fabric8, via
 * `K8sClientProvider` or the equivalent `KubernetesClientFactory` used by kit status checks)
 * or a private cluster HTTP endpoint (via `ProxiedHttpClientFactory` / `HttpClientFactory`,
 * e.g. Grafana, VictoriaMetrics, VictoriaLogs on the control node's private IP).
 *
 * `DefaultCommandExecutor.checkRequirements()` starts the SOCKS5 tunnel before executing any
 * command carrying this annotation, when the cluster is provisioned, infrastructure is UP, and
 * Tailscale is not active. Commands that never touch the private cluster network must NOT carry
 * this annotation — doing so would start a tunnel nothing needs.
 *
 * This is opt-in by design. A command that omits the annotation but does reach the private
 * cluster network degrades to a confusing connection error — the same behaviour it had before
 * the annotation existed — rather than to a new failure mode. That benign failure mode is why
 * opt-in was chosen over an opt-out annotation, where a missed annotation would instead break a
 * working command.
 *
 * There is deliberately no test enforcing that this annotation is applied. Any such test would
 * either hand-maintain a list of the services considered to reach the cluster network, or
 * over-approximate reachability from bytecode and require a hand-maintained suppression list
 * for its false positives. Both are lists masquerading as checks: they pass while silently
 * covering less over time. Apply this annotation by reading the command's execution path.
 *
 * @property tolerateFailure When `true`, a proxy establishment failure is recorded on
 * [com.rustyrazorblade.easydblab.proxy.ProxyAvailability] instead of aborting the command — the
 * command must then query that holder itself to render a degraded result. This is the single,
 * narrow mechanism for the one specified exception to the fail-fast invariant (see design
 * decision D9 in `openspec/changes/up-fail-fast/design.md`): `status` is read-only and is the
 * command reached for when the cluster is already broken, so it degrades rather than aborting.
 * Defaults to `false` — every other command fails fast, and setting this to `true` must be a
 * deliberate, visible choice at the annotation site. No command that mutates state may set it.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresProxy(
    val tolerateFailure: Boolean = false,
)
