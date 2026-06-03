## 1. Config Change

- [x] 1.1 In `src/main/resources/.../install/clickhouse/config.yaml`, remove the `platform-pvs` entry from the `install` lifecycle
- [x] 1.2 Add `platform-pvs` as the first step of the `start` lifecycle in `config.yaml` (before the `manifest` step that applies the CHI)

## 2. Test Script Update

- [x] 2.1 In `bin/tests/clickhouse`, add a stop/start resume cycle: after the initial start+verify, stop ClickHouse, verify PVs still exist (`kubectl get pv`), start again, and verify the basic query still works (confirming data survived the stop/start)
- [x] 2.2 Remove or simplify the "reinstall + restart" section of the test since stop/start persistence is now the primary lifecycle being validated; ensure the reinstall path still tests install→start→stop→uninstall cleanly

## 3. Spec Archive

- [x] 3.1 Verify `openspec/specs/clickhouse/spec.md` REQ-CH-003 is updated at archive time to reflect the new stop/start behavior (handled by `opsx:archive`)
- [x] 3.2 Confirm `openspec/specs/clickhouse-volume-persistence/spec.md` is created at archive time (handled by `opsx:archive`)
