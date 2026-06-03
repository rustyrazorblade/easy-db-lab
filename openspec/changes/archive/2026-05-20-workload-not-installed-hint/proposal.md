## Why

When a user types `easy-db-lab presto` and presto isn't installed, PicoCLI returns a cryptic
"Unmatched argument at index 0: 'presto'" error with generic "did you mean repl?" suggestions.
This is confusing because the user's intent is clear — they want to use a workload that simply
hasn't been installed yet.

## What Changes

- Add a custom `IParameterExceptionHandler` to `CommandLineParser` that intercepts
  `UnmatchedArgumentException` and checks whether the unrecognized argument matches an
  available (installable) workload template.
- If the argument matches an available workload, print an actionable message:
  `"<name> is not installed. Run: easy-db-lab install <name>"`
- If the argument does not match any available template, fall through to PicoCLI's default
  error output unchanged.

## Capabilities

### New Capabilities

- `uninstalled-workload-hint`: When an unrecognized top-level argument matches an installable
  workload template name, the CLI displays a targeted "not installed" message with the exact
  install command, instead of the generic PicoCLI unmatched-argument error.

### Modified Capabilities

## Impact

- `CommandLineParser.kt` — register custom `IParameterExceptionHandler` on the root `CommandLine`
- `InstallTemplateResolver` — already provides `listAvailableTemplates()`, no changes needed
- No changes to commands, services, or external systems
