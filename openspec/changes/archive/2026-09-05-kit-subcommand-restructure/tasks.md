## 1. New Command Classes

- [x] 1.1 Create `commands/kit/Kit.kt` — PicoCLI parent command named `kit` that prints help when invoked without a subcommand
- [x] 1.2 Create `commands/kit/KitList.kt` — `kit list` subcommand that emits `Event.Install.TemplatesListed(resolver.listAvailableTemplateDetails())`
- [x] 1.3 Move `Install.kt` into `commands/kit/` and remove the `--list` flag and its branch from `Install.execute()`
- [x] 1.4 Move `Uninstall.kt` into `commands/kit/` (package rename only, no logic changes)

## 2. CommandLineParser Registration

- [x] 2.1 Remove `Install::class` and `Uninstall::class` from `@Command(subcommands=[...])` in `EasyDBLabCommand`
- [x] 2.2 Register `Kit` as a top-level subcommand in `EasyDBLabCommand`
- [x] 2.3 Update `registerDynamicInstallSubcommands()` to look up `kit` then navigate to its `install` child when registering dynamic per-kit subcommands

## 3. Hint Handler Update

- [x] 3.1 Update `UninstalledKitHintHandler` hint message from `easy-db-lab install <name>` to `easy-db-lab kit install <name>`

## 4. Tests

- [x] 4.1 Update `KitInstallCommandFactoryTest` and `KitRunnerCommandFactoryTest` to reference the new command path
- [x] 4.2 Update `InstallFromTest` to use `kit install` command path
- [x] 4.3 Verify `UninstalledKitHintHandlerTest` (if it exists) checks the updated hint message

## 5. Documentation

- [x] 5.1 Update `commands/CLAUDE.md` package map to show `kit/` subdirectory with `Kit.kt`, `KitList.kt`, `Install.kt`, `Uninstall.kt`
- [x] 5.2 Update user-facing docs (any page referencing `install --list` or `easy-db-lab install <kit>`) to use `kit list` and `kit install <kit>`
