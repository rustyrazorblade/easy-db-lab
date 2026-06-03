## Context

When `clickhouse stop` deletes the ClickHouseInstallation, the Altinity operator deletes the associated PVCs. PVs (Retain policy) go to `Released` state with a stale `claimRef.uid`. On the next `clickhouse start`, new PVCs are created with the same name but a new UID, and Kubernetes won't bind them to the Released PVs.

Currently `platform-pvs` only runs at `install` time, so the stale-UID clearing logic in `createLocalPersistentVolumes` is never triggered on a plain `start`. Moving `platform-pvs` into `start` ensures UIDs are always fresh before the CHI is applied.

Additionally, having PV creation at `install` time is premature — the operator hasn't scheduled any pods yet and the node ordinal affinity is set up correctly, so there's no reason PVs must exist before first start. Moving them to `start` aligns creation with actual use.

## Goals / Non-Goals

**Goals:**
- `clickhouse stop` + `clickhouse start` resumes existing data without extra user steps
- PV creation is idempotent: running `platform-pvs` when PVs already exist is a no-op (already true)
- `clickhouse install` is simpler — only installs the operator, no storage
- Data is only destroyed on explicit `clickhouse uninstall`

**Non-Goals:**
- Changing PV lifecycle for any other workload
- Changing the `uninstall` steps (already correct — `platform-pvs-delete` handles missing PVs gracefully)
- Adding a flag to force a fresh volume on start (out of scope)

## Decisions

### Move `platform-pvs` from `install` to `start` in `config.yaml`

`createLocalPersistentVolumes` is already idempotent:
- PV doesn't exist → creates it
- PV exists with stale claimRef UID → clears the UID, returns PV to `Available`
- PV exists and already bound → skips it

Running it as the first step of `start` covers all three cases. Zero new Kotlin code is required; the only change is the YAML config.

**Alternative considered:** Keep `platform-pvs` in `install` AND add it to `start`. Rejected — duplicating it adds no value and makes the install step misleading (it creates storage that isn't used yet).

**Alternative considered:** Keep PVCs alive across stop (don't delete them). Rejected — the Altinity operator owns PVC lifecycle when the CHI is deleted; we can't prevent that.

## Risks / Trade-offs

- [Risk] `clickhouse uninstall` called before any `start` — no PVs exist to delete. `deleteLocalPersistentVolumes` already handles this gracefully (deletes zero resources). → No regression.
- [Risk] Disk space is held between stop and start → Acceptable: ephemeral clusters are short-lived by design.
- [Trade-off] `clickhouse install` is now a lighter-weight operation (no storage provisioned) — this is desirable but test scripts must not assume PVs exist after install.

## Open Questions

None.
