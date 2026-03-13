## Context

The project is a multi-module Gradle build: a root CLI module, a `core` shared library, and several `spark/*` modules. Version is an integer (currently `12`) defined in `gradle.properties`. The project already publishes a Docker container to GitHub Container Registry via `publish-container.yml`. No Maven publication is currently configured.

GitHub Packages is the target Maven repository. It accepts integer versions and does not require artifact signing.

## Goals / Non-Goals

**Goals:**
- Publish `core` module as a reusable library JAR to GitHub Packages
- Publish root module shadowJar as the distributable CLI artifact to GitHub Packages
- Trigger publication automatically on version tag pushes (`v*`) and main branch merges
- Mirror the trigger and permissions pattern of the existing `publish-container.yml` workflow

**Non-Goals:**
- Maven Central publishing
- Artifact signing
- Publishing spark modules (deferred to follow-up)
- Changing the version scheme

## Decisions

**1. Apply `maven-publish` plugin per-module, not in `allprojects`**

Each module needs different publication configuration: `core` publishes a standard library JAR, while the root module publishes a shadowJar. Applying per-module keeps configurations explicit and isolated.

**2. Root module publishes the shadowJar, not the standard jar**

The root CLI module is only useful as a fat jar — its dependencies are not on Maven Central in the expected form for a thin jar to work. The shadowJar is the correct distributable artifact. The publication artifact classifier should be empty (i.e., the shadowJar is the primary artifact).

**3. `core` publishes with sources and javadoc JARs**

Standard library publication practice. Sources and javadoc JARs improve IDE integration for consumers. These can be generated with `kotlinSourcesJar` and the `javadoc` task.

**4. Workflow triggers: version tags and main branch merges**

Consistent with `publish-container.yml`. On tag push (`v*`), publish with the version derived from the tag. On main branch merge (via `workflow_run` on PR Checks success), publish with version `latest` or the integer version from `gradle.properties`. Using the same `workflow_run` trigger avoids publishing from failing builds.

**5. Repository credentials via `GITHUB_TOKEN`**

GitHub Packages Maven requires authentication. The `GITHUB_TOKEN` secret is available in all workflows without configuration. The repository URL is `https://maven.pkg.github.com/rustyrazorblade/easy-db-lab`.

**6. No version override in workflow — use `gradle.properties` version as-is**

The integer version in `gradle.properties` is valid for Maven and GitHub Packages. No transformation needed. For tag pushes, the tag version (`v*` → strip `v`) should override the `gradle.properties` version to keep artifacts aligned with Git tags.

## Risks / Trade-offs

- **[Re-publishing same version]** GitHub Packages does not allow overwriting an existing published version. Publishing on every main merge with the same integer version will fail after the first merge. → Mitigate by publishing a `-SNAPSHOT` suffix on main branch merges, and the clean version only on tag pushes.
- **[Spark module consumers]** Spark modules depend on `core`. Leaving them unpublished is intentional — they are internal tools, not reusable libraries. → Documented as non-goal.
