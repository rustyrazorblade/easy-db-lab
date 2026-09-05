# PostgreSQL Extensions

Registry-driven extension support for the postgres kit. Provides built-in aliases that resolve to a CNPG-compatible image, shared_preload_libraries config, and CREATE EXTENSION statements. Also provides escape-hatch flags for custom images and unlisted extensions.

## ADDED Requirements

### Requirement: Extension Registry

The postgres kit SHALL include an `extensions.yaml` file defining built-in extension aliases. Each alias SHALL specify: `description` (string), `image` (string with `__PG_MAJOR__` placeholder), optional `shared_preload_libraries` (list), and `create_extensions` (list). The registry SHALL include aliases for at minimum: `duckdb`, `postgis`, and `timescaledb`.

#### Scenario: Registry is present in kit resources
- **WHEN** the postgres kit is installed
- **THEN** `extensions.yaml` is present alongside `kit.yaml` in the kit resource directory

#### Scenario: Registry contains duckdb alias
- **WHEN** `extensions.yaml` is loaded
- **THEN** the `duckdb` alias resolves to image `ghcr.io/duckdb/pg_duckdb:pg__PG_MAJOR__`, shared_preload_libraries `[pg_duckdb]`, and create_extensions `[pg_duckdb]`

### Requirement: --extension Flag Resolves Alias

`postgres start` SHALL accept a repeatable `--extension <alias>` flag. When provided, the alias SHALL be resolved from `extensions.yaml`. The resolved image SHALL be used unless `--image` is also provided. Resolved `shared_preload_libraries` and `create_extensions` SHALL always be applied regardless of whether `--image` overrides the image.

#### Scenario: Single alias sets image and creates extension
- **WHEN** the user runs `postgres start --extension duckdb`
- **THEN** the CNPG Cluster CR uses the duckdb image, `pg_duckdb` is in shared_preload_libraries, and `CREATE EXTENSION pg_duckdb` is run on init

#### Scenario: Unknown alias fails fast
- **WHEN** the user runs `postgres start --extension nonexistent`
- **THEN** an error is emitted before any K8s resources are applied

### Requirement: --image Flag Overrides Image

`postgres start` SHALL accept an `--image <image>` flag that overrides the container image regardless of what any alias specifies. When combined with `--extension`, the alias image is ignored but its preload and CREATE EXTENSION config still applies.

#### Scenario: Custom image with alias config
- **WHEN** the user runs `postgres start --image my-registry/pg-custom:pg17 --extension duckdb --extension postgis`
- **THEN** the CNPG Cluster CR uses `my-registry/pg-custom:pg17` as the image, and both pg_duckdb and postgis are in the preload and CREATE EXTENSION list

#### Scenario: --image alone overrides default
- **WHEN** the user runs `postgres start --image my-registry/pg-custom:pg17`
- **THEN** the CNPG Cluster CR uses `my-registry/pg-custom:pg17` and no extensions are added

### Requirement: Multi-Alias Image Conflict Detection

When multiple `--extension` aliases each define a different image and `--image` is not provided, `postgres start` SHALL fail with an error naming the conflicting aliases and instructing the user to provide `--image`.

#### Scenario: Two aliases with different images and no --image
- **WHEN** the user runs `postgres start --extension duckdb --extension postgis` without `--image`
- **THEN** an error is emitted naming both aliases and instructing the user to provide `--image` with a combined image

#### Scenario: Two aliases with different images and --image provided
- **WHEN** the user runs `postgres start --extension duckdb --extension postgis --image my-registry/combined:pg17`
- **THEN** the command succeeds using the provided image with both extensions' preload and CREATE EXTENSION config applied

### Requirement: Escape-Hatch Flags

`postgres start` SHALL accept `--shared-preload-libraries <lib>` (repeatable) and `--create-extension <name>` (repeatable) flags. These SHALL be appended to whatever any alias contributes, allowing users to add config beyond what a built-in alias provides or to use a fully custom image with no alias.

#### Scenario: Pure escape hatch with no alias
- **WHEN** the user runs `postgres start --image my-registry/pg-custom:pg17 --shared-preload-libraries pg_duckdb --create-extension pg_duckdb`
- **THEN** the CNPG Cluster CR uses the custom image, includes `pg_duckdb` in shared_preload_libraries, and runs `CREATE EXTENSION pg_duckdb` on init

#### Scenario: Escape hatch augments alias
- **WHEN** the user runs `postgres start --extension duckdb --shared-preload-libraries my_extra_lib`
- **THEN** `my_extra_lib` is appended to the preload libraries from the duckdb alias

### Requirement: postgres extensions Subcommand

The `postgres extensions` subcommand SHALL display all aliases from `extensions.yaml` in a table showing: alias name, image template, preload libraries, and CREATE EXTENSION list. This is a read-only display command.

#### Scenario: List shows all built-in aliases
- **WHEN** the user runs `postgres extensions`
- **THEN** a table is printed listing all aliases defined in `extensions.yaml` including at minimum duckdb, postgis, and timescaledb
