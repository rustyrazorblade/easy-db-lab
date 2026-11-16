# agent-test skill

Dynamic end-to-end test runner that calls `easy-db-lab` commands directly.

## What it does

- Calls `easy-db-lab` commands directly, in its own workspace under `clusters/`.
- Generates its test plan from the branch's changes and reads the actual diffs for targeted
  verification.
- Investigates a failing step inline, and can adjust, skip, or add steps as needed.

## Usage

```bash
/agent-test                    # Auto-detect from branch, propose plan
/agent-test --cassandra        # Test Cassandra, propose plan
/agent-test --all              # Full suite, propose plan
/agent-test --cassandra --yes  # Skip confirmation, run immediately
/agent-test --no-teardown      # Keep cluster after tests
```

## What it does

1. Reads `git diff` to understand what changed and why
2. Proposes a specific test plan with the exact commands it will run
3. Gets confirmation (unless `--yes`)
4. Executes each command and reports results in real-time
5. Investigates inline when a step fails — checks pods, logs, SSH
6. Provides root cause and recommended fix
7. Tears down cluster on success (unless `--no-teardown`)
