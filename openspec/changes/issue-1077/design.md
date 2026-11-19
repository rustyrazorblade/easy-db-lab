## Context

How this machine reaches a cluster is recorded twice today:

- `state.json` holds `tailscaleActive: Boolean`.  `init` sets it to true when the profile holds Tailscale OAuth credentials and `--no-tailscale` is not given.  Kotlin reads it through `ClusterState.isTailscaleEnabled()` in about ten places.
- `<workspace>/.socks5-proxy.env` holds `EDL_TAILSCALE_ACTIVE=true|false`, copied from `state.json` by `ProxyEnvFile.recordTailscale`.  The callers are `WorkspaceShellTools.prepare` (from `up` and the VPC restore), `ProxyPreflight.ensureTunnel` (before every `@RequiresProxy` command) and `socks start`.  The tool wrappers (`configuration/tool-wrapper.sh`, copied into `<workspace>/bin/`) and `env.sh`'s `with-proxy` and `socks5-status` source this file.

Patrick's commit `946a7a1fa` adds a third way to reach a cluster, direct (the cluster API is reachable from this machine with no tunnel, for example K3s in Docker).  It records direct as a second key, `EDL_NETWORK_MODE=direct`, which is written only while direct is active.  The owner approved the port with one change: one network mode value replaces both flags, and existing workspaces keep working.

Constraints from the standing rules:

- Shell code reads only the sourceable env file, never JSON.  `ProxyEnvFile` is the file's only writer.
- `state.json` is the cluster state.  The env file is a copy of what shell code needs.
- Never touch the `socksProxyHost`/`socksProxyPort` JVM properties.  This change does not touch any proxy wiring; it changes only the decision of whether a tunnel is used.
- Names are provider-neutral.  `NetworkMode` says how this machine reaches the cluster, not which cloud runs it.
- The profile (`User.kt`) holds only the Tailscale credentials.  It has no mode and does not change.

## Goals / Non-Goals

**Goals:**

- One value, `socks`, `tailscale` or `direct`, in `state.json` and in the env file.
- Direct mode in the wrappers, `with-proxy` and `socks5-status`, with the behaviour of `946a7a1fa`: no tunnel needed, a recorded tunnel ignored, inherited proxy variables passed through untouched.
- A workspace made by an earlier release keeps working with the new build: its `state.json`, its env file and the old wrappers in its `bin/`.
- Kotlin code that decides "tunnel or not" stops asking "is it Tailscale?".

**Non-Goals:**

- Anything that records `direct` in production.  `init --local` and the execution environment (#1040, a later piece of Patrick's branch) do that.
- A CLI option to choose the mode.
- Renaming `.socks5-proxy.env`.
- Changing how Tailscale is detected at `init` (profile credentials and `--no-tailscale`).

## Decisions

### D1. The type: `NetworkMode`, an enum with three wire values

`configuration/NetworkMode.kt`:

- `NetworkMode.Socks`, wire value `socks`: this machine reaches the cluster through the SOCKS tunnel the CLI manages.
- `NetworkMode.Tailscale`, wire value `tailscale`: this machine reaches the cluster's private addresses over the tailnet.
- `NetworkMode.Direct`, wire value `direct`: this machine reaches the cluster API as it is, with no tunnel and no VPN that easy-db-lab manages.

Each value carries its wire id (`id`), and `NetworkMode.fromId(String): NetworkMode?` parses one.  The same lower-case ids are used in `state.json` and in the env file, so one name means one thing everywhere.  One helper answers the question most callers have: `NetworkMode.needsTunnel` is true only for `Socks`.

Alternative: keep `tailscaleActive` and add `direct: Boolean` (Patrick's form).  Rejected by the owner: two booleans allow "Tailscale and direct" and make every reader pick which one wins.

### D2. `state.json`: the field `networkMode`

`ClusterState.networkMode: NetworkMode`, default `Socks`, serialized as its wire id (`"networkMode": "socks"`).  `tailscaleActive` is removed from `ClusterState`.  `isTailscaleEnabled()` is removed.  Callers use `state.networkMode` directly (`== NetworkMode.Tailscale` where the code is about Tailscale itself, `.needsTunnel` where it is about the tunnel).

Who sets it:

- `init` sets `Tailscale` when the profile holds both Tailscale credentials and `--no-tailscale` is not given, and `Socks` otherwise.  This is today's rule, moved to the new field.
- Nothing sets `Direct` in this change.  #1040 adds the writer.
- No other command changes it.

### D3. Reading an old `state.json`

`ClusterStateManager.load()` already rewrites the parsed JSON tree before it maps it (the `cni` default).  The mode migration is done the same way, on every load, before mapping:

- `networkMode` present: it is used.  A `tailscaleActive` field next to it is dropped and ignored.
- `networkMode` absent, `tailscaleActive: true`: the state loads as `tailscale`.
- `networkMode` absent, `tailscaleActive` false or absent: the state loads as `socks`.  A state with neither field is a cluster from before Tailscale direct connect, which was SOCKS.

`load()` does not write the file.  The next `save()` writes `networkMode` and no `tailscaleActive`, because the class no longer has the field.  Most commands save the state (the `lastAccessedAt` update, the incremental backup hashes), so the file is rewritten on the first ordinary command after the upgrade.  Until then the file is still readable by the new build, so nothing depends on when that happens.

An unknown value fails the load with one line: `Unknown networkMode 'vpn' in state.json: the known values are socks, tailscale, direct`.  A wrong mode would send traffic down the wrong path (for example, skip the tunnel on a SOCKS cluster) and fail later with a confusing connect timeout, so failing at load is the clearer error.  Owner decision (2026-11-19): an unknown value fails the load, for every command in that workspace, `down` included; it is never read as `socks`.

### D4. The env file: the key `EDL_NETWORK_MODE`

The file holds:

- `EDL_NETWORK_MODE=<socks|tailscale|direct>`, from `state.json`, once the CLI has recorded the mode.
- `EDL_SOCKS_PORT=<port>` and `EDL_SOCKS_PID=<pid>`, as today, only while a verified tunnel is recorded.

`EDL_NETWORK_MODE` is always written with a value, unlike `946a7a1fa`, where it was present only in direct mode.  `EDL_TAILSCALE_ACTIVE` is never written by the new build.

`ProxyEnvFile`:

- `ProxyEnv(networkMode: NetworkMode?, tunnel: RecordedTunnel?)`.  `networkMode` is null before the mode is recorded.
- `recordNetworkMode(mode: NetworkMode)` replaces `recordTailscale(Boolean)` and Patrick's `recordDirect(Boolean)`.  It keeps the recorded tunnel.
- `recordTunnel` and `removeTunnel` keep the recorded mode, as they keep the Tailscale flag today.

Who records the mode, and when (the same callers that record the Tailscale flag today):

- `WorkspaceShellTools.prepare(workspace, mode)`: at the end of `up` and after a VPC restore.
- `ProxyPreflight.ensureTunnel()`: before every `@RequiresProxy` command, whenever a cluster state exists.
- `socks start`.

### D5. Reading and rewriting an old env file

An env file written by the old build has `EDL_TAILSCALE_ACTIVE` and no `EDL_NETWORK_MODE`.

- `ProxyEnvFile.read()` takes `EDL_NETWORK_MODE` when present.  When it is absent, `EDL_TAILSCALE_ACTIVE=true` reads as `Tailscale` and `EDL_TAILSCALE_ACTIVE=false` as `Socks`.  Neither key reads as null.  An unknown `EDL_NETWORK_MODE` reads as null.
- `recordNetworkMode` writes `EDL_NETWORK_MODE` and drops `EDL_TAILSCALE_ACTIVE`.  This is the only write that converts the file.
- `recordTunnel` and `removeTunnel` on an old file keep `EDL_TAILSCALE_ACTIVE` as they found it and add no `EDL_NETWORK_MODE`.  They never convert the file.  The reason is D6: only a mode write is preceded by a wrapper install.

The new wrappers and `env.sh` read both formats (D7), so an old file works with new wrappers at any time.

### D6. The old wrappers in `<workspace>/bin/`

An old wrapper knows only `EDL_TAILSCALE_ACTIVE`.  On a converted env file of a Tailscale cluster it would find no `EDL_TAILSCALE_ACTIVE=true` and no port, and fail with the `socks start` message.  (On a SOCKS cluster it would still work, because it falls through to the port.)  So the old wrappers must be gone before any file is converted.

`WorkspaceShellTools` becomes the only caller of `recordNetworkMode`, and it always installs the wrappers first (`ToolWrapperInstaller.install`, which rewrites every wrapper whose content differs from the packaged one).  `ProxyPreflight` and `socks start` call `WorkspaceShellTools.prepare` instead of calling `ProxyEnvFile` directly.  So the order is always: new wrappers, then new env file.  The env file is converted, and the wrappers replaced, on the first `up`, VPC restore, `socks start` or `@RequiresProxy` command after the upgrade.  A `bin/` that holds a tool file without the easy-db-lab marker makes the install fail before the env file is written, as it does today.

Kit processes already install the wrappers before every run (`KitProcessEnvironment.applyTo`), so a kit step never sees an old wrapper either.

The remaining window is a shell that sourced the old `env.sh`.  Its `with-proxy` and `socks5-status` are shell functions that live in that shell, not files.  After the env file is converted, the old `with-proxy` on a Tailscale cluster fails with the `socks start` message until the user sources the new `env.sh`.  `up` rewrites `env.sh`, and the tools themselves (`kubectl`, `curl`, ...) resolve to the new wrappers through `PATH`, so only these two helper functions are affected.  Owner decision (2026-11-19): this is accepted.  The user sources `env.sh` again; the CLI does not keep writing `EDL_TAILSCALE_ACTIVE` as a second key for a transition, because that would bring back the two-key form.

### D7. How the wrappers and `env.sh` branch on the mode

Each wrapper unsets inherited `EDL_NETWORK_MODE`, `EDL_TAILSCALE_ACTIVE`, `EDL_SOCKS_PORT` and `EDL_SOCKS_PID`, sources the env file, and resolves the mode:

1. `EDL_NETWORK_MODE` when set.
2. Otherwise `tailscale` when `EDL_TAILSCALE_ACTIVE=true` (an old file).
3. Otherwise `socks` (an old file of a SOCKS cluster, or no file at all).

Then:

- `tailscale` or `direct`: `exec` the real tool with no proxy variable set or cleared.  The four Kubernetes tools still set `KUBECONFIG`.  A recorded tunnel is ignored.
- `socks`: today's path.  It needs a recorded port whose `EDL_SOCKS_PID` is alive, clears the inherited proxy variables, and sets its own.  Otherwise it fails with the `socks start` message.
- Anything else: exit 1 with `easy-db-lab: unknown network mode '<value>' in <workspace>/.socks5-proxy.env, so <tool> cannot reach the cluster.` and `easy-db-lab: run 'easy-db-lab socks start' in <workspace> to record the mode again.`  `socks start` rewrites the mode from `state.json`.  The wrapper does not guess: guessing `socks` would clear a corporate proxy, and guessing `direct` would send cluster traffic outside the tunnel.

`env.sh` gets one helper, `_edl_network_mode`, which resolves the mode the same way in a subshell that ignores inherited values.  It replaces `_edl_tailscale_active` and Patrick's `_edl_network_direct`.

- `with-proxy`: `tailscale` or `direct` runs the command as is.  `socks` runs today's path.  An unknown mode prints the same two lines as the wrapper and returns 1.
- `socks5-status`: `tailscale` prints `This cluster uses Tailscale: no SOCKS5 tunnel is needed.`  `direct` prints Patrick's `This workspace uses direct network mode: the cluster is reached with no SOCKS5 tunnel.`  `socks` prints today's tunnel status.  An unknown mode prints the unknown-mode line and returns 1.

### D8. Kotlin callers

Two kinds of callers read the mode.

- **Tunnel or not** (`needsTunnel`): `ProxyPreflight` (start the tunnel only for `socks`), `socks start` (report `TunnelNotNeeded` for `tailscale` and `direct`), `CqlSessionService` (direct session unless `socks`), `ObservabilityHttp` (ensure the tunnel only for `socks`), `RecordedTunnelRoutes` (direct route unless `socks`), and `up`'s operator route.
- **Tailscale itself** (`== Tailscale`): `ProvisioningPreflight.verifyLocalTailscale`, the Cilium Tailscale masquerade in `K3sControlSteps`, and `up`'s tailnet route probe.

In `up`, the operator route becomes `socks` → `SocksRoute`, `tailscale` → `TailnetRoute` (none under `--no-setup`, as today), `direct` → no route step.  `up` provisions EC2 only, so `direct` does not reach it in practice before #1040.  It is covered so the `when` is exhaustive.

`Event.Proxy.TunnelNotNeeded` becomes a data class with one field, `networkMode` (`tailscale` or `direct`).  Its text names the mode: the Tailscale text stays, and the direct text is `This cluster is reachable from this machine, so no SOCKS5 tunnel is needed: tools connect to it directly.`

### D9. A mode change on a workspace with a recorded tunnel

If `state.json` says `tailscale` or `direct` while the env file still records a tunnel (for example, the state was edited by hand), the preflight records the new mode and leaves the tunnel recorded and running.  The wrappers ignore it (D7).  `socks stop` and `down` stop it as they do today.  The preflight does not stop it, because stopping a process is not what recording a mode means.

### D10. `tailscale-direct-connect` spec

That spec still says `init` runs `tailscale status --json` to set `tailscaleActive`.  The code and the networking spec ("Tailscale detection is profile-based") use the profile credentials and `--no-tailscale`.  This change rewrites that requirement to the profile rule and the new field, because it has to touch it anyway to rename the field.  Owner decision (2026-11-19): the stale requirement is corrected in this change; correcting a spec is always high priority.

### D11. One network mode type, also after #1163

Owner decision (2026-11-19).  Patrick's later piece #1163 (`c9e5b8f8e`) adds `configuration.environment.NetworkMode { Tunnel, Direct }`, which `ClusterContract.networkMode` returns per environment.  When #1163 is ported, that enum goes.  The environment supplies only the initial mode `init` records in `state.json` (EC2 + K3s: `socks` or `tailscale`, from the profile and `--no-tailscale` as today; K3s in Docker and a brought cluster: `direct`), and every reader, `ClusterContract` included, reads `state.networkMode`.  There is one network mode type, `configuration/NetworkMode`, and one recorded value.

## Risks / Trade-offs

- [A shell that sourced the old `env.sh` keeps the old `with-proxy` and `socks5-status`.] → After the conversion, those two functions fail on a Tailscale cluster with the `socks start` message until the user sources `env.sh` again.  Wrapped tools are not affected.  Accepted by the owner (D6).
- [A state saved by the new build cannot be read by the old build.] → The old build ignores unknown fields and reads the cluster as SOCKS.  Clusters are ephemeral and nobody downgrades; no handling.
- [Name clash with a later piece of Patrick's branch.] → #1163 adds `configuration.environment.NetworkMode { Tunnel, Direct }` on `ClusterContract`.  Resolved by D11: one type.
- [An unknown mode fails every command that loads state.] → That is the intent: the mode is read before the first connection, and the error names the file and the known values.

## Migration Plan

The migration is in the code; there is no separate step.

1. A workspace from an earlier release: the first command loads `state.json` as `socks` or `tailscale` (D3).  The first save writes `networkMode`.
2. The first `up`, VPC restore, `socks start` or `@RequiresProxy` command replaces the wrappers and then converts the env file (D6).
3. Until step 2, the new build reads the old env file and the old wrappers keep working, because the file is unchanged.

The real-cluster test before merge covers this: a 3-node cluster brought up by the current release, then used with the new build (a kit step, a wrapped `kubectl` from a sourced `env.sh`, `socks5-status`, `socks stop`/`socks start`, `down`), and a fresh 3-node cluster with the new build.

## Open Questions

None.  The owner settled the four questions of the first draft on 2026-11-19, each as recommended: old `env.sh` shells re-source it (D6), one network mode type after #1163 (D11), the stale `tailscale-direct-connect` requirement is corrected here (D10), and an unknown `networkMode` fails the load (D3).
