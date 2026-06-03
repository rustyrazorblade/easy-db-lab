## 1. Config File

- [x] 1.1 Create `spark/cassandra-analytics-source.properties` with `repo=https://github.com/apache/cassandra-analytics.git` and `branch=trunk`

## 2. Update `bin/build-cassandra-analytics`

- [x] 2.1 Add logic to read `repo` and `branch` defaults from `spark/cassandra-analytics-source.properties` (relative to project root)
- [x] 2.2 Add `--repo` CLI flag (overrides config file value)
- [x] 2.3 Print the resolved repo and branch before any git operations
- [x] 2.4 After resolving the repo URL, check if `.cassandra-analytics/` already exists with a different remote — if so, print a warning and exit with instructions to re-run with `--force`

## 3. Verification

- [x] 3.1 Run `bin/build-cassandra-analytics --help` and confirm `--repo` appears in usage
- [x] 3.2 Confirm build output shows the resolved repo and branch
- [x] 3.3 Update `docs/development/` or `CLAUDE.md` if the build prerequisite section needs updating
