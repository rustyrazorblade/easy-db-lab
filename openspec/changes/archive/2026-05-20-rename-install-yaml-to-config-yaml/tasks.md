## 1. Add constant

- [x] 1.1 Add `CONFIG_FILE = "config.yaml"` to `Constants.kt` (in an appropriate nested object, e.g. `Constants.Workload`)

## 2. Rename classpath resources

- [x] 2.1 Rename `src/main/resources/.../install/clickhouse/install.yaml` → `config.yaml`
- [x] 2.2 Rename `src/main/resources/.../install/presto/install.yaml` → `config.yaml`

## 3. Update production code

- [x] 3.1 Replace `"install.yaml"` literal in `InstallTemplateResolver.kt` with `Constants.Workload.CONFIG_FILE`
- [x] 3.2 Replace `"install.yaml"` literal in `WorkloadRunnerCommandFactory.kt` with the constant
- [x] 3.3 Replace `"install.yaml"` literal in `WorkloadInstallCommand.kt` with the constant
- [x] 3.4 Replace `"install.yaml"` literal in `BaseInstallCommand.kt` with the constant
- [x] 3.5 Replace `"install.yaml"` literal in `WorkloadRunnerCommand.kt` with the constant
- [x] 3.6 Replace `"install.yaml"` literal in `WorkloadHookExecutor.kt` with the constant
- [x] 3.7 Replace `"install.yaml"` literal in `CommandLineParser.kt` with the constant

## 4. Update tests

- [x] 4.1 Update `WorkloadRunnerCommandFactoryTest.kt` — rename `install.yaml` to `config.yaml` in temp dir setup
- [x] 4.2 Update `WorkloadRunnerCommandTest.kt` — rename `install.yaml` references
- [x] 4.3 Update `WorkloadHookExecutorTest.kt` — rename `install.yaml` references
- [x] 4.4 Update `InstallTemplateResolverTest.kt` — rename `install.yaml` references
- [x] 4.5 Update `WorkloadEndToEndTest.kt` — rename `install.yaml` references

## 5. Update documentation and specs

- [x] 5.1 Update `openspec/specs/install-command/spec.md` to reference `config.yaml` throughout
- [x] 5.2 Update `docs/` user documentation that mentions `install.yaml`

## 6. Verify

- [x] 6.1 Run `./gradlew test` — all tests pass
