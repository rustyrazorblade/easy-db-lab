## 1. Agent delivery

- [x] 1.1 Add the Pyroscope agent image (multi-arch, checksummed jar) to `publish-container.yml`.
- [x] 1.2 Raise `opentelemetry-agent` in `gradle/libs.versions.toml` to 2.31.1.
- [x] 1.3 Test first: `JavaAgentDelivery.applyTo` adds the emptyDir, one init container per agent (restricted security context) and a read-only mount; `flag(agent)` names a file an init container writes.
- [x] 1.4 Add `JavaAgent`, `JavaAgentDelivery`, `Constants.Kit.Agents.*`, `AGENTS_MOUNT`; publish `__OTEL_AGENT_IMAGE__` / `__PYROSCOPE_AGENT_IMAGE__`; move cassandra-pods off build-time `@OTEL_AGENT_VERSION@`.
- [x] 1.5 neo4j `statefulset.yaml.template`, presto and trino `start.sh.template` jq patches: init container + emptyDir, no hostPath.

## 2. Stress jobs (split with #1135 per D6; starts after #1135 merges)

- [ ] 2.1 Test first (`DefaultStressJobServiceTest`, replacing `:445,473` asserts): no hostNetwork, no hostPath; agent paths cross-checked against init-container copy targets; selector from `RoleSelectors`.
- [ ] 2.2 `StressJobService.assembleJob` / `buildCommandJob`: drop `hostNetwork` / `ClusterFirstWithHostNet` / hostPath; use `JavaAgentDelivery` and the app selector. Leave the Pyroscope address and control-node lookup to #1135.
- [ ] 2.3 Test first: `StressTargetResolver` picks the running kit, else VM hosts, else fails naming both; DC from kit `--dc` or region.
- [ ] 2.4 `StressTarget` + resolver; `StressStart` drops its control-host requirement.

## 3. Role selectors

- [ ] 3.1 Test first: `RoleSelectors.from(state)` (null → `OWNED_NODES`), `--role-nodes` parsing and refusal, `NodeSelector.toJson` / `toLabelSelector`.
- [ ] 3.2 Add `NodeSelector`, `RoleSelectors`, `ClusterState.roleSelectors`, `Init --role-nodes`.
- [ ] 3.3 `RoleNodes` (via `K8sService.nodesMatching`) and `KitPlacementVariables` (`DB_/APP_NODE_SELECTOR`, `DB_/APP_/CONTROL_NODE_LABELS`, counts); `KitInstallCommand.requiredNodePoolIsMissing` uses `RoleNodes`.
- [ ] 3.4 `KitEndpointResolver`: `TARGET_*` for in-cluster clients resolves to Service DNS and Service port via `KitEndpointServices` (#1110).
- [ ] 3.5 Kits: replace every `type` nodeSelector / affinity with `__DB_NODE_SELECTOR__` / `__APP_NODE_SELECTOR__` in cassandra-pods, memcached (both), neo4j, postgres, clickhouse (+ keeper), tidb, presto, trino, flink.
- [ ] 3.6 Rendering test: every templated kit with `OWNED_NODES` equals today's `type=<role>`; with `ANYWHERE`, no role constraint.

## 4. Shell library

- [ ] 4.1 Test first (`src/test/shell/kit-lib.test.sh`, bash 3.2): `edl_pod_overrides` with `{}` and `{"type":"app"}`; `edl_require_app_node` with empty and set labels; gauge-push pod choice with and without a control selector.
- [ ] 4.2 `edl-kit-lib.sh`: read `APP_NODE_LABELS` / `APP_NODE_SELECTOR` / `CONTROL_NODE_LABELS`; `edl_push_gauges` per D9; keep function names.

## 5. Presto and Trino Services

- [ ] 5.1 Kit-owned `nodeport-service.yaml.template` (presto 30880, trino 30881); `kit.yaml` endpoints declare the NodePort; `Constants.Kit.Presto.NODE_PORT` / `Trino.NODE_PORT`.
- [ ] 5.2 Remove hostPort patches and Trino's coordinator-delete workaround; use `kubectl rollout restart` where ConfigMap pickup is still needed.

## 6. Client networks

- [ ] 6.1 Test first: `ClientNetworks` from AWS state (VPC + cluster CIDR) and from probed node data plus overrides; JSON / pg_hba rendering.
- [ ] 6.2 `ClientNetworks`, `ClientNetworkProbe`, `Init --client-network`, `Constants.K3s.CLUSTER_CIDR` passed as `--cluster-cidr`; ClickHouse `__CLIENT_NETWORKS_JSON__`; Postgres one trust line per CIDR.

## 7. Guard and integration tests

- [ ] 7.1 `BuiltinKitHostDependencyTest` over packaged kit resources and `edl-kit-lib.sh`, plus a self-test that each pattern matches its forms.
- [ ] 7.2 Integration (K3s, one unlabelled node, `ANYWHERE`): a stress Job from the real `StressJobService` reaches Running and both agent jars exist.
- [ ] 7.3 Integration: `CassandraPodsKitIntegrationTest` unlabelled-node variant runs cassandra-easy-stress against the kit; Job Succeeded.
- [ ] 7.4 Integration (K3s, one unlabelled node): install the CNPG operator, start postgres, run sysbench prepare and start; the run pod Succeeded. Repeat in the AWS lab run (9.2).
- [ ] 7.5 Keep `src/test/shell/cassandra-pods-start.test.sh` covering `EDL_NODE_SSH=false`.

## 8. Docs and specs

- [ ] 8.1 `docs/development/kits.md` (agents, role variables, shell library), `docs/reference/ports.md` (30880, 30881), `docs/reference/commands.md` (`init --role-nodes`, `--client-network`), kit user guides, `neo4j/METRICS.md`, `configuration/CLAUDE.md`, `Constants.kt:752-761` KDoc.
- [ ] 8.2 Specs: `clickhouse-benchmark-kit`, `tidb`, `kit-install-command`, `networking`.
- [ ] 8.3 Root CLAUDE.md kit section: role variables replace `type=app`.

## 9. Verification

- [ ] 9.1 `./gradlew test integrationTest testCassandraScripts detekt ktlintCheck` on JDK 21 (in a subagent).
- [ ] 9.2 AWS lab run: stress, neo4j, presto, trino, sysbench; placement and dashboards unchanged (read back through `dashboard-editor`).
