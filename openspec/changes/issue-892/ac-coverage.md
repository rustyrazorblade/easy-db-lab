# AC coverage — issue 892

One row per acceptance criterion on the issue, plus one per risk the architect and design-critic
surfaced.

| Source | Requirement | Covering scenario(s) | Status |
|--------|-------------|----------------------|--------|
| AC | Bare group prints help, lists `show` and `setup`, exits 0 | `profile-command-group: profile with no subcommand prints help` | ✅ Covered |
| AC | `profile show` prints profile name, absolute directory, and all five settings | `profile-command-group: Configured profile reports its settings` | ✅ Covered |
| AC | Works with no cluster workspace; no cluster state loaded, no cluster-state error | `profile-command-group: Runs outside a cluster workspace` | ✅ Covered |
| AC | No secret value is printed | `profile-command-group: Secret values are absent from the report` | ✅ Covered |
| AC | AxonOps status is a flag | `profile-command-group: AxonOps enabled when both the org and the key are present` + `AxonOps disabled when either credential is missing` | ✅ Covered — scenario amended during implementation, see note below |
| AC | Tailscale status is a flag, via `User.isTailscaleEnabled()` | `profile-command-group: Tailscale enabled when both credentials are present` + `Tailscale disabled when either credential is missing` | ✅ Covered |
| AC | Not-set-up profile gives a message, not interactive setup | `profile-command-group: Missing settings.yaml reports not-configured` | ✅ Covered |
| AC | Malformed profile is reported, not thrown | `profile-command-group: Truncated settings.yaml is reported, not thrown` | ✅ Covered |
| AC | Non-default profile is honored | `profile-command-group: Non-default profile is reported` | ✅ Covered |
| AC | `profile setup` runs the existing setup flow | `profile-command-group: profile setup runs the setup workflow` + `setup: First-time user runs setup` | ✅ Covered — `SetupProfileTest` covers the workflow; `ProfileCommandGroupTest` asserts the `setup` key is bound to `SetupProfile` (added in fix round 1, tr-3) |
| AC | Neither `setup` nor `setup-profile` resolves at top level | `setup: Former top-level names no longer resolve` | ✅ Covered |
| AC | Output is one block via `println()`, emits no events | `profile-command-group: Configured profile reports its settings` | ✅ Covered — `ProfileCommandGroupTest` asserts the `BufferedOutputHandler`'s `messages` and `errors` are empty after `execute()`; `TestModules.testEventBusModule()` forwards every emitted event to that handler, so empty is direct proof no event was emitted (added in fix round 1, tr-4) |
| AC | Docs name the new form, including bare-`setup` references | `setup: Guidance names the current command` | ✅ Covered — **verified by inspection, not by test.** Zero live `setup-profile` or bare-`setup` references remain outside the two archive paths. A guard test would be a grep-over-sources test: brittle, low value, deliberately not written. |
| AC | The `commands↔services` cycle is closed | — | ⚠️ Excluded — a structural invariant, not observable product behavior. Verified by task 1.5 and enforced later by the ArchUnit rules that belong to 745; encoding it as a product requirement here would misplace ownership. |
| Risk | `Repl.kt:38-68` holds a second command-tree copy; no existing test catches the drift | — | ⚠️ Excluded — internal wiring with no distinct user-visible contract beyond the two command-resolution scenarios already specified. Carried as task 2.4 with the "no test catches this" warning attached. |
| Risk | `ProfileShow` must carry no `@RequireProfileSetup`, or setup launches instead of reporting | `profile-command-group: Missing settings.yaml reports not-configured` (the "interactive setup flow does not run" clause) | ✅ Covered |
| Risk | `profile show` must not load cluster state, and must stay that way | `profile-command-group: Runs outside a cluster workspace` | ✅ Covered |
| Risk | `buildReport` reusing `maskValue()` would leak a first character and still pass a sentinel test | `profile-command-group: Secret values are absent from the report` (requirement text forbids calling any masking helper) | ✅ Covered |
| Risk | A workspace directory named `profile` with a `kit.yaml` shadows the group | `profile-command-group: A profile kit directory is skipped` | ✅ Covered — `CommandLineParserTest` creates `<tempCwd>/profile/kit.yaml` and asserts the `profile` subcommand is still a `Profile` instance (added in fix round 1, tr-2). Before that test the behavior rested on one untested line, `CommandLineParser.kt:272` |
| Risk | Missing Koin binding for `ProfileShow` fails silently via `KoinCommandFactory`'s fallback | — | ⚠️ Excluded — a wiring convention with no distinct observable behavior; the fallback makes the command work either way. Carried as task 3.5. |
| Risk | Emitted event command name changes from `setup-profile` to `setup` | — | ⚠️ Excluded — deliberate consequence of the rename, recorded in `proposal.md` Impact. Nothing in the repo branches on `commandName`; it is a serialized field for external subscribers, and no committed spec constrains its value. |
| Risk | AC3's no-cluster-state claim is conditional on `EASY_DB_LAB_RESTORE_VPC` being unset | — | ⚠️ Excluded — an environment-variable escape hatch outside this change's scope; noted in `design.md` Risks so the claim is not read as unconditional. |

## Amendments after Seam 1

The owner approved this change at Seam 1 on 2026-09-05. One committed scenario was amended during
implementation, with the owner's explicit approval, and it is recorded here rather than silently
edited.

**`profile-command-group`: the AxonOps flag scenarios.** As approved, the scenario read *GIVEN
`axonOpsKey` is non-empty THEN the output reports AxonOps as `ENABLED`*, with no condition on
`axonOpsOrg`. The correctness lens found that both real consumers — `Up.kt:706` and
`cassandra/Start.kt:78` — require **both** fields to be non-blank before enabling AxonOps, so the
approved scenario described a report that would assert a capability the next `up` or `cassandra
start` would silently skip. Two reachable states produced the wrong answer: a key set with a blank
org (which `SetupProfile` can produce, since it prompts for the two independently and either can be
skipped), and a whitespace-only key.

The scenario encoded the bug, so the code could not be both correct and conformant. The implementer
raised it as a `SPEC-DEFECT` rather than editing the spec to match its own code. The owner chose to
amend the scenario. The requirement now names `User.isAxonOpsEnabled()` as the shared predicate and
requires both fields; `Up.kt` and `cassandra/Start.kt` were repointed at that same predicate, so one
definition replaces three copies of the rule.

Issue AC5 and `tasks.md` 3.4 were updated to match.

## A note on what "Covered" means in this table

The *Covering scenario(s)* column names **spec scenarios**, not tests. A reader who takes ✅ Covered
to mean "verified by a test" can be misled, and was: three scenarios reached the review panel with
no implementing test, and one row claimed an assertion that did not exist. Fix round 1 closed all
four gaps, and each row above now names the test that pins it, or says plainly that it is verified
by inspection instead.
