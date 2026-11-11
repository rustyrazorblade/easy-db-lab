# Design

## Context

Two call sites carry a copied-inline predicate deciding whether a workspace subdirectory is an
installed kit:

- `CommandLineParser.registerDynamicKitSubcommands()` (around lines 251-295)
- `Status.displayKitsSection()` (around lines 419-435)

Both compute `hasBinScripts || hasConfigYaml`. The `hasBinScripts` leg is the defect. They share no
class today: `Status` does not use `KitRunnerCommandFactory`, and the two want different return
shapes — the parser needs `File` objects to build kit groups from, `Status` needs sorted names for
display. Where the shared rule lives was the open design question.

## Goals / Non-Goals

**Goals.** One implementation of the rule, consumed by both call sites. `kit.yaml` as the sole
marker. The rule written down in the specs rather than implied.

**Non-Goals.** Name-collision handling where a kit directory shadows a core command. Any new
warning or diagnostic for a rejected directory. The `kit uninstall` phase rule. The behavior of
`KitRunnerCommandFactory.collectScriptPhases()` — this changes which directories qualify, not what a
qualified kit exposes. The unparseable-`kit.yaml` case, which keeps its warn-and-fall-back behavior:
there the file exists, so the directory still registers.

## Decisions

### `WorkspaceKitScanner`, a Koin service in `services/`

```kotlin
class WorkspaceKitScanner(private val context: Context) {
    fun isInstalledKit(dir: File): Boolean =
        dir.isDirectory && File(dir, Constants.Kit.CONFIG_FILE).isFile

    fun discover(): List<File> =
        context.workingDirectory.listFiles().orEmpty().filter { isInstalledKit(it) }
}
```

Registered with one line in `services/ServicesModule.kt` beside the other kit registrations:
`singleOf(::WorkspaceKitScanner)`.

`KitSourcesProvider` is the same shape already — a small class over `Context`, `singleOf`-registered,
filesystem-only, no external side effects. `Status` injects services with `by inject()`;
`CommandLineParser` resolves them with `get<T>()` in the same init path. The layering holds: commands
read from a service, the service reads the filesystem.

### The seam is `discover(): List<File>`, not the bare predicate

Both call sites share the whole head of the pipeline —
`listFiles().orEmpty().filter { isDirectory }.filter { predicate }` — and differ only in the tail.
Sharing just the boolean would leave the `listFiles()` null handling duplicated, which is the part
with a real failure mode.

`Status` appends `.map { it.name }.sorted()` itself, because sort order is a display concern. No
`discoverNames()` method: it would add public surface for one `map`.

`isInstalledKit(dir)` stays public. It states the rule in one place and reads better than an inlined
`File(dir, CONFIG_FILE).isFile` at any future call site.

### `kit install --from` enforces the invariant

`resolveAdHoc` currently requires only that the path is an existing directory:

```kotlin
require(dir.isDirectory) { "Template path does not exist or is not a directory: $path" }
```

A second `require` beside it rejects a template with no `kit.yaml`. This is where the check belongs:
it is the single entry point for the ad-hoc path, it fails before anything is written to disk, and it
matches the guard already there.

`kit install <name>` needs no equivalent check. That path only registers an install subcommand when
`loadInstallConfig(source)` returns non-null, so a template with no parseable descriptor never gets a
subcommand to invoke.

Rejected: writing a minimal `kit.yaml` when the template has none. It would paper over a malformed
template rather than enforce the rule, and the invariant is meant to hold because installation
enforces it, not because the tool patches around violations.

### Naming

`WorkspaceKitScanner`, not `InstalledKitScanner`. Kits registered in the tool in code are not
discovered from the filesystem, so a name promising "every installed kit" would overclaim once both
registration paths exist. `WorkspaceKitScanner` names what is actually scanned.

## Alternatives Considered

**A top-level function `discoverInstalledKits(workingDirectory: File)` in `services/`.** The close
runner-up, and a legitimate choice. Zero DI wiring, and the repo does have top-level functions
(`kubernetes/getLocalKubeconfigPath`). Rejected only because a class matches the existing
`KitSourcesProvider` precedent and satisfies the project's class-KDoc convention naturally. Nothing
else in the design changes if this is preferred.

**A companion function on `KitRunnerCommandFactory`.** Rejected. It would make `Status` depend on the
PicoCLI group builder. The factory builds command specs; it does not own a filesystem query. It is
also not Koin-registered.

**An extension on `Context`.** Rejected. `Context` is a broad config holder in the root package.
Kit-domain knowledge does not belong there.

**The `core` module.** Rejected. `Constants` lives in the root module, so `core` cannot reference
`Constants.Kit.CONFIG_FILE` without moving `Constants` too — an unrelated migration.

**An extension point for future marker types.** Explicitly rejected by the owner. The rule is a hard
invariant, not a pluggable strategy. Kotlin-native kits (issue 879) are registered in code and never
reach this predicate, so there is no second marker to accommodate.

**Writing a minimal `kit.yaml` when an ad-hoc `--from` template has none.** Rejected by the owner. It
would paper over a malformed template rather than enforce the rule. The invariant is stated in the
spec instead; see "Risks" below for the residual exposure.

## Risks / Trade-offs

**`kit install --from <dir>` on a template with no `kit.yaml` now fails where it used to succeed.**
That is the intent: such an install would otherwise produce a kit directory that registers nothing,
with no error — a silent failure, which is the mode this repo's fail-fast convention exists to
avoid. The template was always malformed; the old `bin/` predicate merely hid it.

Whether any real `--from` template lacks a descriptor is unknown. The repo contains no such fixture
and every built-in kit ships one, so the expected blast radius is zero.

**Kits installed by name are unaffected.** `registerDynamicInstallSubcommands()` only registers a
`kit install <name>` subcommand when `loadInstallConfig(source)` returns non-null, and
`BaseInstallCommand` then writes that descriptor into the installed directory. A template with no
parseable `kit.yaml` never gets an install subcommand in the first place.

**Small blast radius.** One new file, one Koin registration, two call sites each reduced to a single
line, two test files, and the documentation. `CommandLineParser` loses its last use of
`java.io.File`, so that import must go or ktlint fails. `Constants` stays — line 135 still uses it.
The existing `@Suppress("TooGenericExceptionCaught")` is still needed for the surviving try/catch
around `buildKitGroup`.

**No `CommandLineParser` registration test.** There is none today, and building one pulls three Koin
dependencies plus a ClassGraph scan in `init`. Once the predicate moves out, the registration loop
has no branching the scanner test does not already cover, so the direct proof of "not registered"
rests on the scanner test rather than an end-to-end registration test. Recorded as a deliberate
trade-off, not an oversight.
