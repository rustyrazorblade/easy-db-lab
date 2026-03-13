## 1. Configure maven-publish for `core` module

- [ ] 1.1 Add the `maven-publish` plugin to `core/build.gradle.kts`. Configure a `MavenPublication` named `"core"` that publishes the `java` component (standard library JAR). Include a sources JAR via `kotlin { withSourcesJar() }`. Set `groupId = "com.rustyrazorblade"`, `artifactId = "easy-db-lab-core"`, and `version = project.version.toString()`. Configure the `publishingExtension` repository to point at `https://maven.pkg.github.com/rustyrazorblade/easy-db-lab` with credentials from environment variables `GITHUB_ACTOR` and `GITHUB_TOKEN`.

## 2. Configure maven-publish for root CLI module

- [ ] 2.1 Add the `maven-publish` plugin to the root `build.gradle.kts`. Configure a `MavenPublication` named `"cli"` that includes the shadowJar as the primary artifact (no classifier). Set `groupId = "com.rustyrazorblade"`, `artifactId = "easy-db-lab"`, and `version = project.version.toString()`. Configure the same GitHub Packages repository as in task 1.1.

## 3. Add GitHub Actions workflow for Maven publishing

- [ ] 3.1 Create `.github/workflows/publish-maven.yml`. The workflow should trigger on `push` to tags matching `v*` and via `workflow_run` on `PR Checks` completion (same pattern as `publish-container.yml`). The `publish` job should: check out the code, set up Java 21 (Temurin), set up Gradle, determine the version (strip `v` prefix from tag for tag pushes; append `-SNAPSHOT` to `gradle.properties` version for main branch merges), and run `./gradlew :core:publish :publish --no-daemon` with `GITHUB_ACTOR` and `GITHUB_TOKEN` environment variables set. Grant `contents: read` and `packages: write` permissions.

## 4. Update developer documentation

- [ ] 4.1 Add a section to `DEVELOPERS.md` (or create `docs/development/publishing.md` if appropriate) explaining: (a) how artifacts are published (tags vs main), (b) how to consume `core` as a dependency (Maven coordinate `com.rustyrazorblade:easy-db-lab-core:<version>`), (c) how to download the CLI jar from GitHub Packages, (d) that GitHub Packages requires authentication even for public packages.
