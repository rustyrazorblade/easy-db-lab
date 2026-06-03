## 1. Kit descriptor

- [ ] 1.1 Add `KitType` enum to `services/KitConfig.kt` with `@SerialName("db")` and `@SerialName("app")` entries
- [ ] 1.2 Add `val type: KitType? = null` to `KitConfig` data class

## 2. Install-time validation

- [ ] 2.1 Add `Event.Kit.RequirementNotMet(kit: String, type: String, message: String)` to `events/Event.kt`
- [ ] 2.2 In `KitInstallCommand.execute()`, before `renderAndWrite()`, check: if `config.type != null` and `clusterState.getHosts(serverType).isEmpty()`, emit `RequirementNotMet` and return
- [ ] 2.3 Map `KitType.DB` → `ServerType.Cassandra`, `KitType.APP` → `ServerType.Stress` for the host lookup

## 3. Update built-in kits

- [ ] 3.1 Add `type: app` to `kits/presto/kit.yaml`
- [ ] 3.2 Add `type: db` to `kits/clickhouse/kit.yaml`

## 4. Spec update

- [ ] 4.1 Document the `type` field in `openspec/specs/install-command/spec.md` — field definition, behaviour when missing, and the install-time check

## 5. Tests

- [ ] 5.1 Test: install fails with `RequirementNotMet` when `type: app` and no app nodes exist
- [ ] 5.2 Test: install proceeds when `type: app` and app nodes exist
- [ ] 5.3 Test: install proceeds when no `type` field, regardless of node topology
