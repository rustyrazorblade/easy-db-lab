# PostgreSQL Kit

Notes for developers maintaining the postgres kit.

## Extension Model — One Image Per Installation

**Each extension uses a different pre-built container image.** A postgres installation can only have one extension active at a time because CNPG runs a single image per cluster. The `--extension` flag on `kit install postgres` selects exactly one alias; there is no multi-extension CLI path.

The `ExtensionResolver` contains conflict-detection logic for multiple aliases, but this is unreachable from the CLI — `ArgType.EXTENSION` maps to a single `String`, not a list. Do not add multi-extension scenarios to unit tests or docs without first wiring up multi-value PicoCLI support.

## CNPG Extension Images

### Non-Semver Tags — ImageCatalog Bypass

CNPG 1.29+ validates image tags as semver and rejects tags like `v1.1.1` or `17-v1.1.1`. To use an extension image with a non-semver tag:

1. Define an `ImageCatalog` resource in `postgres-cluster.yaml.template` listing the image under the postgres major version.
2. Reference it via `imageCatalogRef` on the Cluster spec instead of `imageName`.

See the `ImageCatalog` block in `postgres-cluster.yaml.template` for the reference implementation.

### Custom Postgres UID/GID

The CNPG Cluster defaults to UID/GID 26 (the `postgres` user in the CNPG base image). Extension images built on Docker Hub's official `postgres` image use UID 999. A mismatch causes `initdb: could not look up effective user ID` at cluster init and the pod never becomes Ready.

Set `postgres_uid` and `postgres_gid` in `extensions.yaml` for any extension image that is NOT based on the CNPG base image. These values flow through `ExtensionResolver` → `ExtensionConfig` → `__POSTGRES_UID__`/`__POSTGRES_GID__` substitution in `postgres-cluster.yaml.template`.

Known UIDs:
- CNPG base image (`ghcr.io/cloudnative-pg/postgresql`): UID 26 (default, no override needed)
- Docker Hub `postgres` base (`pgduckdb/pgduckdb`): UID 999
- `timescale/timescaledb-ha`: UID 1000 (Ubuntu-based, postgres user)

## Waiting for Postgres Pods

Always use `cnpg.io/podRole=instance` as the label selector when waiting for running postgres pods. **Do NOT use `cnpg.io/cluster=<name>`** — that label matches both running instance pods AND completed initdb job pods, causing `kubectl wait --for=condition=Ready` to time out on the completed job pod which can never become Ready.
