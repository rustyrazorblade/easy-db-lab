## MODIFIED Requirements

### Requirement: Template discovery sources
Templates are resolved from four sources in priority order:

1. **Profile directory**: `~/.easy-db-lab/profiles/<profile>/kits/<name>/`
2. **Additional sources**: directories registered via `kit source add`, searched in registration order; each registered directory's subdirectories are treated as individual kit templates
3. **Built-in classpath**: `kits/<name>/` (bundled with the CLI)
4. **Ad-hoc path**: supplied via `--from <path>` flag (install-time only, never listed)

Profile templates override built-ins and additional-source templates when names collide.
Additional-source templates override built-ins when names collide. Within multiple registered
additional sources, the first-registered source wins on name collision.
A missing additional-source directory (path no longer exists on disk) is silently skipped —
it does not cause an error.
The ad-hoc `--from` path is the one-time variant of a registered source: both resolve through
`TemplateSource.Directory` and render through the same pipeline.

#### Scenario: Kit from additional source appears in list
- **WHEN** a directory is registered via `kit source add ~/myproject/kits/`
- **AND** that directory contains a `my-kit/` subdirectory with `kit.yaml`
- **THEN** `kit list` includes `my-kit`

#### Scenario: Kit from additional source is inspectable
- **WHEN** a directory is registered via `kit source add ~/myproject/kits/`
- **AND** that directory contains a `my-kit/` subdirectory with `kit.yaml`
- **THEN** `kit info my-kit` displays the kit details

#### Scenario: Kit from additional source is installable
- **WHEN** a directory is registered via `kit source add ~/myproject/kits/`
- **AND** that directory contains a `my-kit/` subdirectory with `kit.yaml`
- **THEN** `kit install my-kit` copies the kit files into the cluster directory

#### Scenario: Additional source kit shadows built-in of same name
- **WHEN** a registered additional source contains a kit with the same name as a built-in kit
- **THEN** the additional-source kit is used (priority 2 beats priority 3)

#### Scenario: Profile kit shadows additional source of same name
- **WHEN** the profile kits directory contains a kit with the same name as a registered additional source
- **THEN** the profile kit is used (priority 1 beats priority 2)

#### Scenario: Missing additional source directory is skipped silently
- **WHEN** a registered additional source path no longer exists on disk
- **THEN** `kit list` does not include kits from that path
- **THEN** no error is raised
