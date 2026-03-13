## Why

The CLI tool currently initializes OpenTelemetry manually: it calls `AutoConfiguredOpenTelemetrySdk`, installs a Logback OTLP appender bridge, wires AWS SDK and OkHttp interceptors, and builds a resource object with `OtelResourceBuilder`. This plumbing is ~400 lines of code that duplicates what the OTel Java agent provides automatically via bytecode instrumentation at JVM startup.

The manual SDK approach has practical downsides:
- SDK, exporter, and instrumentation library versions must be kept in sync on the classpath, creating dependency conflicts.
- Adding new framework support (new AWS SDK operations, new HTTP clients) requires code changes.
- The `NoOpTelemetryProvider` exists only to skip initialization overhead when OTel is not configured — the agent eliminates this need because it auto-detects whether an OTLP endpoint is configured.
- The container image does not include the agent JAR; it must be built and published via Jib.

The OTel Java agent is the standard production approach for JVM services. It handles SDK initialization, OTLP export, resource attribute detection, and bytecode-level instrumentation of AWS SDK, OkHttp, and Logback without any application code changes.

## What Changes

- **Remove manual OTel SDK initialization** — `OtelTelemetryProvider` is simplified to delegate to `GlobalOpenTelemetry.get()`, which the agent initializes at startup. `AutoConfiguredOpenTelemetrySdk`, `OtelResourceBuilder`, Logback appender bridge installation, and explicit SDK shutdown are all removed.
- **Delete `NoOpTelemetryProvider`** — when the agent is present but no OTLP endpoint is configured, `GlobalOpenTelemetry.get()` returns a no-op implementation automatically.
- **Simplify `TelemetryFactory`** — always creates `OtelTelemetryProvider`; the conditional no-op logic is no longer needed.
- **Remove manual AWS SDK and OkHttp interceptors** — the agent instruments these via bytecode transformation; explicit wiring in `AWSClientFactory` and `AWSModule` is removed.
- **Bundle the agent in the Jib container** — add a Gradle task to download the `opentelemetry-javaagent` JAR and include it at `/app/otel/opentelemetry-javaagent.jar` in the container image. Add `-javaagent:/app/otel/opentelemetry-javaagent.jar` to Jib JVM flags.
- **Trim classpath dependencies** — the `opentelemetry` bundle in `libs.versions.toml` is reduced to `opentelemetry-api` only; SDK, exporter, and instrumentation libraries are bundled inside the agent JAR and must not appear on the application classpath to avoid conflicts.
- **Update documentation** — `docs/reference/opentelemetry.md` updated to describe agent-based configuration.

## Capabilities

### Modified Capabilities

- `observability`: The CLI tool's traces, metrics, and logs are now exported by the OTel Java agent instead of a manually initialized SDK. Configuration (e.g., `OTEL_EXPORTER_OTLP_ENDPOINT`) is unchanged from the user's perspective.

## Impact

- **Deleted files**: `OtelResourceBuilder.kt`, `NoOpTelemetryProvider.kt`
- **Simplified files**: `OtelTelemetryProvider.kt`, `TelemetryFactory.kt`, `AWSClientFactory.kt`, `AWSModule.kt`
- **Build changes**: `build.gradle.kts` (new `otelAgent` configuration, `copyOtelAgent` task, Jib extra directory + JVM flag), `libs.versions.toml` (agent version constant, trimmed bundle)
- **Tests**: `OtelResourceBuilderTest.kt` deleted (class removed); any tests for `NoOpTelemetryProvider` or `TelemetryFactory` conditional logic removed or updated
- **Docs**: `docs/reference/opentelemetry.md` updated to describe agent-based instrumentation
- **No user-facing behavior change**: environment variables and exported telemetry are identical from the user's perspective
