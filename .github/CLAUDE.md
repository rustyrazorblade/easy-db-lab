# GitHub CI

## Container Tagging Rules

The `publish-container.yml` workflow publishes containers for all branches and tags. Do NOT restrict which branches trigger the workflow — branch containers are intentional for testing.

Tagging scheme:
- **main branch** → `latest`
- **feature branches** → sanitized branch name (e.g., `stress-dashboard`)
- **version tags** (`v*`) → `$VERSION` and `v$VERSION` (e.g., `1.0.0` and `v1.0.0`). NEVER include `latest`.

Only the main branch should ever produce the `latest` tag.
