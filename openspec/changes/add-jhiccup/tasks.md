# Tasks: add-jhiccup

- [x] Create `jvm-pause-agent/build.gradle.kts` Gradle subproject
- [x] Implement `JvmPauseAgent.kt` (premain method)
- [x] Implement `JvmPauseDetector.kt` (background measurement thread with OTel export)
- [x] Add `include "jvm-pause-agent"` to `settings.gradle`
- [x] Add shadow JAR to root distribution contents in `build.gradle.kts`
- [x] Update `cassandra.in.sh` to conditionally activate agent
- [x] Update `SetupInstance` to deploy agent JAR to Cassandra nodes
- [x] Add `JVM_PAUSE` entry to `GrafanaDashboard` enum
- [x] Create `jvm-pause.json` Grafana dashboard
- [ ] Add GitHub Actions publish workflow (skipped: no permission to modify `.github/workflows/`)
