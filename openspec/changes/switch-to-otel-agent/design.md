## Context

The CLI tool's `observability/` package currently contains a manually initialized OpenTelemetry SDK: `OtelTelemetryProvider` calls `AutoConfiguredOpenTelemetrySdk.initialize()`, installs the Logback appender bridge, and attaches instrumentation interceptors for AWS SDK and OkHttp. `OtelResourceBuilder` merges default resource attributes with environment overrides. `NoOpTelemetryProvider` handles the case where `OTEL_EXPORTER_OTLP_ENDPOINT` is not set.

The CLI is built into a container image via Jib (`ghcr.io/rustyrazorblade/easy-db-lab`). The container does not currently include the OTel Java agent JAR.

## Goals / Non-Goals

**Goals:**
- Replace manual SDK initialization with the OTel Java agent
- Bundle the agent JAR in the Jib container image
- Reduce the OTel classpath to `opentelemetry-api` only, eliminating version conflict risk
- Preserve all existing telemetry behavior from the user's perspective (same env vars, same exported signals)

**Non-Goals:**
- Changing which signals are collected or which instrumentation libraries are active
- Modifying the OTel Collector K8s manifest or configuration
- Altering how EMR Spark or Cassandra sidecar use the OTel agent (those are separate deployment paths)
- Supporting agent configuration beyond what env vars already provide

## Decisions

### 1. Agent delivery via Jib extra directory

**Choice:** Download the agent JAR during the Gradle build and include it in the Jib container image at `/app/otel/opentelemetry-javaagent.jar`.

A new `otelAgent` Gradle configuration (non-transitive) holds the `io.opentelemetry.javaagent:opentelemetry-javaagent:<version>` dependency. A `copyOtelAgent` task copies the JAR to `build/otel/opentelemetry-javaagent.jar`. Jib's `extraDirectories` maps this file into the image. Jib task dependencies (`jib`, `jibDockerBuild`, `jibBuildTar`) declare `dependsOn(copyOtelAgent)`.

**Agent activation:** `-javaagent:/app/otel/opentelemetry-javaagent.jar` is added to Jib's `jvmFlags` list.

**Alternatives considered:**
- *Download at container startup* — adds startup latency, requires `curl` in the image, fails if GitHub releases are unavailable at runtime.
- *Multi-stage Docker build* — more complex than using Jib's built-in extra directory support.

**Agent version:** Align with the existing `opentelemetry-instrumentation` version already in `libs.versions.toml` (`2.11.0-alpha` → `2.11.0` stable agent). Record in `libs.versions.toml` as `opentelemetry-agent = "2.11.0"`.

### 2. `OtelTelemetryProvider` simplification

**Choice:** Replace the body of `OtelTelemetryProvider` with delegation to `GlobalOpenTelemetry.get()`.

The agent populates `GlobalOpenTelemetry` at JVM startup via the `javaagent` premain hook, before `main()` runs. Reading `GlobalOpenTelemetry.get()` in the constructor (or lazily) gives the agent-initialized SDK. No explicit initialization, shutdown, or bridge installation is needed.

```kotlin
class OtelTelemetryProvider : TelemetryProvider {
    private val otel = GlobalOpenTelemetry.get()
    private val tracer = otel.getTracer(TelemetryNames.SERVICE_NAME)
    private val meter = otel.getMeter(TelemetryNames.SERVICE_NAME)
    // ... delegate withSpan, recordDuration, incrementCounter to tracer/meter
}
```

### 3. Remove `NoOpTelemetryProvider`

**Choice:** Delete the class. When the agent is loaded without an OTLP endpoint configured (`OTEL_EXPORTER_OTLP_ENDPOINT` unset), `GlobalOpenTelemetry.get()` returns a no-op SDK automatically. No conditional logic is needed.

`TelemetryFactory` is simplified to always instantiate `OtelTelemetryProvider`.

### 4. Remove manual AWS SDK and OkHttp wiring

**Choice:** Remove the explicit `AwsSdkTelemetry` and `OkHttpTelemetry` interceptors from `AWSClientFactory` and `AWSModule`. The agent instruments these libraries via bytecode transformation with no application-side code.

### 5. Classpath dependency reduction

**Choice:** In `libs.versions.toml`, retain only `opentelemetry-api` in the application bundle. Remove `opentelemetry-sdk`, `opentelemetry-sdk-extension-autoconfigure`, `opentelemetry-exporter-otlp`, `opentelemetry-instrumentation-logback`, `opentelemetry-aws-sdk`, and `opentelemetry-okhttp` from the application classpath. These are bundled inside the agent JAR with shaded package names to avoid conflicts.

`opentelemetry-semconv` and `opentelemetry-semconv-incubating` are used only for attribute key constants in `TelemetryNames.kt`. Verify whether they are still needed; if only string literals are used, remove them too.

## Risks / Trade-offs

- **[Agent not present in dev/test]** When running from Gradle (`./gradlew run`) without building a container, the agent JAR is not on the classpath and `GlobalOpenTelemetry.get()` returns a no-op. This is the correct behavior — developers run without OTLP export unless they explicitly configure it.

- **[Startup time]** The agent adds ~100–300ms to JVM startup for bytecode scanning. The CLI already takes several seconds to start due to K8s/AWS client initialization, so this is negligible.

- **[Version alignment]** The agent version must be treated as a first-class dependency. When upgrading OTel API, the agent version should be upgraded together. Document this in `libs.versions.toml` comments.

## Open Questions

None — the approach is well-established for JVM applications.
