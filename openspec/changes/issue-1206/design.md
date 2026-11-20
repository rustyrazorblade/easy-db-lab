## Context

Every node runs the `pyroscope-ebpf` DaemonSet: one Grafana Alloy v1.20.0 container (`grafana/alloy:v1.20.0`, host network, host PID, privileged) whose `config.alloy` runs `pyroscope.ebpf` (`collect_interval` 15s, `sample_rate` 97) and forwards to `pyroscope.write`, which pushes to `PYROSCOPE_WRITE_URL` (the control node's Pyroscope on port 4040, or the redirect URL) with `X-Scope-OrgID` set to the cluster's tenant.  It is the only profiler of every non-JVM process: ClickHouse, TiKV, PD, FerrosaDB and the rest.

Issue #991 made every other sender wait out a backend outage in memory, for up to 1 hour.  It recorded that the eBPF profiler loses what it cannot send within about 15 seconds and left that to this issue.

### How Alloy sends today, and why no setting fixes it

Read from the Alloy source at the tags named.

- `internal/component/pyroscope/ebpf/send.go`, `sendProfiles` ([v1.20.0](https://github.com/grafana/alloy/blob/v1.20.0/internal/component/pyroscope/ebpf/send.go), unchanged at [v1.22.0](https://github.com/grafana/alloy/blob/v1.22.0/internal/component/pyroscope/ebpf/send.go) and on `main`): every collect's profiles are sent by a pool of up to 32 workers under `ctx, cancel := context.WithTimeout(ctx, c.args.CollectInterval)`.  When the deadline passes, the jobs not yet run are counted in `pprofsDroppedTotal` and the ones running see a cancelled context.  There is no queue between collects.
- `internal/component/pyroscope/write/write.go` (v1.20.0 and v1.22.0): each endpoint has `remote_timeout` (10s), `min_backoff_period` (500ms), `max_backoff_period` (5m), `max_backoff_retries` (10, 0 means no limit) and `retry_on_http_429` (true).  The dskit backoff runs on the caller's context, so the 15-second eBPF deadline ends the retries whatever these say.  `shouldRetry` retries a deadline, 408, 429 and 5xx; any other 4xx is final.
- The push is the Connect unary RPC `push.v1.PusherService/Push` (`pushv1connect.NewPusherServiceClient`), a `POST /push.v1.PusherService/Push` with an `application/proto` body, over HTTP/1.1 unless the endpoint sets h2c.
- No release from v1.20.1 to [v1.22.0](https://github.com/grafana/alloy/releases/tag/v1.22.0) (2026-11-13, the newest) notes a queue or a WAL for `pyroscope.write`, and no open Alloy issue asks for one.  v1.22.0 moves the eBPF profiler fork to v0.0.202640 and the embedded collector to v0.164.1; neither touches the send path.
- Alloy's `otelcol.*` components carry only metrics, logs and traces (the `otelcol.exporter.otlp` reference: "metrics, logs, or traces"), and `pyroscope.ebpf`'s `forward_to` takes only `ProfilesReceiver`, so Alloy cannot hand eBPF profiles to an OTel profiles pipeline either.

### The OpenTelemetry eBPF profiler route

Read from the collector, the profiler and Pyroscope at the tags named.

- The [`otelcol-ebpf-profiler`](https://github.com/open-telemetry/opentelemetry-collector-releases/tree/v0.161.0/distributions/otelcol-ebpf-profiler) distribution, image `otel/opentelemetry-collector-ebpf-profiler:0.161.0` (0.165.0, 2026-11-07, is the newest), bundles the `profiling` receiver (`go.opentelemetry.io/ebpf-profiler` v0.0.202636, stability development; v0.0.202645 in 0.165.0), the `otlp` and `otlphttp` exporters, and the `k8sattributes`, `resource`, `transform` and `batch` processors.
- Profiles need the collector feature gate `service.profilesSupport`, which is Alpha (off by default) in 0.161.0 and 0.165.0: `--feature-gates=+service.profilesSupport`.
- The `otlp` exporter builds its profiles exporter with `WithTimeout`, `WithRetry` and `WithQueue` (`exporter/otlpexporter/factory.go`, v0.161.0), so `sending_queue` and `retry_on_failure.max_elapsed_time: 0` work for profiles as they do for traces.
- Pyroscope 2.3.1 accepts OTLP profiles on gRPC `opentelemetry.proto.collector.profiles.v1development.ProfilesService/Export` and on HTTP `/v1development/profiles`, port 4040 (`pkg/ingester/otlp/ingest_handler.go`, `pkg/api/api.go`).  Its proto is `go.opentelemetry.io/proto/otlp` profiles `v1development` v0.3.0 (OTLP proto 1.10.0); collector 0.161.0 sends v0.4.0 (OTLP proto 1.11.0).  The 1.10.0 to 1.11.0 diff of `profiles.proto`, `profiles_service.proto` and `common.proto` changes comments only, so the two agree on the wire today; every collector bump must be checked again.
- The upstream profiler does not symbolize native frames on the host.  `processmanager/manager.go` sends "non-symbolized frames if no native symbolizer is active", and symbolization is open upstream ([#928](https://github.com/open-telemetry/opentelemetry-ebpf-profiler/issues/928), [#435](https://github.com/open-telemetry/opentelemetry-ebpf-profiler/issues/435)).  Alloy's fork does symbolize them on the host (`reporter/pprof.go`, `symb/irsymcache`; `OnTargetSymbolizationEnabled: true` by default in `pyroscope.ebpf`).  Pyroscope 2.3.1's own symbolizer is experimental, off by default (`symbolizer.enabled`), runs in the query frontend, and fetches debug info from debuginfod or an upload store; stripped database binaries have none there.
- Grafana's own [guide](https://github.com/grafana/pyroscope/blob/v2.4.3/docs/sources/configure-client/opentelemetry/ebpf-profiler.md) (Pyroscope v2.4.3) calls this route "suitable for development and testing".

### What limits every profile sender

Pyroscope rejects a profile older than `limits.reject_older_than`, 1 hour by default (`pkg/validation/limits.go` v2.3.1, `validation.reject-older-than`), checked in the distributor against the profile's own time.  Alloy stamps a profile when it collects it, and the Java agent stamps a snapshot when it takes it.  The cluster's `config.yaml` does not set the limit.  So a sender that held a profile through a 1-hour outage still loses the oldest part of it: 4xx, final.  The #991 requirement claims 1-hour Java-agent cover and is wrong at its edge.

## Goals / Non-Goals

**Goals:**

- No eBPF profile is lost to a Pyroscope restart or outage of up to 1 hour, local or redirected, as #991 does for the other signals.
- Held in memory only.
- Alloy keeps doing the collecting and the native symbolization.
- Nothing in it is cloud-specific: it runs wherever the DaemonSet runs, an existing Kubernetes cluster included.
- The 1-hour Java-agent cover of #991 becomes true.

**Non-Goals:**

- Cover past 1 hour, or across a restart of the relay itself (its queue is in memory, as the collector's is).
- A WAL, a persistent queue, `file_storage`, or a `memory_limiter`: never.
- Bumping Alloy; v1.22.0 changes nothing here.
- Changing the external Pyroscope of a redirected cluster: its `reject_older_than` is its owner's setting.

## Decisions

### D1. A profile relay sidecar in the `pyroscope-ebpf` pod (chosen)

The relay is a small store-and-forward proxy for the one RPC Alloy sends.

- It listens on `127.0.0.1:4041` (`Constants.ProfileRelay.PORT`) on the node's network.  `config.alloy`'s `pyroscope.write` points at `http://127.0.0.1:4041`, and the relay's upstream is today's `PYROSCOPE_WRITE_URL` value (local or redirect).
- On `POST /push.v1.PusherService/Push` it reads the body, puts the body and the `X-Scope-OrgID` and `Content-Type` headers on its queue, and answers 200 with an empty `PushResponse` (zero bytes, `application/proto`) at once.  Alloy's 15-second deadline is never reached.  It refuses anything else with 404.
- Senders, a few at a time (`Constants.ProfileRelay.SENDERS`, 4), take pushes from the head of the queue in order and POST them unchanged to `<upstream>/push.v1.PusherService/Push`.
- A push is kept and sent again after a fixed 5 s (as the collector's metrics exporter, #991) when the upstream does not answer, times out (30 s), answers 5xx, 408 or 429, or answers 422 with a body naming a server that is shutting down or starting ("service is unavailable", "empty ring"), the same rule the JFR shipper follows.  There is no retry limit.
- Any other 4xx drops that push.  The relay prints one line naming the status, the tenant and the start of the body, so a malformed push cannot hold the queue.
- The queue is bounded by bytes: `Constants.ProfileRelay.QUEUE_HEAP_PERCENT` (80) of the JVM's max heap, which is `-XX:MaxRAMPercentage=10` of the node's memory.  When a push would overfill it, the oldest pushes are dropped and the relay prints how many and how old they were.  With no container limit, heap is committed only as the queue grows, so a healthy relay holds a few MB.
- Its output is the container's stdout: start, each drop, and once a minute while anything is queued, the queued count and bytes and the age of the head.
- On SIGTERM it stops accepting, keeps sending for up to 25 s of the pod's 30 s grace, and exits.
- It runs as a native sidecar (an init container with `restartPolicy: Always`, K3s v1.35.1 has it GA), so it is listening before Alloy starts and stops only after Alloy has exited: Alloy's last push of a rollout is queued, and a rollout loses only what was still queued when the grace ended.

Why: it is the only option that keeps both Alloy's host-side native symbolization and a queue with no time limit.  It holds its data in memory and needs no feature gate or proto pin.  It talks only to Pyroscope's own push API, so it works on any cluster.

### D2. The relay is a Kotlin module shipped in the ConfigMap

- New Gradle module `profile-relay`, with no dependency on the root module, like `compactor-lease`.  It uses the JDK's `com.sun.net.httpserver.HttpServer` and `java.net.http.HttpClient` and nothing beyond the Kotlin standard library.  The shadow jar is minimized and the build fails if it passes `Constants.ProfileRelay.MAX_JAR_BYTES` (900 KiB), so it always fits a ConfigMap.
- It ships as `agents/profile-relay.jar` in the distribution and in the Jib image's `/agents`, found as `CompactorAgentJarFile` finds its jar (`easydblab.agentsdir`, else `<apphome>/agents`).  A Homebrew install needs no checkout.
- `PyroscopeManifestBuilder` puts the jar in the `pyroscope-ebpf-config` ConfigMap's `binaryData`, and the relay container runs `java -XX:MaxRAMPercentage=10 -jar /etc/profile-relay/profile-relay.jar` from it.  No upload to every node, no AMI change, and a jar change reaches every node at the next `up` or `grafana update-config`.
- `ConfigHashAnnotator` today hashes only a ConfigMap's `data`.  It must hash `binaryData` too, or a new jar would not roll the DaemonSet.
- The image is `eclipse-temurin:21.0.13_9-jre` (multi-arch, amd64 and arm64), one constant `Constants.Jre.IMAGE` that the compactor's lease container uses too, replacing its unpinned `eclipse-temurin:21-jre`.

Why not upload per node: the compactor's upload is to one node; a jar on every node would need a per-node step in bring-up and on node replacement.  The ConfigMap reaches every node, the replaced ones included, with no new step.

### D3. Pyroscope accepts profiles up to 2 hours old

`config.yaml` sets `limits.reject_older_than: 2h`, so the oldest profile of a 1-hour outage, which is up to 1 hour plus its replay time old, is accepted.  This covers the relay's queue and the Java agent's 360-snapshot queue alike.  It is not retention and deletes nothing.

### D4. Alloy stays at v1.20.0

The research found nothing in v1.20.1 to v1.22.0 that this change needs.  A bump is its own change.

## Alternatives

- **B. The OpenTelemetry eBPF profiler (rejected).**  A DaemonSet of `otel/opentelemetry-collector-ebpf-profiler` sending OTLP profiles through `sending_queue` and unlimited retry to Pyroscope's OTLP ingest, or the node collector's own profiles pipeline.  It needs no new code, but:
  - it loses the function names of every native frame, which are the whole value for ClickHouse, TiKV and the other C++ and Rust databases, until upstream symbolization lands;
  - it runs on an Alpha feature gate and a development-stability receiver;
  - it ties each collector bump to Pyroscope's proto version;
  - it changes the labels the dashboards and docs use (`service_name`, `namespace`, `pod`, `container`), which `k8sattributes` and `transform`, both development-stability for profiles, would have to rebuild.
- **C. Wait for Alloy (rejected).**  Nothing upstream suggests a queue is coming.
- **D. Alloy's `pyroscope.receive_http` as a relay (rejected).**  It forwards on the request's context, so it is no queue.
- **E. A general proxy such as Vector (rejected).**  It has no Pyroscope push sink; its generic HTTP sink would need the same retry rules configured, and it is a large dependency for one RPC.

## Risks / Trade-offs

- [The relay's queue is lost if the relay restarts during an outage] → the same as the collector's (#991); its output says what it held.
- [Memory on a busy node during a long outage] → the queue is bounded by bytes and the heap by 10% of the node.  The real-cluster test measures an hour's bytes on a loaded database node and the docs state it.  If an hour does not fit, the oldest pushes go, and the relay says so.
- [A replay burst hits Pyroscope's ingestion rate (`ingestion_rate_mb` 16, burst 128)] → it answers 429, which the relay retries.
- [The integration test needs eBPF in Docker] → it runs privileged, as `K8sServiceIntegrationTest`'s K3s already does; a kernel that cannot run the profiler fails the test, and that failure is reported, not skipped.
- [A redirected cluster's external Pyroscope keeps its own `reject_older_than`] → the docs say that cover past its window depends on that setting.
- [Pyroscope changes the Push RPC] → the relay forwards bytes and headers unchanged; only the path and the status rules are its own, and the integration test runs against the pinned Pyroscope.
