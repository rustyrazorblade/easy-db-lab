## 1. OtelManifestBuilder — ClusterIP Service

- [x] 1.1 Add `buildService()` method to `OtelManifestBuilder` that creates a Kubernetes ClusterIP Service named `otel-collector` in the `default` namespace, selecting pods with label `app.kubernetes.io/name: otel-collector`, exposing port 4317 (gRPC)
- [x] 1.2 Update `OtelManifestBuilder` to return the Service alongside the DaemonSet wherever the DaemonSet is applied (verify how the DaemonSet is applied and ensure the Service is applied in the same call)

## 2. TiDB Kit — restore OTel endpoint

- [x] 2.1 Restore the `opentelemetry` config block in `kits/tidb/tidbcluster.yaml.template`, setting `endpoint` to `otel-collector.default.svc.cluster.local:4317`

## 3. Spec archive

- [x] 3.1 Update `openspec/specs/observability/spec.md` with the new ClusterIP Service requirement (merge from change delta)

## 4. Verification

- [x] 4.1 Run `./gradlew ktlintFormat && ./gradlew build` — confirm no compilation errors
- [x] 4.2 Verify `OtelManifestBuilder` unit test (if one exists) still passes, or add a test asserting the Service is built with the correct name, namespace, selector, and port
