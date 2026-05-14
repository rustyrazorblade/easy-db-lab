# Development Overview

Hello there. If you're reading this, you've probably decided to contribute to easy-db-lab or use the tools for your own work. Very cool.

## Dev Containers (Recommended)

Dev Containers are the preferred method for developing easy-db-lab. They provide a consistent, pre-configured environment with all required tools installed:

- **Java 21** (Temurin) via SDKMAN
- **Kotlin** and **Gradle**
- **MkDocs** for documentation
- **Docker-in-Docker** for container operations
- **Claude Code** for AI-assisted development
- **zsh** with Powerlevel10k theme

### VS Code

1. Install the [Dev Containers extension](https://marketplace.visualstudio.com/items?itemName=ms-vscode-remote.remote-containers)
2. Open the project folder
3. Click "Reopen in Container" when prompted

### JetBrains IDEs

1. Install the [Dev Containers plugin](https://plugins.jetbrains.com/plugin/21962-dev-containers)
2. Open the project and select "Dev Containers" from the remote development options

### CLI with bin/dev

The `bin/dev` script provides a convenient wrapper for dev container management:

```bash
bin/dev start          # Start the dev container
bin/dev shell          # Open interactive shell
bin/dev test           # Run Gradle tests
bin/dev docs-serve     # Serve docs with live reload
bin/dev claude         # Start Claude Code
bin/dev status         # Show container status
bin/dev down           # Stop and remove container
```

To mount your Claude Code configuration (for AI-assisted development):

```bash
ENABLE_CLAUDE=1 bin/dev start
```

Run `bin/dev help` for all available commands.

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

Once inside the container (or with local tools installed):

```bash
./gradlew assemble
./gradlew test
```

## Documentation Preview

Preview documentation locally with live reload:

```bash
bin/dev docs-serve
```

Then open http://localhost:8000 in your browser.

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
