# docker/

This directory contains the base Docker image for easy-db-lab.

## Dockerfile

`Dockerfile` builds the base image published to `ghcr.io/rustyrazorblade/easy-db-lab-base:latest`. It extends `eclipse-temurin:21-jre` and installs `helm` (via the official install script) and `kubectl`.

This image is the foundation for the container that runs the easy-db-lab CLI. The application JAR and resources are layered on top at build time by the main `publish-container.yml` workflow.

## Base image publishing

The base image is rebuilt and pushed automatically by `.github/workflows/publish-base-image.yml`:
- On every push to `main` that touches `docker/Dockerfile`
- On a weekly schedule (Mondays at 06:00 UTC)
- On manual `workflow_dispatch`

Only modify this `Dockerfile` when the base tooling (Java version, helm, kubectl) needs to change. Application code changes do not require a base image rebuild.
