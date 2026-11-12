# Development Overview

Hello there. If you're reading this, you've probably decided to contribute to easy-db-lab or use the tools for your own work. Very cool.

## Prerequisites

Install these locally before building:

- **Java 21 or newer** (Temurin) via SDKMAN — Java 21 is the project default, but the
  build also works on newer JDKs (e.g. 25). The build compiles with whatever JDK you have
  installed and always emits Java 21 bytecode, so artifacts still run on older JVMs.
  One caveat: static analysis (`detekt`, and therefore the full `check` task) must run on
  **JDK 21** — detekt 1.23.8 cannot run under a JDK 25 runtime. Building, running, and
  testing the application all work on JDK 25; CI runs `check` on JDK 21.
- **Kotlin** and **Gradle** (the Gradle wrapper `./gradlew` is committed)
- **Docker** — for TestContainers-backed integration tests
- **helm** and **kubectl** — on your `PATH`, for `./gradlew integrationTest` and therefore `check`:
  `LocalClusterToolingIntegrationTest` runs the host's own tools, as the local form of
  `ClusterTooling` does, and fails with a message naming whichever is missing (it never skips).
  CI installs helm v3.17.x (the version the node AMI pins in `packer/base/install/install_helm.sh`)
  and kubectl v1.35.x (the K3s the tests run), in `.github/workflows/pr-checks.yml`.
- **mdbook** + **mdbook-admonish** — for previewing documentation

The example Spark jobs and their Cassandra Analytics build live in the separate
[`spark-examples`](https://github.com/rustyrazorblade/spark-examples) repository, so this
repo needs only a single JDK (21 or newer).

## Local Configuration (.env)

`bin/easy-db-lab` automatically loads a `.env` file from the project root if one exists. This is the recommended way to set per-developer configuration without modifying committed scripts.

### Setup

```bash
cp .env.example .env
# Edit .env with your values
```

`.env` is listed in `.gitignore` and will never be committed.

### Supported Variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `AWS_PROFILE` | Yes (for e2e tests) | — | AWS credentials profile from `~/.aws/config` |
| `EASY_DB_LAB_INSTANCE_TYPE` | No | `c5d.2xlarge` | EC2 instance type for database nodes |
| `SIDECAR_IMAGE` | No | `ghcr.io/apache/cassandra-sidecar:latest` | Custom Cassandra sidecar container image |

Example `.env`:

```bash
AWS_PROFILE=sandbox-admin
# SIDECAR_IMAGE=102382809497.dkr.ecr.us-west-2.amazonaws.com/rustyrazorblade/cassandra-sidecar
# EASY_DB_LAB_INSTANCE_TYPE=c5d.4xlarge
```

Variables already exported in your shell always take precedence over `.env`.

## Building the Project

With the required tools installed:

```bash
./gradlew assemble
./gradlew test
```

## Documentation Preview

Preview documentation locally with live reload:

```bash
cd docs
mdbook serve
```

Then open http://localhost:3000 in your browser.

## Project Structure

easy-db-lab is broken into several subprojects:

- **Docker containers** (prefixed with `docker-`)
- **Documentation** (the manual you're reading now)
- **Utility code** for downloading artifacts

## Architecture

The project follows a layered architecture:

```
Commands (PicoCLI) → Services → External Systems (K8s, AWS, Filesystem)
```

### Layer Responsibilities

- **Commands** (`commands/`): Lightweight PicoCLI execution units
- **Services** (`services/`, `providers/`): Business logic layer
- **Kernel** (`kernel/`): Types shared by commands, services and providers, such as the `PicoCommand` interface and the provider error contract. It keeps those layers from depending on each other.
- **Provider boundary**: AWS SDK types stay in `providers/aws` and `services/aws`; a unit test (`PackageBoundaryTest`) fails the build if one appears elsewhere.

For more details, see the project's `CLAUDE.md` file.
