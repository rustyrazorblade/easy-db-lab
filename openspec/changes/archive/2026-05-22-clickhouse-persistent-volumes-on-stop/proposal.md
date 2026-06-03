## Why

Currently, ClickHouse PVs are created at `install` time, and the stop/start cycle leaves PVs with stale claimRef UIDs that block new PVCs from binding. This makes every `clickhouse start` after a stop spin up with no data. Moving PV creation into `start` and making `start` idempotent allows stop/start to resume the existing data naturally.

## What Changes

- `platform-pvs` is removed from the `install` step and added to the `start` step instead
- `clickhouse start` creates PVs if they don't exist, or clears stale claimRef UIDs if they do — then proceeds to deploy the CHI as before
- `clickhouse stop` is unchanged — it deletes the CHI and NodePort service; PVs survive
- `clickhouse uninstall` is unchanged — it deletes PVs and the operator helm release
- `clickhouse install` no longer creates PVs; it only installs the operator

## Capabilities

### New Capabilities
- `clickhouse-volume-persistence`: PVs are created lazily at `start` time (not install time); they survive `stop`; the `platform-pvs` step is idempotent so `start` is safe to re-run; data is only wiped on `uninstall`

### Modified Capabilities
- `clickhouse`: REQ-CH-003 (Lifecycle Management) changes — stop no longer implies data loss; start resumes existing data if present

## Impact

- `src/main/resources/.../install/clickhouse/config.yaml` — move `platform-pvs` from `install` to `start`
- `bin/tests/clickhouse` test script — verify the new lifecycle (install → start → stop → start → verify data survives → stop → uninstall)
- No Kotlin code changes required — `createLocalPersistentVolumes` is already idempotent
