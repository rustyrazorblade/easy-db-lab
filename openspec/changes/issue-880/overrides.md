# Overrides and conflicts

## Overrides existing behavior

None — this change only adds new requirements. Its delta spec contains a single `## ADDED
Requirements` section for the new `profiling` capability and no `## MODIFIED` or `## REMOVED`
sections.

That deserves a word of justification, because the change does delete a working mechanism. The
Pyroscope Java agent injection in `packer/cassandra/cassandra.in.sh` is being removed, but **no
committed requirement ever described it**. Verified by searching `openspec/specs/` for both
`pyroscope` and `profil`:

- `openspec/specs/observability/spec.md` covers OTel, Grafana, Cilium/Hubble, and hostPort exposure.
  Its only match is a dashboard-title requirement that happens to list "Profiling" as an example
  dashboard name — a naming rule, unaffected by this change.
- Every other match across `setup`, `cluster-lifecycle`, `env-file-config`, `kit-source-management`,
  `server`, `networking`, and `ignite3` is on the substring `profile` in the sense of *AWS profile*
  or *user profile*, not profiling.

So Cassandra's continuous profiling has been unspecified behavior until now, which is why this change
creates a capability rather than amending one.

Two committed specs *do* cover Pyroscope, and **both remain true**:

- **`trino` REQ-TRN-005 "Pyroscope Profiling"** — Trino coordinator and worker deployments are
  patched post-Helm to attach the Pyroscope Java agent via `JAVA_TOOL_OPTIONS`, with
  `/usr/local/pyroscope` hostPath-mounted. Trino is out of scope here and is not touched.
- **`sidecar-otel`** — the Cassandra Sidecar systemd service loads the Pyroscope Java agent when
  `PYROSCOPE_SERVER_ADDRESS` is set in its environment file.

The `sidecar-otel` requirement was checked specifically, because this change replaces
`SetupInstance.setupCassandraSystemdEnv` — the writer of `PYROSCOPE_SERVER_ADDRESS` — and the
requirement names that same variable. It is **not** a conflict: `PYROSCOPE_SERVER_ADDRESS` is written
to `/etc/default/cassandra` and read only by `packer/cassandra/cassandra.in.sh`, confirmed by
searching `src/main` and `packer/`. `/etc/default/cassandra` is consumed via `EnvironmentFile=` by
`cassandra.service` alone. The sidecar is a containerized DaemonSet reading its own environment file,
so removing the variable from Cassandra's env file leaves the sidecar's requirement satisfied.

Also worth recording as a non-override: `install_pyroscope_agent.sh` is deliberately **kept**.
`StressJobService.kt:150-151` hostPath-mounts `/usr/local/pyroscope` into stress pods, so the jar is
still required on those nodes even though Cassandra stops using it.

## Conflicts with other in-flight changes

No other open change declares a `profiling` capability, and none modifies a requirement this change
touches. Checked by enumerating every `specs/<capability>/` directory across all open changes.

Two open changes touch **adjacent code** without any requirement conflict. Recording them because a
textual merge conflict is possible even where the specs agree:

- **`up-fail-fast`** — touches `SetupInstance` from the outside only. It adds exit-code checking to
  four nested command invocations made by `Up` (`WriteConfig`, `SetupInstance`, `ConfigureAxonOps`,
  `GrafanaUpdateConfig`), and fixes the SSH readiness wait to cover the control node. It does not
  alter `SetupInstance`'s internals, whereas this change replaces `setupCassandraSystemdEnv` inside
  it. Different files, no requirement overlap; no action needed beyond ordinary rebasing.
- **`add-aws-sso-support`** — routes the Pyroscope jar download through the versioned S3 download
  cache in the Cassandra packer install path. This touches `install_pyroscope_agent.sh`, which this
  change explicitly retains, so the two agree rather than conflict.
- **`add-trino-kit`** — references Pyroscope only for Trino's own agent injection, which is out of
  scope here and governed by the `trino` spec.

## Related work outside OpenSpec

**#872** (`cassandra asprof` on-demand flame graphs) is absorbed into this change by owner decision,
becoming the `fetch` and `flamegraph` verbs. It has no OpenSpec change of its own, so there is no
delta to reconcile — but it should not be implemented separately while this change is in flight. A
comment recording the absorption is on #872; the owner will close it.

**#876 / PR #878** (install arbitrary Cassandra versions on a live cluster) relocates the
`cassandra.in.sh` snippet to a durable `/usr/local/share/easy-db-lab/cassandra.in.sh` and re-appends
it to every newly installed version. This change edits that snippet's *content*, so the two compose
cleanly whichever merges first — and if #878 lands first, this change's edit automatically survives
`cassandra use` version swaps. Not a conflict; a beneficial interaction worth knowing about when
sequencing the merges.
