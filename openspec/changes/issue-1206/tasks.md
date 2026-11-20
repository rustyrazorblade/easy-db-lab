## 1. Failing test first

- [ ] 1.1 Add `EbpfOutageReplayIntegrationTest` (`src/integrationTest/.../configuration/pyroscope/`): the pinned Alloy with the rendered `config.alloy` (privileged, host PID, as the DaemonSet runs it) and the pinned Pyroscope with its rendered `config.yaml` (`ObservabilityBackends.startPyroscope`); a busy process for the profiler to sample; stop Pyroscope for 60 s while the profiler runs, start it again, and assert that every 15-second window of the outage holds `process_cpu` samples for the cluster's tenant
- [ ] 1.2 Run it against the current config (Alloy straight to Pyroscope) and record that it fails, naming the windows that are empty
- [ ] 1.3 Add the old-profile test: push one profile stamped 70 minutes ago to the pinned Pyroscope with its rendered config, and assert it is accepted; record that it fails at the default `reject_older_than` (1h)

## 2. Profile relay module

- [ ] 2.1 New module `profile-relay` (`settings.gradle`, `build.gradle.kts` like `compactor-lease`: kotlin jvm, shadow, ktlint, detekt), no dependency on the root module and no runtime dependency beyond the Kotlin standard library; the shadow jar is minimized and the build fails when it passes `MAX_JAR_BYTES` (900 KiB)
- [ ] 2.2 Receiver: `HttpServer` on `127.0.0.1:<port>`; `POST /push.v1.PusherService/Push` queues the body with its `X-Scope-OrgID` and `Content-Type` and answers 200 with an empty `application/proto` body; every other path answers 404
- [ ] 2.3 Queue: in memory, bounded by bytes (a percentage of the max heap); a push that would overfill it drops the oldest pushes and prints the count and age dropped
- [ ] 2.4 Forwarder: a few senders POST each push unchanged to `<upstream>/push.v1.PusherService/Push` with a 30 s request timeout; keep and retry at a fixed 5 s with no limit on a connection failure, timeout, 5xx, 408, 429, or 422 naming "service is unavailable" or "empty ring"; drop any other 4xx and print the status, the tenant and the start of the body
- [ ] 2.5 Output: start line (port, upstream, queue bound), one line per drop, a once-a-minute line while anything is queued (count, bytes, age of the head); SIGTERM stops accepting and keeps sending for up to 25 s
- [ ] 2.6 Unit tests over a fake upstream: each retried status is retried until it succeeds and arrives once, in order; a 400 and a 422 parse error are dropped and the next push is sent; a full queue drops the oldest; the tenant and content type arrive unchanged; the receiver answers at once while the upstream is down
- [ ] 2.7 Package `agents/profile-relay.jar` in the distribution and `/agents` in the Jib image, next to `compactor-lease.jar`; a `ProfileRelayJarFile` finds it as `CompactorAgentJarFile` does

## 3. DaemonSet and config

- [ ] 3.1 `Constants.ProfileRelay` (port 4041, senders, queue heap percent, max jar bytes) and `Constants.Jre.IMAGE = "eclipse-temurin:21.0.13_9-jre"`; the compactor lease container uses `Constants.Jre.IMAGE` and `Constants.Compactor.AGENT_IMAGE` goes
- [ ] 3.2 `PyroscopeManifestBuilder`: the relay jar in the `pyroscope-ebpf-config` ConfigMap's `binaryData`; the relay as a native sidecar (init container, `restartPolicy: Always`) on `Constants.Jre.IMAGE` running `java -XX:MaxRAMPercentage=10 -jar`, its upstream from today's write URL (local or redirect), no writable volume; Alloy's `PYROSCOPE_WRITE_URL` becomes `http://127.0.0.1:4041`
- [ ] 3.3 `ConfigHashAnnotator` hashes a ConfigMap's `binaryData` as well as its `data`, with a test that a changed jar changes the DaemonSet's hash
- [ ] 3.4 `config.yaml`: `limits.reject_older_than: 2h`, the reason in the comment
- [ ] 3.5 Reserve 4041 in `node-tuning/60-easydblab.conf`; `NodeTuningReservedPortsTest` passes
- [ ] 3.6 Update `PyroscopeManifestBuilderTest` (relay container, image, upstream local and redirect, write URL, no writable volume, `reject_older_than`), the `pyroscope.yaml` and `compactor` golden manifests, and `K8sServiceIntegrationTest` (the DaemonSet with the relay applies to K3s)

## 4. Green

- [ ] 4.1 Tests 1.1 and 1.3 pass with the relay and the new `config.yaml`
- [ ] 4.2 `./gradlew test`, `./gradlew integrationTest` and `detekt` pass (on JDK 21), with no `@Suppress`, threshold or baseline change

## 5. Docs

- [ ] 5.1 `docs/user-guide/profiling.md`: replace the "no queue" paragraph with the eBPF cover (held in memory by the relay for up to 1 hour, what a relay restart loses, the measured memory for an hour on a loaded node, the external Pyroscope's own `reject_older_than` on redirect), add the relay to the data-flow diagram, and add `reject_older_than` to the Java-agent outage paragraph
- [ ] 5.2 `docs/user-guide/monitoring.md` versions line: the JRE image
- [ ] 5.3 The `observability-stack` skill's backend-outage paragraph, `configuration/CLAUDE.md` (the Alloy DaemonSet entry, `ConfigHashAnnotator`), and the root `CLAUDE.md` module list (`profile-relay`)

## 6. Real cluster

- [ ] 6.1 Build with `./gradlew installDist`, then `init` 1 database node, 1 app node and 1 control node with `--tag embark_offhours=off` on the default profile, and `up`
- [ ] 6.2 Run load on the database node, stop the Pyroscope server for 10 minutes (scale to 0), start it, and confirm in Grafana that the database node's `process_cpu` profiles cover the whole outage; record the relay's queued bytes for those 10 minutes and from them an hour's estimate for the docs (5.1)
- [ ] 6.3 Roll the `pyroscope-ebpf` DaemonSet and confirm no window around the rollout is empty
- [ ] 6.4 `down`

## 7. Close

- [ ] 7.1 roborev on the branch, and fix its findings
- [ ] 7.2 Archive this change in the implementation PR (`openspec archive issue-1206`), so its deltas reach `observability-store` and `telemetry-redirect`
