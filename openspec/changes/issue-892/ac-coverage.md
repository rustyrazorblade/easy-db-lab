# AC coverage — issue 892

One row per acceptance criterion on the issue, plus one per risk the architect and design-critic
surfaced.

| Source | Requirement | Covering scenario(s) | Status |
|--------|-------------|----------------------|--------|
| AC | Bare group prints help, lists `show` and `setup`, exits 0 | `profile-command-group: profile with no subcommand prints help` | ✅ Covered |
| AC | `profile show` prints profile name, absolute directory, and all five settings | `profile-command-group: Configured profile reports its settings` | ✅ Covered |
| AC | Works with no cluster workspace; no cluster state loaded, no cluster-state error | `profile-command-group: Runs outside a cluster workspace` | ✅ Covered |
| AC | No secret value is printed | `profile-command-group: Secret values are absent from the report` | ✅ Covered |
| AC | AxonOps status is a flag | `profile-command-group: AxonOps enabled when a key is present` + `AxonOps disabled when no key is present` | ✅ Covered |
| AC | Tailscale status is a flag, via `User.isTailscaleEnabled()` | `profile-command-group: Tailscale enabled when both credentials are present` + `Tailscale disabled when either credential is missing` | ✅ Covered |
| AC | Not-set-up profile gives a message, not interactive setup | `profile-command-group: Missing settings.yaml reports not-configured` | ✅ Covered |
| AC | Malformed profile is reported, not thrown | `profile-command-group: Truncated settings.yaml is reported, not thrown` | ✅ Covered |
| AC | Non-default profile is honored | `profile-command-group: Non-default profile is reported` | ✅ Covered |
| AC | `profile setup` runs the existing setup flow | `profile-command-group: profile setup runs the setup workflow` + `setup: First-time user runs setup` | ✅ Covered |
| AC | Neither `setup` nor `setup-profile` resolves at top level | `setup: Former top-level names no longer resolve` | ✅ Covered |
| AC | Output is one block via `println()`, emits no events | `profile-command-group: Configured profile reports its settings` (asserts no `Event` for report content) | ✅ Covered |
| AC | Docs name the new form, including bare-`setup` references | `setup: Guidance names the current command` | ✅ Covered |
| AC | The `commands↔services` cycle is closed | — | ⚠️ Excluded — a structural invariant, not observable product behavior. Verified by task 1.5 and enforced later by the ArchUnit rules that belong to 745; encoding it as a product requirement here would misplace ownership. |
| Risk | `Repl.kt:38-68` holds a second command-tree copy; no existing test catches the drift | — | ⚠️ Excluded — internal wiring with no distinct user-visible contract beyond the two command-resolution scenarios already specified. Carried as task 2.4 with the "no test catches this" warning attached. |
| Risk | `ProfileShow` must carry no `@RequireProfileSetup`, or setup launches instead of reporting | `profile-command-group: Missing settings.yaml reports not-configured` (the "interactive setup flow does not run" clause) | ✅ Covered |
| Risk | `profile show` must not load cluster state, and must stay that way | `profile-command-group: Runs outside a cluster workspace` | ✅ Covered |
| Risk | `buildReport` reusing `maskValue()` would leak a first character and still pass a sentinel test | `profile-command-group: Secret values are absent from the report` (requirement text forbids calling any masking helper) | ✅ Covered |
| Risk | A workspace directory named `profile` with a `kit.yaml` shadows the group | `profile-command-group: A profile kit directory is skipped` | ✅ Covered |
| Risk | Missing Koin binding for `ProfileShow` fails silently via `KoinCommandFactory`'s fallback | — | ⚠️ Excluded — a wiring convention with no distinct observable behavior; the fallback makes the command work either way. Carried as task 3.5. |
| Risk | Emitted event command name changes from `setup-profile` to `setup` | — | ⚠️ Excluded — deliberate consequence of the rename, recorded in `proposal.md` Impact. Nothing in the repo branches on `commandName`; it is a serialized field for external subscribers, and no committed spec constrains its value. |
| Risk | AC3's no-cluster-state claim is conditional on `EASY_DB_LAB_RESTORE_VPC` being unset | — | ⚠️ Excluded — an environment-variable escape hatch outside this change's scope; noted in `design.md` Risks so the claim is not read as unconditional. |
