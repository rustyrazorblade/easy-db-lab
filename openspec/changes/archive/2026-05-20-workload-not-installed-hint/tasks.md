## 1. Implementation

- [x] 1.1 Add a custom `IParameterExceptionHandler` in `CommandLineParser.kt` that intercepts `UnmatchedArgumentException`, extracts the first unmatched token, checks it against `InstallTemplateResolver.listAvailableTemplates()`, and if it matches prints `"<name> is not installed. Run: easy-db-lab install <name>"` then returns exit code 2; otherwise delegates to PicoCLI's default handler
- [x] 1.2 Register the custom parameter exception handler on the root `CommandLine` instance in `CommandLineParser`

## 2. Tests

- [x] 2.1 Add a unit test verifying that when the first unmatched argument matches an available template name, the hint message is printed and exit code 2 is returned
- [x] 2.2 Add a unit test verifying that when the first unmatched argument does not match any available template, the default PicoCLI error behavior runs (no hint message)
