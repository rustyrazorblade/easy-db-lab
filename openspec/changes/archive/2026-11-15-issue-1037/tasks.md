## 1. Capabilities

- [x] 1.1 Add `configuration/NodeCapability.kt` (`NodeCapability` with `absentReason`, serializable, plus `NodeCapabilities` with `AWS`, `LOCAL_DOCKER` and `from(ClusterState)`). Write a unit test for `from` returning `AWS`, and keep any environment-name test out of consumers (they ask `in`, never the environment).

## 2. Stage gating

- [x] 2.1 Add `Event.Grafana.StageSkipped` and update `events/CLAUDE.md`.
- [x] 2.2 Test first: `ObservabilityStackServiceTest` with `LOCAL_DOCKER`. fluent-bit-journald, YACE and s3manager are not applied and not awaited, and there is one `StageSkipped` per stage, with its capability and reason. With `AWS`, the applied set equals today's and no skip is emitted.
- [x] 2.3 Give `Stage` a `requires` field in `ObservabilityStackService.buildStages`, and gate the three stages.

## 3. Collector config

- [x] 3.1 Test first: the rendered collector config has a `yace` job only with `CLOUDWATCH`, and the deploy path and the `OtelSyncService` path render the same config.
- [x] 3.2 Move the `yace` job from `otel-collector-config.yaml` into an `OtelManifestBuilder` function, and pass the capabilities through `CollectorResources.build`.

## 4. Status

- [x] 4.1 Test first: without `S3`, the `status` output and the `StatusCache` result have no S3 Manager section. With it, they are unchanged.
- [x] 4.2 Gate `Status.kt:529` and `StatusCache.kt:485`.

## 5. Pod logs without a journal

- [x] 5.1 Integration test (`src/integrationTest/`, K3s TestContainer, which has no journal): deploy the collector and Loki, run a pod that prints a marker line, and assert that Loki returns it.

## 6. Docs and verify

- [x] 6.1 Update `configuration/CLAUDE.md` and the root `CLAUDE.md` observability section: list the gated workloads and the capability each needs.
- [x] 6.2 Run `./gradlew check` on JDK 21 in a subagent, with no new `@Suppress`, threshold change or baseline entry.
