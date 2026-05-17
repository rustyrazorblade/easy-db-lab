---
name: push
description: Commit and/or push workflow for easy-db-lab. Run this skill before every git push. Runs ktlintFormat, handles commit confirmation, then pushes.
allowed-tools: Bash(*) Read Edit AskUserQuestion
---

You are executing the commit/push workflow for easy-db-lab. Follow the phases below based on what the user asked for.

---

## Determine Entry Point

If the user asked to **commit** (with or without push), start at **Phase 1**.
If the user asked only to **push** (no commit needed), skip to **Phase 3**.

---

## Phase 1: Summarize Changes and Confirm Scope

Run in parallel:
```bash
git status
git diff --stat
git diff
```

Summarize the changes to the user in plain language — what files changed and why (infer from the diff). Keep it concise: one line per logical group of changes.

Then ask:
- "Do you want to stage everything (`-A`), or select specific files?"

Wait for the user's answer before proceeding.

---

## Phase 2: Commit

Once the user has confirmed scope:

1. Run `./gradlew ktlintFormat` to auto-fix style issues before committing.
   - If ktlintFormat fails, stop and report the error. Do not proceed.
   - If it modified files, show which files were changed and re-stage them.

2. Draft a commit message following the project's conventional commit style:
   - Format: `type(scope): short description`
   - Common types: `feat`, `fix`, `refactor`, `test`, `docs`, `chore`
   - Keep the subject line under 72 characters
   - Do not attribute the commit to Claude

3. Show the proposed commit message and ask for approval. Wait for the user to say yes (or a variant) before committing.

4. Run `git commit` with the approved message. Use a HEREDOC to pass the message:
```bash
git commit -m "$(cat <<'EOF'
<message here>
EOF
)"
```

---

## Phase 3: Pre-Push ktlint (always)

Before every push, run:
```bash
./gradlew ktlintFormat
```

If ktlintFormat modified any files, stage and amend — but only if the user explicitly approved amending. Otherwise, create a new fixup commit (ask the user which they prefer).

If ktlintFormat fails (non-zero exit), stop and report the error. Do not push.

---

## Phase 4: Push

Run:
```bash
git push
```

Report success or failure. If the push is rejected (non-fast-forward), explain why and ask the user how to proceed — never force-push without explicit instruction.

---

## Notes

- Never commit without explicit user approval of the commit message.
- Never force-push without explicit user instruction.
- Never skip ktlintFormat — it must run before every push, no exceptions.
- If the user says "just push" (no commit), still run Phase 3 before Phase 4.
