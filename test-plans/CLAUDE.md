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

Every plan creates a timestamped workspace under `clusters/` and generates an `easy-db-lab` wrapper in it using `bin/create-easy-db-lab-wrapper`. The wrapper handles the correct `JAVA_HOME` (the highest-installed Java 21 via SDKMAN, or the newest JDK >= 21 if no 21 is present) and `cd` automatically — no manual path or Java setup needed in the plan steps.

Single-DC pattern:

```bash
CLUSTER_DIR="clusters/<test-name>-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$CLUSTER_DIR"
bin/create-easy-db-lab-wrapper "$CLUSTER_DIR"
EDB="$CLUSTER_DIR/easy-db-lab"
```

Multi-DC pattern:

```bash
CLUSTER_BASE="clusters/<test-name>-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$CLUSTER_BASE/dc1" "$CLUSTER_BASE/dc2"
bin/create-easy-db-lab-wrapper "$CLUSTER_BASE/dc1"
bin/create-easy-db-lab-wrapper "$CLUSTER_BASE/dc2"
DC1="$CLUSTER_BASE/dc1/easy-db-lab"
DC2="$CLUSTER_BASE/dc2/easy-db-lab"
```

All subsequent steps use `$EDB` (or `$DC1`/`$DC2`) directly with no `cd` prefix.

## Writing a new plan

Use `/easy-db-lab:plan` with a description of what you want to test. The skill will ask questions and write the plan here. Name it `<scenario>.md`.

Every plan must:
1. Create the workspace directory and run `bin/create-easy-db-lab-wrapper` as its first step
2. Assign the wrapper path to a variable (`EDB`, `DC1`, `DC2`, etc.)
3. Use that variable for every subsequent `easy-db-lab` command — never use `bin/easy-db-lab` directly or prefix with `cd`
4. End with `$EDB down --auto-approve`
