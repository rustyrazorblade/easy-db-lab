# bin/ Scripts

## easy-db-lab

The local-development CLI entrypoint. It does **not** launch the JVM itself — it delegates to the Gradle-generated start script (`build/install/easy-db-lab/bin/easy-db-lab`), which is the single source of truth for the classpath, the OTel java agent, and `easydblab.apphome`/`easydblab.version` (#727). Build it first with `./gradlew installDist`. This wrapper only layers on dev-only conveniences: `.env` loading (gitignored; shell env always wins), a log directory (`EASY_DB_LAB_LOG_DIR`, defaults to `./logs`), and passing extra JVM options through the generated script's `EASY_DB_LAB_OPTS` hook.

## create-easy-db-lab-wrapper

```
bin/create-easy-db-lab-wrapper <cluster-dir>
```

Generates a workspace-local `easy-db-lab` wrapper inside a cluster directory. The generated wrapper sets `JAVA_HOME` to a compatible JDK via SDKMAN (the highest-installed Java 21, else the newest installed JDK >= 21), `cd`s into the workspace directory, and execs `bin/easy-db-lab` — so every cluster command runs from the correct directory with the correct JDK, no manual `cd` or Java ceremony. See "Cluster Workspace Directories" in the root `CLAUDE.md`.

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
