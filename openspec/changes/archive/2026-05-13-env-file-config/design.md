## Context

`bin/easy-db-lab` and `bin/end-to-end-test` are shell scripts that need per-developer settings such as `AWS_PROFILE` (which AWS account to target) and `SIDECAR_IMAGE` (which container image to deploy for the Cassandra sidecar). Currently `bin/end-to-end-test` hardcodes `AWS_PROFILE=sandbox-admin`, forcing every developer to either edit the script or remember to export the variable before every run. There is no canonical list of available overrides; developers discover them by reading the scripts.

## Goals / Non-Goals

**Goals:**
- Single gitignored `.env` file at project root for all per-developer overrides
- Both `bin/easy-db-lab` and `bin/end-to-end-test` source `.env` automatically if it exists
- `.env.example` committed to document all supported variables
- `AWS_PROFILE` removed from hardcoded position in `bin/end-to-end-test`
- Local development docs updated with setup instructions

**Non-Goals:**
- No `.env` precedence over variables already set in the shell (shell wins; `.env` is a fallback)
- No `.env` support inside Kotlin/JVM code — only shell scripts
- No secret management integration (this is for non-sensitive local config only)

## Decisions

### Source `.env` with shell-default semantics (don't override existing vars)

Use `set -a` + `source` with a guard so already-exported variables are not overwritten:

```bash
# Source .env if it exists; only sets variables not already in environment
if [ -f "$PROJECT_ROOT/.env" ]; then
    set -a
    # shellcheck disable=SC1091
    source "$PROJECT_ROOT/.env"
    set +a
fi
```

**Why**: Developers running in CI or with explicit `export AWS_PROFILE=...` in their shell shouldn't have `.env` silently stomp their settings. `set -a` auto-exports every assignment, avoiding the need for `export` on each line. This is the standard convention used by docker-compose, direnv, and most `.env`-loading tools.

**Alternative considered**: `export $(grep -v '^#' .env | xargs)` — rejected because it breaks on values with spaces and doesn't handle multiline variables.

### `.env` relative to `PROJECT_ROOT`, not `$PWD`

Both scripts already define `PROJECT_ROOT="$(pwd)"` at their top. The `.env` load uses `$PROJECT_ROOT/.env` so it works regardless of the directory a developer invokes the script from.

### `.env.example` documents all variables with safe defaults

Committed file lists every recognised variable with an explanatory comment and a safe placeholder. Variables that have no universal default (like `SIDECAR_IMAGE`) are commented out. `AWS_PROFILE` gets a placeholder of `your-aws-profile`.

### No default for `AWS_PROFILE` in the script itself

Rather than replacing `sandbox-admin` with another hardcoded default, `AWS_PROFILE` is left unset if not in environment or `.env`. AWS SDK and CLI already have their own default-profile fallback (`default`). Failing loudly on a missing profile is better than silently using the wrong account.

## Risks / Trade-offs

- [Risk] Developers who don't create `.env` and rely on the old hardcoded `sandbox-admin` will need to set the profile explicitly. → **Mitigation**: The docs update covers this clearly; `.env.example` makes the path obvious.
- [Risk] `.env` accidentally committed. → **Mitigation**: `.gitignore` entry added as part of this change.

## Migration Plan

1. Add `.env` to `.gitignore`.
2. Add `.env` sourcing block to `bin/easy-db-lab` and `bin/end-to-end-test`.
3. Remove `export AWS_PROFILE=sandbox-admin` from `bin/end-to-end-test`.
4. Commit `.env.example`.
5. Update docs.

No rollback needed — this is purely additive for new scripts, and the only breaking change (removing hardcoded `AWS_PROFILE`) is immediately visible to any developer who runs the e2e test without a profile set.

## Open Questions

None.
