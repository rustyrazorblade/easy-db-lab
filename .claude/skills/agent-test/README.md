# agent-test skill

Dynamic end-to-end test runner that calls `easy-db-lab` commands directly.

## How it differs from `/e2e-test`

| | `/e2e-test` | `/agent-test` |
|--|--|--|
| Execution | Wraps `bin/end-to-end-test` | Calls `easy-db-lab` commands directly |
| Test plan | Fixed step list | Generated dynamically from branch changes |
| Debugging | Post-hoc via `/debug-environment` | Inline at each failing step |
| Adaptability | Flags only | Can adjust, skip, or add steps as needed |
| Diff awareness | File-level (flags) | Reads actual diffs for targeted verification |

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
