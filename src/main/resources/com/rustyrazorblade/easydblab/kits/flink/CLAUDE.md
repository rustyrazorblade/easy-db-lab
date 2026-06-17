# Flink Kit

Notes for developers maintaining the flink kit.

## Deployment model — Operator + session cluster

This kit installs the **Flink Kubernetes Operator** (helm) at `kit install`, then deploys a
`FlinkDeployment` custom resource in **session mode** at `start`. A session cluster is a
long-lived JobManager + TaskManagers into which users submit their own jobs — it is NOT an
application cluster (no `job:` block in the CR). This mirrors the operator+CR pattern used by
the clickhouse kit.

## webhook.create=false — no cert-manager

The operator's admission webhook normally requires cert-manager. We install with
`webhook.create: "false"` (see `kit.yaml`), which drops that dependency entirely. The CRDs and
reconciliation still work; only the validating webhook is skipped. Do not add cert-manager.

## flinkVersion is pinned, not derived

The `--version` arg sets the image tag (`flink:1.20`), but the CR's `flinkVersion` field is an
enum (`v1_20`, `v2_0`, …) that template substitution cannot derive from the tag. It is
hardcoded to `v1_20` in `flinkdeployment.yaml.template` to match the default `--version`. If
you bump the default minor, update `flinkVersion` in the template in lockstep.

## Prometheus reporter is pre-staged, not copied

The official Flink image already ships the `flink-metrics-prometheus` reporter as a plugin at
`/opt/flink/plugins/metrics-prometheus/`, which Flink's plugin manager auto-loads. The kit only
sets the reporter config (`metrics.reporter.prom.factory.class` + port) in `flinkConfiguration`
— no initContainer, no volume, no jar copying. The reporter binds `:9249` on every JobManager
and TaskManager pod.

Do NOT add an `emptyDir` mounted at `/opt/flink/plugins` to stage the jar — it overlays and
hides the pre-staged plugin. (An earlier version did exactly this and the JobManager crash-
looped: the assumed jar path under `/opt/flink/opt` did not exist, and the overlay masked the
real plugin dir.)

## Metrics scrape topology

The Prometheus reporter binds **hostPort 9249** so the OTel DaemonSet (hostNetwork) can scrape
it on each app node. Because both JobManager and TaskManager expose 9249, two Flink pods on the
same node would collide on that hostPort, so the podTemplate uses `podAntiAffinity` to spread
them one-per-node. Validated on a live cluster: with `--taskmanagers 1` on a 2-app-node cluster
the JM and TM landed on separate nodes and both scraped cleanly.

The caveat is capacity: if `--taskmanagers` ≥ app-node count, a JM and a TM must share a node
and one will fail to bind 9249. Keep TaskManager replicas below the app-node count, or (if a
denser layout is ever needed) move to distinct hostPorts per role via separate
`jobManager.podTemplate` / `taskManager.podTemplate`.

## stop cancels running jobs first

In session mode, jobs submitted into the cluster are not owned by the `FlinkDeployment`, and the
operator refuses to delete a session cluster that still has non-terminated jobs ("...should be
cancelled first"). Deleting the CR directly therefore hangs indefinitely. The `stop` phase runs
a shell step that cancels every RUNNING job via the JobManager REST API
(`PATCH /jobs/<id>?mode=cancel`) before the `delete` steps. Validated: without it, `flink stop`
blocks for minutes when a job is running.

## Web UI exposed via NodePort

The operator creates a ClusterIP `flink-session-rest` service on 8081. We add our own NodePort
service `flink-session-ui` (8081 → nodePort 30081) selecting `app=flink-session,
component=jobmanager` so the UI/REST API is reachable from the app node IP. It is deleted in
`stop` alongside the FlinkDeployment.

## Profiling

No per-kit Pyroscope Java-agent wiring. The operator reconciles its pods, so a `kubectl patch`
to inject `JAVA_TOOL_OPTIONS` (the pattern other JVM kits use) would be reverted — JVM opts
would have to live in the CR's `flinkConfiguration`. System-level eBPF profiling already covers
Flink with no setup, so the Java agent is deferred.
