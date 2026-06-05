## 1. Data Model & Persistence

- [x] 1.1 Create `KitSourcesConfig` data class (kotlinx.serialization) with `paths: List<String>`
- [x] 1.2 Create `KitSourcesProvider` service: `load()`, `save()`, `addPath()`, `removePath()` backed by `kit-sources.yaml` in the profile directory
- [x] 1.3 Register `KitSourcesProvider` as a Koin singleton in `ServicesModule`
- [x] 1.4 Write unit tests for `KitSourcesProvider`: add, remove, duplicate no-op, missing file treated as empty

## 2. Resolver Integration

- [x] 2.1 Inject `KitSourcesProvider` into `InstallTemplateResolver`
- [x] 2.2 Update `listAvailableTemplates()` to scan additional source directories (between profile-kits and built-ins); skip missing paths silently
- [x] 2.3 Update `resolve(name)` to check additional source directories before built-ins
- [x] 2.4 Write unit tests for resolver priority: additional source shadows built-in, profile shadows additional source, missing path is skipped

## 3. `kit source` Commands

- [x] 3.1 Create `commands/kit/source/` package with `KitSource` parent command (PicoCLI `@Command`)
- [x] 3.2 Implement `KitSourceAdd`: validate path exists, delegate to `KitSourcesProvider.addPath()`, print confirmation or "already registered"
- [x] 3.3 Implement `KitSourceList`: print each path from `KitSourcesProvider`; flag missing paths with `[missing]`; print "No sources registered" when empty
- [x] 3.4 Implement `KitSourceRemove`: delegate to `KitSourcesProvider.removePath()`, print confirmation or "not found"
- [x] 3.5 Register `KitSource` as a subcommand of `Kit` in `Kit.kt`
- [x] 3.6 Write unit tests for `KitSourceAdd`, `KitSourceList`, `KitSourceRemove` using `@TempDir` and real `KitSourcesProvider`

## 4. End-to-End Verification

- [x] 4.1 Write integration test: register a temp directory with a kit subdir, verify the kit appears in `listAvailableTemplates()`
- [x] 4.2 Write integration test: register a source, install the kit, verify files are written to the cluster working directory

## 5. Documentation

- [x] 5.1 Update `docs/` kit documentation to describe `kit source add/list/remove` and the `kit-sources.yaml` file
