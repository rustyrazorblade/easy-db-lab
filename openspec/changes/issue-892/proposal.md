# Add a `profile` command group with `profile show`

## Why

Nothing in the CLI reports what the active profile actually holds. `status` prints the *cluster's*
bucket from `state.json` (`Status.kt:397-415`), not the profile's, and it fails outside a cluster
workspace. The only way to read the profile's `s3Bucket` today is a hand-written grep of
`~/.easy-db-lab/profiles/<profile>/settings.yaml`.

That gap is worst exactly when it matters most. A user debugging a misconfigured profile has no
command to ask "what am I configured with?", and the multi-profile mechanism
(`EASY_DB_LAB_PROFILE`) makes "which profile am I even on?" a real question with no answer.

Setup is also mis-shelved. `setup-profile` sits at the top level with a bare `setup` alias, while
every comparable area of the CLI — `kit`, `tailscale`, `cassandra` — groups its operations under a
parent command. Adding profile inspection as a second top-level command would compound that; adding
it under a `profile` group fixes the shelving and creates the obvious home for later `profile list`
and `profile use`.

## What Changes

**A new `profile` top-level command group**, following `kit` and `tailscale`. The group class is a
`Runnable`, not a `PicoCommand` — the execution strategy at `CommandLineParser.kt:161-167` routes
only `PicoCommand` through `CommandExecutor`, so a bare `easy-db-lab profile` falls through to
`CommandLine.RunLast()`, prints usage, and exits 0. `Kit` already proves this path.

**A new `profile show` command** that reports the active profile's name, absolute directory,
`email`, `region`, `keyName`, `awsProfile`, and `s3Bucket`, plus `ENABLED`/`DISABLED` flags for
AxonOps and Tailscale. It prints none of the five secret values. It carries no requirement
annotation and reads no cluster state, so it works in any directory. Report text is built by a
`companion object` function so it is unit-testable without capturing stdout, following `KitList`
and `KitInfo`.

It has three branches, not two: configured, no `settings.yaml`, and — new — a `settings.yaml` that
exists but cannot be deserialized. `isSetup()` is a bare `exists()` check
(`UserConfigProvider.kt:42`) while `User` has six constructor parameters with no defaults
(`User.kt:31-37`), so a truncated or hand-edited file currently throws a raw Jackson error through
the very command added to diagnose it.

**`setup-profile` becomes `profile setup`, with both old names removed.** The bare `setup` alias
and the top-level `setup-profile` name both stop resolving. All 33 live `setup-profile` references
are updated, plus two docs passages naming the bare `setup` alias that contain no `setup-profile`
string. `Repl.kt:38-68` holds a second, hand-maintained copy of the command tree and needs its own
edit.

**The `commands↔services` package dependency cycle is closed** — one of the seven cycles named in
745. `PicoCommand` moves out of `commands/` to a kernel package in the main module, and a
`ProfileSetupCommandProvider` in `services/` replaces `CommandExecutor`'s direct construction of
`SetupProfile()`. `CommandExecutor.kt:10-11` are the only two `commands` references anywhere under
`services/`.

## Impact

- **Breaking, deliberately.** `easy-db-lab setup` and `easy-db-lab setup-profile` stop resolving.
  Aliasing was a nice-to-have and is not worth carrying a second class for.
- The emitted event command name for setup changes from `setup-profile` to `setup`. Nothing in the
  repo branches on `commandName`; it is a serialized field for external subscribers.
- The tree will carry two unrelated `profile` groups under different parents — the new one and the
  existing `cassandra profile` (`commands/cassandra/profiler/Profiler.kt:25`). Not a conflict.
- A workspace directory named `profile` holding a `kit.yaml` stops registering as a kit command
  (`CommandLineParser.kt:267`).
- Does **not** add the ArchUnit cycle rules. They would fail immediately on the five cycles this
  change does not touch; 745 owns them.
