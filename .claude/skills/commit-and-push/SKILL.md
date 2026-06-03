---
name: commit-and-push
description: Commit and/or push workflow for easy-db-lab. Run this skill before every git push. Runs ktlintFormat, handles commit confirmation, then pushes. Pass -a to stage all files without prompting.
allowed-tools: Bash(*) Read Edit AskUserQuestion
---

You are executing the commit/push workflow for easy-db-lab. Follow the phases below based on what the user asked for.

---

## Determine Entry Point

If the user asked to **commit** (with or without push), start at **Phase 1**.
If the user asked only to **push** (no commit needed), skip to **Phase 3**.

Check the args passed to this skill. If `-a` is present, set AUTO_STAGE=true.

---

## Phase 1: ktlint (always)

Before every push, run:
```bash
./gradlew ktlintFormat
```

## Phase 2: Summarize Changes and Confirm Scope

Run in parallel:
```bash
git status
git diff --stat
git diff
```

Summarize the changes to the user in plain language — what files changed and why (infer from the diff). Keep it concise: one line per logical group of changes.

Then:
- If **AUTO_STAGE=true**: proceed immediately — do not ask.
- Otherwise: ask "Do you want to stage everything (`-A`), or select specific files?" and wait for the answer.

---

## Phase 3: Commit

Once scope is confirmed:

1. Run `./gradlew ktlintFormat` to auto-fix style issues **before staging**.
   - If ktlintFormat fails, stop and report the error. Do not proceed.
   - If it modified files, show which files were changed.

2. Stage files (run `git add -A` for AUTO_STAGE=true, or add the user-selected files).

4. Draft a commit message following the project's conventional commit style:
   - Format: `type(scope): short description`
   - Common types: `feat`, `fix`, `refactor`, `test`, `docs`, `chore`
   - Keep the subject line under 72 characters
   - Do not attribute the commit to Claude

5. Show the proposed commit message and ask for approval. Wait for the user to say yes (or a variant) before committing.  Use a menu so the user can just hit enter.

6. Run `git commit` with the approved message. Use a HEREDOC to pass the message:
```bash
git commit -m "$(cat <<'EOF'
<message here>
EOF
)"
```

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
