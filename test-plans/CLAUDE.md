# Test Plans

This directory contains lab test plans for easy-db-lab. Each plan is a step-by-step script for provisioning a cluster, running a specific test scenario, and tearing it down.

## What belongs here

One file per test scenario, named descriptively:
- `cassandra-5.0-validation-3node.md` — validates all `easy-db-lab cassandra` subcommands
- `presto-validation.md` — full Presto kit lifecycle with Cassandra backend

## How to use with `/easy-db-lab:run`

Pass the plan path as the argument:

```
/easy-db-lab:run test-plans/presto-validation.md
```

The skill reads the plan, shows a numbered summary, and executes each step one at a time with confirmation.

## Cluster workspace directories

**`/easy-db-lab:run` creates the workspace before executing any plan step** — a timestamped directory
under `clusters/`, an `easy-db-lab` wrapper that `cd`s into it, and the lab-report scaffold
(`docs/book.toml`, `SUMMARY.md`, `Makefile`, `journal.md`, `issues.md`). Plans must **not** scaffold
their own workspace; doing so duplicates what `run` already did and can leave the workspace unable to
build its report.

`run` sets `$EDB` (single DC) or `$EDB_DC1`/`$EDB_DC2`/… (multi-DC) after scaffolding. Plan steps use those
directly, with no `cd` prefix.

## Writing a new plan

Use `/easy-db-lab:plan` with a description of what you want to test. The skill will ask questions and write the plan here. Name it `<scenario>.md`.

Every plan must:
1. Begin with provisioning (`$EDB init ... --up`) — **not** with workspace or wrapper creation
2. Use `$EDB` (or `$EDB_DC1`/`$EDB_DC2`) for every `easy-db-lab` command — never `bin/easy-db-lab` directly, and never prefix with `cd`
3. Derive any workspace path it needs from the wrapper rather than hardcoding one, e.g. `CLUSTER_DIR=$(dirname "$EDB")`
4. End with `$EDB down --auto-approve`
