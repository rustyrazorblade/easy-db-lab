## Why

Users want to ship kit definitions alongside private projects (POCs, internal tooling) without
bundling them into easy-db-lab. Today there is no way to register an external directory of kits
so they appear in `kit list`, `kit info`, and `kit install` — the only escape hatch is the
ad-hoc `--from` flag, which is not persistent and not listed.

## What Changes

- **New `kit source` subcommand group** with three subcommands:
  - `kit source add <path>` — register a parent directory of kit subdirectories
  - `kit source list` — show all registered source directories
  - `kit source remove <path>` — unregister a source directory
- **New `kit-sources.yaml`** in `~/.easy-db-lab/profiles/<profile>/` stores registered paths (kotlinx.serialization)
- **`InstallTemplateResolver`** gains a new resolution tier between profile-kits and built-ins
- `kit list`, `kit info`, and `kit install` pick up kits from additional sources with no changes
- `kit install` for an external-source kit copies the kit into the cluster directory exactly like built-in kits — the installed kit is self-contained in the cluster workspace
- The existing `--from` flag is the ad-hoc (one-time) variant of `kit source add`; both resolve through `TemplateSource.Directory` — additional sources make that resolution persistent and discoverable

## Capabilities

### New Capabilities

- `kit-source-management`: Register, list, and remove additional parent directories of kit templates; persist them in `kit-sources.yaml`

### Modified Capabilities

- `install-command`: Template discovery gains a fourth source tier — additional registered directories — between profile-kits and built-ins

## Impact

- `InstallTemplateResolver` — add resolution tier, read sources from `KitSourcesConfig`
- New `KitSourcesConfig` data class + `KitSourcesProvider` service (kotlinx.serialization, `kit-sources.yaml`)
- New `commands/kit/source/` package: `KitSource`, `KitSourceAdd`, `KitSourceList`, `KitSourceRemove`
- `Kit.kt` — add `KitSource` as a subcommand
- `ServicesModule.kt` — register `KitSourcesProvider`
- No changes to `KitList`, `KitInfo`, `Install`, or `BaseInstallCommand`
