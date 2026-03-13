## 1. Build — Agent JAR in Jib Container

- [ ] 1.1 Add `opentelemetry-agent = "2.11.0"` version to `libs.versions.toml`. Add `opentelemetry-javaagent` library entry pointing to `io.opentelemetry.javaagent:opentelemetry-javaagent` using this version.
- [ ] 1.2 Add `otelAgent` Gradle configuration in `build.gradle.kts` (non-transitive). Add the `opentelemetry-javaagent` dependency to this configuration.
- [ ] 1.3 Add `copyOtelAgent` Gradle task in `build.gradle.kts` that copies the agent JAR from the `otelAgent` configuration into `build/otel/opentelemetry-javaagent.jar`.
- [ ] 1.4 Add `build/otel/` as an extra directory in the Jib config, mapping into `/app/otel/` in the container image.
- [ ] 1.5 Add `-javaagent:/app/otel/opentelemetry-javaagent.jar` to Jib `jvmFlags`.
- [ ] 1.6 Make `jib`, `jibDockerBuild`, and `jibBuildTar` tasks depend on `copyOtelAgent`.
- [ ] 1.7 Verify `./gradlew copyOtelAgent` downloads the agent JAR and places it at `build/otel/opentelemetry-javaagent.jar`.

## 2. Application Code — Remove Manual SDK Initialization

- [ ] 2.1 Rewrite `OtelTelemetryProvider.kt` to delegate to `GlobalOpenTelemetry.get()` for tracer and meter. Remove `AutoConfiguredOpenTelemetrySdk`, Logback bridge installation, resource building, and explicit shutdown. Retain `withSpan`, `recordDuration`, `incrementCounter`, `isEnabled`, and `shutdown` method signatures from `TelemetryProvider`.
- [ ] 2.2 Delete `OtelResourceBuilder.kt`.
- [ ] 2.3 Delete `NoOpTelemetryProvider.kt`.
- [ ] 2.4 Simplify `TelemetryFactory.kt` to always instantiate `OtelTelemetryProvider`. Remove the conditional check for `OTEL_EXPORTER_OTLP_ENDPOINT`.
- [ ] 2.5 Remove manual AWS SDK telemetry interceptors from `AWSClientFactory.kt` (remove `AwsSdkTelemetry` usage).
- [ ] 2.6 Remove manual OkHttp telemetry interceptors from `AWSModule.kt` (remove `OkHttpTelemetry` usage).

## 3. Dependencies — Trim Classpath

- [ ] 3.1 In `libs.versions.toml`, remove from the `opentelemetry` bundle: `opentelemetry-sdk`, `opentelemetry-sdk-extension-autoconfigure`, `opentelemetry-exporter-otlp`, `opentelemetry-instrumentation-logback`, `opentelemetry-aws-sdk`, `opentelemetry-okhttp`. Keep only `opentelemetry-api`.
- [ ] 3.2 Check whether `opentelemetry-semconv` and `opentelemetry-semconv-incubating` are still needed (used for attribute key constants in `TelemetryNames.kt`). Remove if unused after the previous step.
- [ ] 3.3 Run `./gradlew compileKotlin` and resolve any missing import errors from removed dependencies.

## 4. Tests — Remove Deleted Class Tests

- [ ] 4.1 Delete `OtelResourceBuilderTest.kt` (the class it tests is removed).
- [ ] 4.2 Review any tests that reference `NoOpTelemetryProvider` or the conditional `TelemetryFactory` logic and remove or update them.
- [ ] 4.3 Run `./gradlew test` and verify all tests pass.

## 5. Documentation

- [ ] 5.1 Update `docs/reference/opentelemetry.md` to describe agent-based instrumentation: how the agent is bundled in the container, that manual SDK wiring is no longer present, and that configuration is via standard OTel env vars (unchanged).
- [ ] 5.2 Update root `CLAUDE.md` Observability section if it references the manual SDK initialization pattern.

## 6. Verify

- [ ] 6.1 Run `./gradlew ktlintFormat` and `./gradlew ktlintCheck`.
- [ ] 6.2 Run `./gradlew detekt`.
- [ ] 6.3 Run `./gradlew compileKotlin`.
- [ ] 6.4 Run `./gradlew test`.
