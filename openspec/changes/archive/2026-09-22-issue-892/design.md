# Design — issue 892: `profile` command group with `profile show`

## Context

Design was produced by the `architect` agent, stress-tested by `design-critic`, and decided by the
owner at the activation design stop. Both agents verified picocli 4.7.7 behaviour by compiling and
running probes against the real jar rather than reasoning from documentation; their probes agreed.

## Structure

New package `commands/profile/`:

- `Profile.kt` — the group. `Runnable`, `@Command(name = "profile", mixinStandardHelpOptions = true,
  subcommands = [ProfileShow::class, SetupProfile::class])`, `run()` prints usage. The `Kit.kt`
  shape exactly.
- `ProfileShow.kt` — `@Command(name = "show")`, extends `PicoBaseCommand`, **no requirement
  annotations**.
- `SetupProfile.kt` — moved from `commands/`, renamed to `@Command(name = "setup")`, `aliases`
  removed.

`CommandLineParser` registers `Profile::class` and drops `SetupProfile::class` from the top-level
list. `Repl.kt:61`'s `ShellCommands` entry changes correspondingly.

`PicoCommand` moves out of `commands/` to a kernel package in the main module. Not `core` — it
imports `annotations.PreExecute`/`PostExecute`, which would drag that package along and widen the
change well past this issue. `PicoBaseCommand` stays in `commands/`; only the interface moves.

`services/ProfileSetupCommandProvider.kt` — a `fun interface` returning a `PicoCommand`, so
`CommandExecutor.checkRequirements()` names an abstraction instead of `SetupProfile()`. A provider
rather than a `ProfileSetupRunner { fun run() }`: a runner would call back into
`CommandExecutor.execute`, creating a Koin construction cycle that then needs a lazy `get()` to
break. The provider needs only the `SetupProfile` factory, so there is no wiring cycle to break.

## Report construction

```kotlin
companion object {
    fun buildReport(profileName: String, profileDir: String, user: User?): String
}

override fun execute() { … println(buildReport(…)) }
```

One function, one call site, following `KitList.buildListText` / `KitInfo.buildInfoText` so tests
assert on returned text rather than capturing stdout.

`buildReport` is the right surface for the report *text* and the wrong surface for anything about
the lifecycle — see **Test surface** below. That split is the single most important correction the
critic made to this design.

Use `getUserConfig()`, not `loadExistingConfig()`: the typed `User` is what provides
`isTailscaleEnabled()` (`User.kt:52`), which the acceptance criteria require the command to call
rather than re-derive. Guard with `isSetup()` first, since `loadUserConfig()` calls `error()` when
`settings.yaml` is absent (`UserConfigProvider.kt:76-81`).

Profile identity comes from `Context` — `context.profile` and `context.profileDir.absolutePath`
resolve `EASY_DB_LAB_PROFILE` at `Context.kt:49-51`. `Context`'s `init` runs `profileDir.mkdirs()`
(`Context.kt:57`), so the directory always exists; only `settings.yaml`'s presence distinguishes
configured from unconfigured. `UserConfigProvider` is already bound to the active profile —
`di/ContextModule.kt:29` constructs it as `UserConfigProvider(context.profileDir)`.

## Test surface

`buildReport` is a pure function of three plain values. It cannot observe what the command lifecycle
did, so it proves the report-text criteria and nothing else. A second, lifecycle-level test class
covers:

| Criterion | Why `buildReport` cannot prove it |
|---|---|
| Bare group prints help, exits 0 | Property of the picocli tree, not the formatter |
| No cluster state loaded | `buildReport` cannot load cluster state under any input, so the assertion is *vacuously* green — and would stay green through exactly the regression this guards against |
| Does not launch interactive setup | Property of `CommandExecutor.checkRequirements()` (`CommandExecutor.kt:228-236`) and of `ProfileShow`'s annotations. Cheapest real proof: assert `ProfileShow::class.annotations` carries no `RequireProfileSetup` |
| Non-default profile honored | `buildReport` receives the profile name as a parameter, so a test there proves only that it prints the string it was handed. Reachable only through `Context` construction |
| Old top-level names no longer resolve | Property of the picocli tree |

## Alternatives Considered

### The `setup-profile` alias — dropped entirely (owner decision)

The issue assumed the alias was a given and left only its *registration mechanism* open. The
architect probed four mechanisms against picocli 4.7.7:

| Candidate | Measured behaviour |
|---|---|
| `aliases = ["setup"]` on `@Command` | The alias becomes an extra key in the **same parent's** map — root map returned `[profile, setup-profile, setup]`. Aliases are sibling names; they cannot cross a level. |
| Same class in two `subcommands` arrays | Works; picocli builds two independent instances. But both take the name from the annotation, so you get `profile setup-profile`, not `profile setup`. |
| `addSubcommand("setup", CommandLine(SetupProfile()))` | Parses, and the group's help shows `setup`. But `spec.name()` stays `setup-profile` and `qualifiedName()` returns `root profile setup-profile` — the subcommand's own usage header prints the wrong path. |
| Thin subclass | Works completely. Root map `[profile, setup-profile]`, group map `[setup]`, both usage headers correct. |

The architect recommended the thin subclass. The critic then found a materially simpler variant the
architect never considered — **reversing the subclass direction**, leaving `SetupProfile` in place
as `setup-profile` and making the *new* class the subclass — which put the churn on new code instead
of pre-existing code, kept the 15 Kotlin references and both existing test classes put, and avoided
silently retagging events on `CommandExecutor`'s forced-setup path.

**The owner rejected the premise instead: drop `setup-profile` altogether.** Aliasing was a
nice-to-have. This removes the new class, the `open` keyword, the second Koin binding, and three of
the critic's minor findings (event retagging on the forced-setup path; the latent hook-inheritance
trap, since `PicoCommand.call()` selects hooks with `declaredMemberFunctions`, which excludes
inherited functions; and a silently-failing missing Koin binding).

Rejected alternatives, for the record:
- **`CommandSpec.forAnnotatedObject(...).name(...)`**, the `KitRunnerCommandFactory.kt:97`
  precedent. Renames correctly, but binds one instance into the spec for the process lifetime —
  the thing `factory { }` exists to avoid.
- **A delegating alias class** that calls `commandExecutor.execute { SetupProfile() }`. Runs a
  second full lifecycle inside the first and pushes two names onto `EventContext`.

### The emitted event command name — let it be `setup`

`PicoBaseCommand.call()` reads `this::class.java.getAnnotation(Command::class.java)?.name`
(`PicoBaseCommand.kt:48`) and pushes it onto `EventContext`; `EventBus.emit` stamps it onto
`EventEnvelope.commandName` (`EventBus.kt:31`). The architect grepped every `commandName` reference
in `src/main`: the only producers and consumers are `EventContext`, `EventBus`, and `EventEnvelope`
itself. No listener branches on the value — it is a serialized field for external subscribers.

**Rejected: emit the fully-qualified `profile setup`.** Not reachable from `@Command(name = ...)`,
which holds the leaf name only. It would require `PicoBaseCommand.call()` to push
`spec.qualifiedName()`, changing the emitted name for *every* grouped command in the tree at once —
`kit list`, `cassandra start`, `tailscale status`. A repository-wide change to the event stream
shape, and a separate issue.

**Rejected: an overridable `eventName` on `PicoBaseCommand`.** A new base-class mechanism for one
command.

### The 745 fold-in — full (owner decision)

- **Narrow** (the provider only): 6 files, 2 tests touched mechanically. Closes **no** cycle —
  `CommandExecutor.kt:10` still imports `commands.PicoCommand`, and that edge *is* the cycle.
- **Full** (provider + relocate `PicoCommand`): narrow's 6 plus 11 more files, every one a single
  import line; 5 tests, all one-line import edits. The 56 files importing `PicoBaseCommand` are
  unaffected — they extend the base class, not the interface.

The architect's headline claim was that full closes **two** of 745's cycles. **The critic showed
that is wrong.** `McpToolRegistry` names its 20 command classes by fully-qualified name, not by
import (`McpToolRegistry.kt:46-65`), so removing its `PicoCommand` import deletes a line and no
dependency; and `Server.kt:7` imports `mcp.McpServer`, so the reverse edge stands. `mcp↔commands`
survives either way, and since ArchUnit works on bytecode, 745's eventual rule would still fail on
it. Full closes **one** cycle.

The `commands↔services` half holds: the critic grepped for fully-qualified references as well as
imports, and `CommandExecutor.kt:10-11` are the only two `commands` references anywhere under
`services/`.

Owner chose full on the corrected framing.

### The secret-omission criterion — simplified (owner decision)

The original AC forbade "no substring of any of them, in plain, masked, or truncated form". The
critic showed this is **unsatisfiable**: every non-empty secret has one-character substrings and the
report prints letters. It is also value-dependent rather than a property of the code —
`awsSecret` set to `us-east-1` while `region` is `us-east-1` fails a `doesNotContain` assertion
though nothing leaked.

The critic proposed two shapes that would hold for all values: a property test (no contiguous run of
4+ characters from any secret), or a `ProfileReportData` projection so secrets never reach the
formatter at all.

**The owner judged both overkill for five fields** and restated the criterion as: the output
contains none of those five values. Sentinel-value test, `doesNotContain` per secret.

The residual risk is named rather than designed away: `SetupProfile.maskValue()`
(`SetupProfile.kt:357-362`) — in the sibling file this change edits — returns `"${value[0]}****"`,
and a `buildReport` reusing it would leak a first character and pass. `buildReport` must not call
any masking helper.

### Report input — pass `User` directly

**Rejected: a `ProfileReportData` value type.** It would make leakage structurally impossible inside
the formatter, but relocates the leak risk into an unmapped projection step and adds a type for one
call site. With the simplified criterion, the direct-`User` test is adequate evidence.

## Domain Facts

No domain-expert agent was consulted — this is CLI structure, not a database-domain question.

## Risks

- **`Repl.kt:38-68` is a second, hand-maintained copy of the command tree**, listing
  `SetupProfile::class` at line 61 independently of `CommandLineParser`. Required edit. The critic
  confirmed no *other* hand-maintained copy exists: `Commands.kt` walks the live picocli tree at
  runtime, `McpToolRegistry` names no profile or setup command, and the repo ships no completion
  script. It also confirmed `ReplTest` asserts only on `status`, `cassandra`, `spark`, `cls` and
  `cassandra stress`, so **nothing existing tests this edit** — it depends on the implementer
  remembering it.
- **`ProfileShow` must carry no requirement annotation.** `checkRequirements()`
  (`CommandExecutor.kt:228-236`) responds to `@RequireProfileSetup` by running `SetupProfile()` and
  `exitProcess(0)`. An annotation added later "for consistency" breaks the command's whole purpose.
- **`profile show` loads no cluster state, and that must stay true by omission.** `PicoBaseCommand`
  exposes `clusterState` as `by lazy`, so not naming it means no `state.json` read. A later reviewer
  adding a "helpful" cluster line breaks the no-workspace criterion — which is why that criterion is
  tested at the lifecycle level, where the regression is visible.
- **The AC3 claim carries one unstated condition.** `executeTopLevel` calls
  `handleVpcReconstructionFromEnv()` before every command (`CommandExecutor.kt:124`), which returns
  immediately unless `EASY_DB_LAB_RESTORE_VPC` is set (`CommandExecutor.kt:355`). The criterion
  holds, but not unconditionally.
- **Six user-facing strings name `setup-profile`**: `Event.kt:2881`, `Event.kt:3632`,
  `UserConfigProvider.kt:79`, `providers/aws/AWS.kt:260`, `containers/Packer.kt:86`,
  `commands/tailscale/TailscaleStart.kt:91`. Nine more in KDoc and comments. Live total outside the
  two archive paths is 33 lines, 15 Kotlin — the issue's count; the architect's 34/19 was an
  arithmetic error against its own enumeration.
- **Register `ProfileShow` in `di/CommandsModule.kt`.** `KoinCommandFactory` falls back to picocli's
  default factory when Koin has no binding (`KoinCommandFactory.kt:22-30`), and the fallback happens
  to work because `PicoBaseCommand` is a `KoinComponent` — so an omission fails **silently**.
- **A workspace directory named `profile` holding a `kit.yaml`** stops registering as a kit command
  (`CommandLineParser.kt:267`). Improbable, but a real behaviour change.

## Structural debt near this change, not folded in

Recommended as separate issues; none filed. The owner was shown these at the design stop and left
them for later.

- `Repl.ShellCommands` duplicates the root command tree and has drifted — missing `Kit`,
  `Tailscale`, `Logs`, `Metrics`, `Platform`, `Grafana`, `Cleanup`, `Down`, `Server`, `Commands`.
  The real fix is one shared subcommand list behind both roots.
- `Context`'s constructor performs I/O (`profileDir.mkdirs()`, `Context.kt:57`).
- `UserConfigProvider.loadUserConfig()` emits user-facing CLI guidance from a persistence class
  (`UserConfigProvider.kt:76-81`).
- `McpToolRegistry.mcpCommandClasses` is a hardcoded 20-class literal (`McpToolRegistry.kt:44-66`) —
  the underlying reason MCP exposure of `profile show` is out of scope.

Folded in: the dead branch in `SetupProfile.maskValue()` (`SetupProfile.kt:357-362`), where
`value.length == 1` returns exactly what `else` returns. Two-line deletion in a file this change
already edits.
