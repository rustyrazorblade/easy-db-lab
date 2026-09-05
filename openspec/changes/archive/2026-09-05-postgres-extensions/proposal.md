## Why

The postgres kit deploys a vanilla PostgreSQL image, but many real-world testing scenarios require extensions that are compiled into the image (pg_duckdb, PostGIS, TimescaleDB). Users currently have no way to deploy PostgreSQL with extensions without manually constructing CNPG Cluster CRs.

## What Changes

- Add `extensions.yaml` to the postgres kit resource directory — a registry mapping alias names to image template, shared_preload_libraries, and CREATE EXTENSION statements
- Add `--extension <alias>` flag to `postgres start` — selects a built-in alias; repeatable
- Add `--image <image>` flag to `postgres start` — overrides the image from any alias or the default
- Add `--shared-preload-libraries <lib>` flag to `postgres start` — appends to alias-provided preload config; repeatable
- Add `--create-extension <name>` flag to `postgres start` — appends to alias-provided CREATE EXTENSION list; repeatable
- Add `postgres extensions` subcommand — lists all aliases from `extensions.yaml` in a table
- Update `postgres-cluster.yaml.template` to support the new image, preload, and initdb fields
- Update user docs for the postgres kit

## Capabilities

### New Capabilities

- `postgres-extensions`: Registry-driven extension support for the postgres kit — alias resolution, flag contract, image override behavior, and the `postgres extensions` list subcommand

### Modified Capabilities

- `postgres`: The postgres kit gains new `start` flags and a new subcommand; the Cluster CR template gains new optional fields

## Impact

- `src/main/resources/com/rustyrazorblade/easydblab/kits/postgres/` — new `extensions.yaml`, updated `postgres-cluster.yaml.template`
- Kotlin command class for `postgres start` — new flags, extension resolution logic
- New `postgres extensions` command class
- `docs/user-guide/install-postgres.md` — document new flags and aliases
