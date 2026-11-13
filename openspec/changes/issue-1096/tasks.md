## 1. Vocabulary and recording

- [x] 1.1 Add `PRIVILEGED`, `HOST_NETWORK`, `HOST_PATH`, `NODE_SSH` to `NodeCapability` with serial names and absent reasons; set `LOCAL_DOCKER` to the three admission capabilities
- [x] 1.2 Add `ClusterState.capabilities: Set<NodeCapability>? = null`; `NodeCapabilities.from(state)` returns it, else `AWS`; expose the present set for publishing
- [x] 1.3 Unit tests: recorded set is used; missing set reads as AWS; state round-trip by serial name

## 2. Overrides and init reporting

- [x] 2.1 `CapabilityOverrides.parse(List<String>)`: name=bool, fail on unknown name, bad value, conflicting repeats, listing valid names (constants in `Constants`)
- [ ] 2.2 `init --capability` (repeatable); record declared set plus overrides before writing state; fail before any state write on a parse error
- [ ] 2.3 Expose `ObservabilityStackService` stage label/`requires` pairs without building manifests; `init` prints one multiline block per absent capability with its reason and requiring stages (or "no component needs it yet")
- [ ] 2.4 Tests: override removes and adds; unknown and conflicting overrides fail with no state; the init component list equals the stages `up` skips for the same set

## 3. Probe

- [ ] 3.1 `CapabilityProbeService.probe(api: KubeApi, namespace)` using #1089's `KubernetesClientFactory`; Fabric8 builders for the restricted baseline Pod and the three single-field variants; create the namespace when missing
- [ ] 3.2 Classify results: 403/422 or webhook dry-run refusal makes the capability absent with a note (webhook named); baseline rejection or other error fails naming namespace and message; `NODE_SSH` always absent
- [ ] 3.3 Integration test (K3s TestContainer, `src/integrationTest/`): namespace enforcing baseline makes the three absent and leaves no Pod; an unlabelled namespace makes them present; a missing namespace is created; a namespace enforcing a policy that rejects the baseline Pod fails the probe
- [ ] 3.4 Unit test of the dry-run-refusal classifier against the API server's message shape

## 4. Publishing and status

- [ ] 4.1 `ClusterConfigData.of()` adds `capabilities` (enum order, comma-joined); test stable output and absence of a removed capability
- [ ] 4.2 `KitProcessEnvironment` sets `EDL_NODE_SSH` from `NODE_SSH in capabilities`; test sshConfig present but `NODE_SSH` absent gives `false`, AWS gives `true`
- [ ] 4.3 `status` prints a Capabilities section (multiline `println`); test present and absent with reason

## 5. Docs

- [ ] 5.1 `configuration/CLAUDE.md` "Node Capabilities": new capabilities, recorded set, probe, overrides
- [ ] 5.2 `docs/development/kits.md` `EDL_NODE_SSH` row: derived from `NODE_SSH`
- [ ] 5.3 `init` reference docs: `--capability`
- [ ] 5.4 Run `ktlintFormat`, `detekt` (JDK 21), `test` and `integrationTest`
