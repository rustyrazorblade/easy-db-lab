# Overrides and conflicts — issue 892

## Overrides existing behavior

### setup: Profile Setup

**Currently:**

> The system MUST provide an interactive workflow to create a user profile with AWS credentials,
> region, and key pair configuration.

Two scenarios, both naming the workflow generically ("run the setup workflow", "runs setup") — the
baseline requirement does not pin a command name at all.

**This change:** same guarantee, plus an explicit command name and the removal of the two former
top-level names:

> The workflow SHALL be reached as `easy-db-lab profile setup`. The former top-level names
> `setup-profile` and `setup` SHALL NOT resolve.

Two scenarios are added: one asserting neither old name resolves, one requiring any CLI message that
directs a user to configure their profile to name `easy-db-lab profile setup`.

**This is a breaking user-visible change, made deliberately.** `easy-db-lab setup` and
`easy-db-lab setup-profile` both stop working. The owner decided at the design stop that aliasing
was a nice-to-have not worth carrying a second class for. Nothing in the baseline spec promised
either name — the requirement was command-name-agnostic — so no committed promise is being broken;
the change makes the spec stricter than it was, not different from it.

No `REMOVED Requirements` sections in this change.

## Conflicts with other in-flight changes

One other open change exists: `issue-888`. It touches `kit-install-command` and `workload-runner`.
This change touches `profile-command-group` (new) and `setup`. No capability is shared, so there is
no conflict.

`profile-command-group` is a new capability with no baseline file, so it cannot conflict with
anything on the current baseline either.
