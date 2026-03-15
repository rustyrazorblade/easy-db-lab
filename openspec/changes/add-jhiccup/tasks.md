## 1. Gradle Subproject: jvm-pause-agent

- [ ] 1.1 Add `include "jvm-pause-agent"` to `settings.gradle`
- [ ] 1.2 Create `jvm-pause-agent/build.gradle.kts` with:
  - `java-library` + `com.github.johnrengelman.shadow` plugins
  - Dependencies: `io.opentelemetry:opentelemetry-sdk`, `io.opentelemetry:opentelemetry-exporter-otlp`, `org.hdrhistogram:HdrHistogram`
  - Shadow JAR with package relocation for OTel classes (avoid classloader conflicts with Cassandra's OTel)
  - Manifest: `Premain-Class`, `Agent-Class`, `Can-Redefine-Classes: false`
  - Archive base name: `jvm-pause-agent`
- [ ] 1.3 Create `jvm-pause-agent/src/main/kotlin/.../JvmPauseDetectorAgent.kt` — `premain` entry point
- [ ] 1.4 Create `jvm-pause-agent/src/main/kotlin/.../JvmPauseDetector.kt` — background thread with HdrHistogram.Recorder
- [ ] 1.5 Create `jvm-pause-agent/src/main/kotlin/.../OtelReporter.kt` — OTel SDK setup and histogram reporting
- [ ] 1.6 Write unit tests for `JvmPauseDetector` (verify histogram recording, intervals, configurable resolution)

## 2. Distribution Integration

- [ ] 2.1 In root `build.gradle.kts` `distributions.main.contents` block, add the `jvm-pause-agent` shadow JAR into `lib/jvm-pause-agent/`
- [ ] 2.2 Ensure `installDist` depends on `:jvm-pause-agent:shadowJar` (dev mode picks up built artifact)
- [ ] 2.3 Verify the JAR is present in `build/install/easy-db-lab/lib/jvm-pause-agent/` after `./gradlew installDist`

## 3. Node Deployment

- [ ] 3.1 Update `SetupInstance` (or the relevant upload step) to copy the `jvm-pause-agent` JAR to `/usr/local/jvm-pause-agent/jvm-pause-agent.jar` on database nodes
- [ ] 3.2 Update `packer/cassandra/cassandra.in.sh` to conditionally append `-javaagent` flag:
  ```bash
  JHICCUP_JAR="/usr/local/jvm-pause-agent/jvm-pause-agent.jar"
  if [ -f "$JHICCUP_JAR" ]; then
      JVM_OPTS="$JVM_OPTS -javaagent:$JHICCUP_JAR"
  fi
  ```

## 4. Grafana Dashboard

- [ ] 4.1 Add `JVM_PAUSE` to `GrafanaDashboard` enum in `configuration/grafana/GrafanaDashboard.kt`
- [ ] 4.2 Create `configuration/grafana/dashboards/jvm-pause.json` — heatmap dashboard for `jvm_pause_duration_bucket`
- [ ] 4.3 Wire the dashboard into the Grafana manifest builder

## 5. GitHub Actions Publishing

- [ ] 5.1 Create `.github/workflows/publish-jvm-pause-agent.yml` — publishes the shadow JAR to GitHub Packages on tag push and main branch merges
- [ ] 5.2 Configure `jvm-pause-agent/build.gradle.kts` with `maven-publish` plugin, `groupId = "com.rustyrazorblade"`, `artifactId = "jvm-pause-agent"`, publishing to `https://maven.pkg.github.com/rustyrazorblade/easy-db-lab`

## 6. Testing

- [ ] 6.1 Run `./gradlew :jvm-pause-agent:test` to verify unit tests pass
- [ ] 6.2 Run `./gradlew installDist` and confirm JAR appears in `build/install/easy-db-lab/lib/jvm-pause-agent/`
- [ ] 6.3 Run `./gradlew check` to confirm no regressions in the main project

## 7. Documentation

- [ ] 7.1 Update `docs/reference/opentelemetry.md` — document `jvm.pause.duration` metric, bucket boundaries, and how to read the heatmap
- [ ] 7.2 Update CLAUDE.md observability section to mention the `jvm-pause-agent` subproject
