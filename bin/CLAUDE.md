# bin/ Scripts

## test

Single entrypoint for all integration tests.

```
bin/test [-p] [-t] [--no-build] <test-name>
```

Handles all common infrastructure: logging, build, cluster provisioning, `easy-db-lab up`, sourcing `env.sh`, and optional teardown on exit. Then delegates to `bin/tests/<test-name>` for the test-specific steps.

**When to use this:** Use the `/run-test` skill — it handles provisioning decisions, surfaces the `Follow:` line, monitors progress, and reports results. Do not call `bin/test` directly unless the user explicitly asks.

When running this script, it prints a `Follow:` line near the top of its output with the exact `tail -f -n 100 <logfile>` command. **Always surface that line to the user** so they can open a second terminal and follow along.

**Never pass `-t` (teardown) unless the user explicitly asks.** Leave the cluster running on failure so they can debug. When the user says "shut it down", run `easy-db-lab down --yes`.

Flags: `-p`/`--provision` (init + up a fresh cluster first), `-t`/`--teardown` (destroy cluster on exit), `--no-build` (skip `./gradlew installDist`).

Log files are written to `logs/test-<name>-YYYYMMDD-HHMMSS.log`.

## tests/

Individual test scripts. Each handles only its own test steps — no provisioning logic. The `step` helper function is exported by `bin/test` and available in all test scripts.

### tests/presto

Installs Cassandra 5 and Presto on a cluster and verifies Presto is running.

```
bin/test presto           # run against an already-up cluster
bin/test -p presto        # provision a fresh cluster first
bin/test -p -t presto     # provision, test, then tear down
```
