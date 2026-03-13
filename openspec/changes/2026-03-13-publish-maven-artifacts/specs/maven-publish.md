# Maven Artifact Publishing

Defines requirements for publishing versioned Maven artifacts from the multi-module Gradle build to GitHub Packages.

## Requirements

### REQ-MP-001: Core Library Publication

The build system MUST publish the `core` module as a reusable library JAR to GitHub Packages under the Maven coordinate `com.rustyrazorblade:easy-db-lab-core`.

**Scenarios:**

- **GIVEN** a version tag push (`v*`), **WHEN** the publish workflow runs, **THEN** the `core` JAR and sources JAR are published to GitHub Packages with the version derived from the tag.
- **GIVEN** a merge to the main branch, **WHEN** the publish workflow runs, **THEN** a snapshot version of the `core` JAR is published to GitHub Packages.

### REQ-MP-002: CLI Artifact Publication

The build system MUST publish the root module shadowJar as the distributable CLI artifact to GitHub Packages under the Maven coordinate `com.rustyrazorblade:easy-db-lab`.

**Scenarios:**

- **GIVEN** a version tag push (`v*`), **WHEN** the publish workflow runs, **THEN** the CLI shadowJar is published to GitHub Packages with the version derived from the tag.
- **GIVEN** a merge to the main branch, **WHEN** the publish workflow runs, **THEN** a snapshot version of the CLI shadowJar is published to GitHub Packages.

### REQ-MP-003: Automated CI Publication

The publish workflow MUST only run after a successful build to avoid publishing broken artifacts.

**Scenarios:**

- **GIVEN** a PR Checks workflow that failed, **WHEN** the publish workflow evaluates whether to run, **THEN** it does not publish any artifacts.
- **GIVEN** a PR Checks workflow that succeeded on the main branch, **WHEN** the publish workflow evaluates whether to run, **THEN** it publishes snapshot artifacts.

### REQ-MP-004: Version Tagging Scheme

On version tag pushes, the published artifact version MUST match the tag (with the `v` prefix stripped). On main branch merges, the published artifact version MUST use a snapshot suffix.

**Scenarios:**

- **GIVEN** a tag push of `v12`, **WHEN** artifacts are published, **THEN** they appear under version `12` in GitHub Packages.
- **GIVEN** a merge to main with `version=12` in `gradle.properties`, **WHEN** artifacts are published, **THEN** they appear under version `12-SNAPSHOT` in GitHub Packages.
