## Context

`bin/build-cassandra-analytics` clones cassandra-analytics and builds it locally, publishing artifacts to `~/.m2`. The clone destination is always `.cassandra-analytics/` in the project root (gitignored). The repo URL is currently hardcoded in the script as `https://github.com/apache/cassandra-analytics.git` with a default branch of `trunk`.

`spark/build.gradle.kts` reads the built version from `.cassandra-analytics/gradle.properties` and references both Maven-published artifacts and local build output JARs — all under `.cassandra-analytics/`. The Gradle build doesn't care which repo was cloned; it only cares that the build artifacts are present.

## Goals / Non-Goals

**Goals:**
- Make the cassandra-analytics source repo and branch configurable via a committed properties file
- Allow CLI flag overrides (`--repo`, `--branch`) for one-off builds without touching the committed config
- Print the resolved repo/branch at build time for visibility
- Work correctly from both the devcontainer (`bin/dev build-analytics`) and directly (`bin/build-cassandra-analytics`)

**Non-Goals:**
- Supporting multiple simultaneous clone directories for different forks
- Adding repo/branch config to Gradle (the Gradle build doesn't need to know the source)
- Changing how `spark/build.gradle.kts` resolves artifacts

## Decisions

### Decision: Properties file over Gradle properties or env vars

A committed `spark/cassandra-analytics-source.properties` file is the single source of truth for which repo and branch to use. This makes the intent explicit and visible in git history (e.g., "switch to fork", "switch back to upstream"). Env vars were rejected because they're invisible and easy to forget; Gradle properties were rejected because the build itself doesn't need this information — only the shell script does.

Format:
```properties
repo=https://github.com/apache/cassandra-analytics.git
branch=trunk
```

### Decision: CLI flags override the file, not vice versa

`--repo` and `--branch` flags take precedence over the properties file. This allows one-off builds against a different source without editing the committed config. The existing `--force` flag behavior is unchanged.

### Decision: No change to `spark/build.gradle.kts`

The Gradle build resolves artifacts from `.cassandra-analytics/` regardless of which repo was cloned there. Since the clone destination is fixed, Gradle doesn't need to know the source. This keeps the change entirely within the shell script and the new properties file.

### Decision: Print resolved repo/branch early

The script prints the resolved repo and branch before any git operations, so it's obvious which fork/branch is in use if a build fails or produces unexpected results.

## Risks / Trade-offs

- **External fork dependency in CI**: When the properties file points to a personal fork, CI is dependent on that fork being accessible. If the fork goes private or is deleted, CI breaks. → Mitigation: Switch the config back to upstream once the upstream PR merges. The two-commit round-trip is acceptable for a personal tool.

- **Stale `.cassandra-analytics/` clone**: If the properties file is updated to point to a different repo, but `.cassandra-analytics/` already exists from a prior clone, the script's update path (`git fetch && git checkout`) will silently continue using the old remote. → Mitigation: Document that switching to a different repo requires `--force` to re-clone. The script should also detect a remote mismatch and warn.
