## Context

`InstallTemplateResolver` currently resolves kit templates from three sources in priority order:
profile-kits directory, built-in classpath, and ad-hoc `--from` path (install-only, not listed).
The `--from` flag is the existing one-off mechanism for pointing at an external kit directory;
it wraps the path as `TemplateSource.Directory` and renders through the same pipeline as any
other kit. This change makes that mechanism persistent and discoverable by adding a registered
sources tier stored in `kit-sources.yaml`.

## Goals / Non-Goals

**Goals:**
- Let users register parent directories of kit subdirs that persist across sessions
- Make registered-source kits appear in `kit list`, `kit info`, and `kit install` with no extra flags
- `kit install` for an external kit copies files into the cluster directory (unchanged from today)
- `--from` remains the one-off variant; both paths use `TemplateSource.Directory`

**Non-Goals:**
- Remote sources (URLs, git repos) — local filesystem only
- Merging or deprecating `--from`
- Per-kit override of individual files from an external source

## Decisions

### 1. Storage: dedicated `kit-sources.yaml` with kotlinx.serialization

`settings.yaml` (the `User` data class) holds cloud credentials and is loaded on every command
that touches AWS. Kit source directories are a development workflow preference with no cloud
dependency. Mixing them into `settings.yaml` would force Jackson (deprecated) and bloat a class
that already has too many concerns.

A dedicated `~/.easy-db-lab/profiles/<profile>/kit-sources.yaml` with kotlinx.serialization keeps
concerns separated and aligns with the codebase's serialization standard.

```
KitSourcesConfig
  paths: List<String>   # absolute paths to parent directories
```

### 2. New `KitSourcesProvider` service

A single-responsibility service owns reading and writing `kit-sources.yaml`. It follows the
same provider pattern as `UserConfigProvider`:
- `load(): KitSourcesConfig` — reads or returns empty config if file absent
- `save(config: KitSourcesConfig)` — writes and updates cache
- `addPath(path: String)` / `removePath(path: String)` — mutate and save

Registered in `ServicesModule` as a Koin singleton.

### 3. Resolution priority in `InstallTemplateResolver`

```
1. profile kits      ~/.easy-db-lab/profiles/<profile>/kits/<name>/
2. additional sources registered via kit source add (searched in order of registration)
3. built-in          classpath
```

Additional sources are iterated in registration order. Within each source directory, subdirectories
are treated as individual kit templates (same as profile-kits). The resolver's `resolve(name)` and
`listAvailableTemplates()` methods are extended to scan additional source dirs between profile-kits
and built-ins.

### 4. `kit source` subcommand group

Three subcommands under a new `KitSource` parent command, added to `Kit.kt`:

| Command | Behaviour |
|---|---|
| `kit source add <path>` | Validates path is an existing directory, then appends to `kit-sources.yaml`. Prints confirmation. |
| `kit source list` | Prints each registered path; flags paths that no longer exist on disk. |
| `kit source remove <path>` | Removes path from `kit-sources.yaml`; no-op with message if not found. |

Commands live in `commands/kit/source/`. They are read-only display (`list`) or profile-state
mutation (`add`, `remove`) — no cluster state involved, no events needed. `println()` for output.

### 5. `--from` relationship

`--from` takes a path that IS the kit directory directly (single kit). `kit source add` takes a
parent directory containing kit subdirectories. Both ultimately produce `TemplateSource.Directory`
instances — `--from` creates one immediately at install time; the resolver creates them during
discovery from registered source dirs. No code is duplicated; the `TemplateSource.Directory` type
and `renderAndWrite` pipeline are unchanged.

## Risks / Trade-offs

- **Stale paths**: A registered path may be deleted or moved (e.g. the project is recloned
  elsewhere). `kit source list` will flag non-existent paths; `kit list` will silently skip them
  rather than fail — missing directories are not an error, just an empty set of kits.
- **Name collision**: If an additional-source kit shares a name with a built-in, the
  additional-source kit wins (priority 2 > 3). If two registered sources have the same kit name,
  the first-registered source wins. This mirrors how profile-kits shadow built-ins today.

## Migration Plan

No migration required. `kit-sources.yaml` is created on first `kit source add`. Existing profiles
without the file behave identically to today. Clusters are ephemeral — no deployed state is affected.
