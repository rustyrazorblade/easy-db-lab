# bin/ Scripts

## easy-db-lab

The local-development CLI entrypoint. It does **not** launch the JVM itself — it delegates to the Gradle-generated start script (`build/install/easy-db-lab/bin/easy-db-lab`), which is the single source of truth for the classpath, the OTel java agent, and `easydblab.apphome`/`easydblab.version` (#727). Build it first with `./gradlew installDist`. This wrapper only layers on dev-only conveniences: `.env` loading (gitignored; shell env always wins), a log directory (`EASY_DB_LAB_LOG_DIR`, defaults to `./logs`), and passing extra JVM options through the generated script's `EASY_DB_LAB_OPTS` hook.

## Other scripts

- `create-release` — cuts a release of the main repo with `gh`; nothing happens until the release is confirmed.
- `diagnose-error` — decodes an AWS "encoded authorization failure" message (`aws sts decode-authorization-message`).
- `set-policies` — creates the managed easy-db-lab IAM policies and attaches them to a user, group or role.
- `export-workload-metrics` (with `metrics-catalog.jq` and `export-workload-metrics.test.sh`) — exports a running kit's metric series to `metrics-catalog.json`; see `docs/development/kits.md`.
- `setup-spark-cluster`, `spark-bulk-write`, `test-local-bulk-writer`, `test-spark-bulk-writer-s3-iam` — Spark bulk-writer helpers. `test-spark-bulk-writer-s3-iam` provisions a cluster; run it by hand only.

There is no test runner in `bin/`. Lab test plans run through the `/easy-db-lab:plan` and
`/easy-db-lab:run` skills, and branch verification through `/agent-test`, each in its own workspace
under `clusters/`. The repository root can never be a workspace: `init` refuses a directory that
already has a `bin/`.
