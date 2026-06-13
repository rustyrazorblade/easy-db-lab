## Context

The postgres kit currently deploys a vanilla `ghcr.io/cloudnative-pg/postgresql` image. CNPG is image-based — extensions that require native libraries (pg_duckdb, PostGIS, TimescaleDB, etc.) must be compiled into the image. Three fields in the CNPG Cluster CR control extension configuration: `spec.imageName`, `spec.postgresql.parameters.shared_preload_libraries`, and `spec.bootstrap.initdb.postInitSQL`. The postgres kit currently hardcodes all three and provides no way for users to vary them.

## Goals / Non-Goals

**Goals:**
- Let users deploy postgres with any extension that ships a CNPG-compatible image
- Ship built-in aliases for common extensions (duckdb, postgis, timescaledb) so the common case requires no image lookup
- Allow combining a custom image with alias-provided preload/CREATE EXTENSION config
- Make available aliases discoverable via `postgres extensions`

**Non-Goals:**
- Supporting extensions that require a pinned extension version in the image tag (e.g. pgvecto.rs `:pg17-v0.3.0`) as built-in aliases — use `--image` for those
- Installing extensions into a running cluster post-start
- Validating CNPG compatibility of user-supplied images at CLI time

## Decisions

### extensions.yaml as a standalone data file

The alias registry lives in `src/main/resources/.../kits/postgres/extensions.yaml` alongside `kit.yaml`. Each entry contains: `description`, `image` (with `__PG_MAJOR__` placeholder), optional `shared_preload_libraries` list, and `create_extensions` list.

`__PG_MAJOR__` is the only version placeholder. This matches the existing template system and covers every known extension that publishes stable floating tags by postgres major version. Extensions requiring a pinned extension version in the tag are not eligible for built-in aliases.

**Alternative considered**: Encoding aliases in `kit.yaml`. Rejected — kit.yaml is behavioral config; a pure data registry belongs in a separate file and is easier to evolve independently.

### Flag contract

Four new flags on `postgres start`:

| Flag | Description |
|---|---|
| `--extension <alias>` | Resolves alias from extensions.yaml; repeatable |
| `--image <image>` | Overrides the image (from alias or default) |
| `--shared-preload-libraries <lib>` | Appended to alias contributions; repeatable |
| `--create-extension <name>` | Appended to alias contributions; repeatable |

**Alternative considered**: A single `--extensions` list flag. Rejected — conflates alias lookup with raw CREATE EXTENSION names, and complicates the escape hatch story.

### Option B: --image overrides, --extension still contributes non-image config

When `--image` is provided alongside `--extension`, the image from the alias is ignored but its `shared_preload_libraries` and `create_extensions` still apply. This lets users bring a combined custom image while reusing known alias config:

```bash
postgres start --image my-registry/pg-custom:pg17 --extension duckdb --extension postgis
```

**Alternative considered**: Mutual exclusion between `--extension` and `--image`. Rejected — forces users who build combined images to re-specify all preload/extension config manually.

### Multi-alias image conflict → error

If two `--extension` aliases each define a different image and `--image` is not provided, the command fails with an actionable error explaining the user must build a combined image and supply it via `--image`.

### postgres-cluster.yaml.template gains optional fields

The template gains three new optional blocks rendered only when values are present:
- `spec.imageName` — defaults to `ghcr.io/cloudnative-pg/postgresql:__POSTGRES_VERSION__`; overridden by resolved image
- `spec.postgresql.parameters.shared_preload_libraries` — only emitted when non-empty
- `spec.bootstrap.initdb.postInitSQL` — only emitted when CREATE EXTENSION list is non-empty

Template rendering stays in the existing TemplateService pattern — no new rendering engine.

### postgres extensions subcommand

A new `postgres extensions` command (no sub-subcommand) reads `extensions.yaml` from the classpath and prints a table of alias → image template, preload libraries, extensions. Output via `println()` — read-only display command, no events.

## Risks / Trade-offs

- **Image tag staleness** — built-in aliases use floating `pg{V}` tags. If an upstream registry changes tag conventions, the alias breaks. Mitigation: document that aliases may need updates; `--image` always works as an escape hatch.
- **CNPG image compatibility** — third-party images may not follow CNPG's uid/gid conventions. Mitigation: document which aliases are CNPG-officially-published vs third-party; fail fast at CNPG apply time with a clear pod error.
- **shared_preload_libraries ordering** — PostgreSQL is sensitive to some preload library ordering. Mitigation: for the current alias set this is not an issue; if it becomes one, the user can use `--shared-preload-libraries` to supply the correct order.
