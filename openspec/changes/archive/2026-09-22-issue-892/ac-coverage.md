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
| Risk | `ProfileShow` must carry no `@RequireProfileSetup`, or setup launches instead of reporting | `profile-command-group: Missing settings.yaml reports not-configured` (the "interactive setup flow does not run" clause) | ✅ Covered — pinned by `ProfileCommandGroupTest`'s `ProfileShow carries no requirement annotation`, which checks all four requirement annotations directly. The scenario's own test is a pure `buildReport` call, which cannot see a class annotation and would stay green if one were added — the annotation test is what actually guards this |
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

**`buildReport`'s signature.** `design.md` and `tasks.md` 3.2 both specify
`buildReport(profileName: String, profileDir: String, user: User?)`. What shipped is
`buildReport(profileName, profileDir, settings: ProfileSettings)`, where `ProfileSettings` is a new
sealed interface with `Loaded(user)`, `Missing` and `Unreadable` cases.

The approved signature was internally contradictory: a nullable `User` has two states, while
`tasks.md` 3.3 required three branches and 4.1 required `buildReport` tests for both the
not-configured and the malformed messages. Those cannot both be satisfied at that signature. The
sealed type preserves what the design actually argued for — a pure function of three values with one
call site, both branches testable without capturing stdout — and `Loaded` passes the `User` straight
through, so the design's explicit "pass `User` directly" decision stands and the reason it rejected a
`ProfileReportData` projection does not apply.

No spec scenario constrains the signature, so this was correctly not raised as a `SPEC-DEFECT`. It is
recorded here because the AxonOps amendment above was recorded and this deviation is the same kind of
departure from an approved artifact; documenting one and not the other would misrepresent the branch.
`design.md` and `tasks.md` 3.2 still carry the original signature — under the read-only-spec rule,
editing them is the owner's call, and this section is the established place for the record.

## A note on what "Covered" means in this table

The *Covering scenario(s)* column names **spec scenarios**, not tests. A reader who takes ✅ Covered
to mean "verified by a test" can be misled, and was: three scenarios reached the review panel with
no implementing test, and one row claimed an assertion that did not exist.

Fix round 1 closed all four gaps. The rows corrected as a result — the setup-binding row, the
`println()`/no-events row, the docs-by-inspection row, the `profile` kit-directory risk row, and the
`@RequireProfileSetup` risk row — now name the test that pins them, or say plainly that they are
verified by inspection. The remaining ✅ rows still name only their spec scenario; the review panel
walked each of those to a real implementing assertion, so none overclaims, but the column is not a
test index and should not be read as one.

Scenario-to-test traceability on the merged branch is 16 of 17. The one uncovered scenario is
`setup: Guidance names the current command`, verified by inspection as its row states.
