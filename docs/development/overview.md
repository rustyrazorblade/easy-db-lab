# Development Overview

Hello there. If you're reading this, you've probably decided to contribute to easy-db-lab or use the tools for your own work. Very cool.

## Prerequisites

Install these locally before building:

- **Java 21** (Temurin) via SDKMAN — the default for the main project
- **Kotlin** and **Gradle** (the Gradle wrapper `./gradlew` is committed)
- **Docker** — for TestContainers-backed integration tests
- **mdbook** + **mdbook-admonish** — for previewing documentation

The example Spark jobs and their Cassandra Analytics build live in the separate
[`spark-examples`](https://github.com/rustyrazorblade/spark-examples) repository, so this
repo needs only Java 21.

## Local Configuration (.env)

Both `bin/easy-db-lab` and `bin/end-to-end-test` automatically load a `.env` file from the project root if one exists. This is the recommended way to set per-developer configuration without modifying committed scripts.

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

For more details, see the project's `CLAUDE.md` file.
