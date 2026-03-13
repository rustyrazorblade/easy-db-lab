## Why

The `core` module is a shared library that other projects could depend on, and the CLI shadowJar is the primary distributable artifact for the tool. Currently there is no automated way to publish these to a Maven repository — developers must build from source or pull the Docker image. Publishing to GitHub Packages makes it straightforward to consume `core` as a library dependency and to retrieve versioned CLI jars without building locally.

## What Changes

- Add the `maven-publish` Gradle plugin to the root module and `core` module
- Configure publication of `core` as a plain library JAR (sources + javadoc JARs included)
- Configure publication of the root module shadowJar as the distributable CLI artifact
- Add a GitHub Actions workflow (`publish-maven.yml`) that publishes artifacts on version tag pushes (`v*`) and on merges to `main`
- No signing required (GitHub Packages does not require artifact signing)
- Spark modules are left for a follow-up

## Capabilities

### New Capabilities

- `maven-publish`: Artifacts from `core` and the root CLI module are published to GitHub Packages (Maven repository) on version tags and main branch merges

### Modified Capabilities

(none)

## Impact

- **Code**: `build.gradle.kts` (root and `core`) — add `maven-publish` plugin and publication configuration
- **CI**: New `.github/workflows/publish-maven.yml` workflow
- **Docs**: Update `docs/development/` or `DEVELOPERS.md` to document how to consume the published artifacts
- **No tests required**: Publication configuration has no meaningful unit-testable logic
