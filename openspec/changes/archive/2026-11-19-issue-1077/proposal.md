## Why

The local execution environment (#1032, #1040) needs a third way to reach a cluster: a cluster API this machine reaches as it is, with no SOCKS tunnel and no Tailscale (for example K3s in Docker).  Patrick's commit `946a7a1fa` adds it as a second flag, `EDL_NETWORK_MODE=direct`, next to the existing `EDL_TAILSCALE_ACTIVE` boolean.  Two flags allow combinations that mean nothing (Tailscale and direct both set) and make every reader decide which one wins.  The owner approved the port with one change: one network mode value, `socks`, `tailscale` or `direct`, replaces both flags, in `state.json` and in the workspace env file.  Existing workspaces must keep working after the upgrade.

## What Changes

- Port `946a7a1fa` (Patrick stays the author): the direct mode in the wrappers, `env.sh`'s `with-proxy` and `socks5-status`, `Constants`, the networking spec and the tests.
- **One mode in cluster state.**  `state.json` records `networkMode` (`socks`, `tailscale` or `direct`).  The boolean `tailscaleActive` is removed from `ClusterState`.  `init` records `tailscale` when the profile holds Tailscale credentials and `--no-tailscale` is not given, and `socks` otherwise.  Nothing in production records `direct` until #1040.
- **One mode in the env file.**  `.socks5-proxy.env` holds `EDL_NETWORK_MODE=<mode>`, always, mirrored from `state.json`.  `EDL_TAILSCALE_ACTIVE` is no longer written.  `ProxyEnvFile.recordTailscale(Boolean)` and Patrick's `recordDirect(Boolean)` become one `recordNetworkMode(mode)`.
- **Old formats are read and rewritten.**
  - A `state.json` with `tailscaleActive` and no `networkMode` loads as `tailscale` (true) or `socks` (false or absent); the next save writes `networkMode` and drops `tailscaleActive`.
  - An env file with `EDL_TAILSCALE_ACTIVE` and no `EDL_NETWORK_MODE` reads the same way; the next write of the file replaces the old key with the new one.
  - Old wrapper scripts in `<workspace>/bin/` are replaced with the new ones every time the CLI records the mode, so no old wrapper ever reads a file that lacks `EDL_TAILSCALE_ACTIVE`.  New wrappers still read a not-yet-rewritten env file correctly.
- **Unknown values fail loudly.**  An unknown `networkMode` in `state.json` fails the load with one line naming the file, the value and the known values.  A wrapper that reads an unknown `EDL_NETWORK_MODE` exits 1 and says so.
- **Kotlin callers ask about the mode, not about Tailscale.**  Code that decides "tunnel or no tunnel" (the proxy preflight, `socks start`, the CQL session, the observability routes and HTTP client, `up`'s operator route) treats `direct` as no tunnel.  Code that is about Tailscale itself (the local Tailscale client check, the Cilium Tailscale masquerade, the tailnet route probe) runs only for `tailscale`.
- `socks start` reports which mode made the tunnel unnecessary.
- Docs: `docs/user-guide/network-connectivity.md` (the env file keys), root `CLAUDE.md` standing rule, `events/CLAUDE.md`.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `networking`: the network mode replaces `tailscaleActive` in state and `EDL_TAILSCALE_ACTIVE` in the env file; the wrappers, `with-proxy` and `socks5-status` branch on the mode and add direct mode; old state files, env files and wrappers are migrated.
- `tailscale-direct-connect`: the requirements that name `tailscaleActive` name the `tailscale` network mode instead.

## Impact

- `configuration/ClusterState.kt`, `configuration/ClusterStateManager.kt` (load-time migration), a new provider-neutral `configuration/NetworkMode.kt`.
- `proxy/ProxyEnvFile.kt`, `proxy/ProxyPreflight.kt`, `proxy/WorkspaceShellTools.kt`, `proxy/ObservabilityRoutes.kt`, `commands/socks/SocksStart.kt`, `commands/Init.kt`, `commands/Up.kt`, `services/CqlSessionService.kt`, `services/ObservabilityHttp.kt`, `services/ProvisioningPreflight.kt`, `services/ClusterConfigurationService.kt`, `services/bringup/k3s/K3sControlSteps.kt`, `events/Event.kt` (`Proxy.TunnelNotNeeded`), `Constants.kt`.
- Packaged shell: `configuration/tool-wrapper.sh`, `configuration/env.sh`.
- Tests: the ones `946a7a1fa` adds or edits, plus migration tests for each old format and a wrapper test against an old-format env file.
- A 3-node real-cluster test that includes a workspace created by the current release and then used with the new build.
