## 1. Port Patrick's commit

- [ ] 1.1 Branch from `main` and cherry-pick `946a7a1fa` with Patrick as the author.  Resolve conflicts against `main` without changing its intent.
- [ ] 1.2 Run `./gradlew test` (take the local Gradle lock) and record the result; the cherry-pick alone must be green before the next commit.

## 2. The network mode in cluster state

- [ ] 2.1 Add `configuration/NetworkMode.kt`: `Socks`, `Tailscale`, `Direct`, each with its wire id; `fromId`; `needsTunnel`.  Class KDoc.
- [ ] 2.2 Replace `ClusterState.tailscaleActive` and `isTailscaleEnabled()` with `networkMode: NetworkMode = Socks`, serialized as the wire id.
- [ ] 2.3 Migrate in `ClusterStateManager.load()`: `tailscaleActive` to `networkMode` when the latter is absent, drop `tailscaleActive`, fail on an unknown value with the message in the spec.  Tests: old true, old false, neither field, both fields, unknown value, and a load-then-save that drops `tailscaleActive`.
- [ ] 2.4 `init` records `Tailscale` or `Socks` from the profile and `--no-tailscale`.  Test both branches.

## 3. The network mode in the env file

- [ ] 3.1 `Constants.Proxy`: `ENV_NETWORK_MODE`; drop `ENV_NETWORK_MODE_DIRECT`; keep `ENV_TAILSCALE_ACTIVE` documented as read-only legacy.
- [ ] 3.2 `ProxyEnvFile`: `ProxyEnv(networkMode, tunnel)`; `recordNetworkMode(mode)` replaces `recordTailscale` and `recordDirect`; read the legacy key; a mode write drops the legacy key; tunnel writes keep the file's format.  Tests for every row of design D5.
- [ ] 3.3 `WorkspaceShellTools.prepare(workspace, mode)` is the only caller of `recordNetworkMode` and installs the wrappers first.  `ProxyPreflight` and `socks start` call it.  Test that a workspace with old wrapper copies ends with the packaged wrappers and a converted env file.

## 4. Wrappers and env.sh

- [ ] 4.1 `tool-wrapper.sh`: resolve the mode (env key, then legacy key, then `socks`), branch per design D7, unknown-mode message.  Extend `ToolWrapperScriptTest`: each mode, the legacy file, an inherited mode, an unknown mode, direct with a recorded tunnel, direct with an inherited `http_proxy`.
- [ ] 4.2 `env.sh`: `_edl_network_mode` replaces `_edl_tailscale_active` and `_edl_network_direct`; `with-proxy` and `socks5-status` per the spec.  Extend `EnvShSourcingTest` the same way, in bash and zsh.
- [ ] 4.3 Test that an old wrapper (the `main` copy of `tool-wrapper.sh`, kept as a test fixture) is replaced by the install that precedes a mode write.

## 5. Kotlin callers

- [ ] 5.1 Tunnel decisions use `needsTunnel`: `ProxyPreflight`, `SocksStart`, `CqlSessionService`, `ObservabilityHttp`, `RecordedTunnelRoutes`, `Up.operatorRoute` (exhaustive `when`).
- [ ] 5.2 Tailscale-only steps use `== Tailscale`: `ProvisioningPreflight.verifyLocalTailscale`, `K3sControlSteps` masquerade, `Up` tailnet route.
- [ ] 5.3 `Event.Proxy.TunnelNotNeeded(networkMode)` with the two texts from design D8.  Update `events/CLAUDE.md`.
- [ ] 5.4 Update every test that builds `ClusterState(tailscaleActive = ...)` or calls `recordTailscale`/`recordDirect`.

## 6. Specs and docs

- [ ] 6.1 In `openspec/specs/networking/spec.md`, remove the "Wrappers use no tunnel in direct network mode" requirement the cherry-pick added; this change's "Wrappers branch on the network mode" replaces it.  Leave the delta to update the rest at archive.
- [ ] 6.2 Root `CLAUDE.md` standing rule: the env file holds `EDL_NETWORK_MODE`, `EDL_SOCKS_PORT`, `EDL_SOCKS_PID`; `EDL_TAILSCALE_ACTIVE` is read only from old files.
- [ ] 6.3 `docs/user-guide/network-connectivity.md`: the env file keys and the three modes.
- [ ] 6.4 `configuration/CLAUDE.md` and `proxy` KDoc where they name `tailscaleActive`.
- [ ] 6.5 Record design D11 where the #1163 port will see it (the `port-patricks-branch-in-pieces` notes / the #1163 piece's issue): its `environment.NetworkMode` goes, the environment supplies only the initial mode `init` records, and every reader uses `state.networkMode`.

## 7. Gates

- [ ] 7.1 `./gradlew ktlintFormat`, then `./gradlew check` on JDK 21 through a Sonnet build-engineer, under the local Gradle lock.  No detekt suppressions.
- [ ] 7.2 `openspec validate issue-1077 --strict`.
- [ ] 7.3 Real-cluster test, 3 nodes, `--tag embark_offhours=off`: (a) bring a cluster up with the current release, then with the new build run a kit step, a wrapped `kubectl` from a freshly sourced `env.sh`, `socks5-status`, `socks stop`, `socks start` and `down`, and check that `state.json`, `.socks5-proxy.env` and `bin/` were converted as specified; (b) a fresh cluster with the new build through the same commands.  Record commands and output in the PR.
