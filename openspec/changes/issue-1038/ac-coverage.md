# Acceptance criteria coverage for issue-1038

| Source | Requirement | Covering scenario(s) | Status |
|---|---|---|---|
| AC | On AWS, Cassandra reaches UN on the requested node count, and CQL answers on its NodePort from the operator's machine | cassandra-kit: Cluster reaches UN; CQL answers on the NodePort; Peer addresses are dialable | ✅ Covered |
| AC | Metrics and logs, labelled with this cluster, can be queried in Mimir and Loki | cassandra-kit: Metrics and logs reach the stack | ✅ Covered |
| AC | Its subcommands run; none is hidden by the built-in `cassandra` command | kit-command-group: Colliding workspace kit is reported; Install refuses a reserved name; Non-colliding kit registers as before | ✅ Covered (D1 name plus D2 fix) |
| AC | A chosen version runs; an unknown version fails before any pod | cassandra-kit: Default version; Explicit version; Image override; Unknown version fails before any pod | ✅ Covered |
| AC | Starts from a Homebrew install with no source checkout | cassandra-kit: Install from the classpath | ✅ Covered |
| AC | Data survives stop and start, or the gap is tracked in #985 | cassandra-kit: Stop then start keeps data; Stop keeps claims | ✅ Covered (D7; no #985 deferral needed) |
| Risk | Memory per pod on small instances | cassandra-kit: Built-in cassandra-pods kit (`--memory` and `--heap` args) | ✅ Covered: request equals limit, default 2Gi, heap derived from the limit |
| Risk | Duplicate series when the AMI Cassandra also runs | cassandra-kit: AMI Cassandra already running | ✅ Covered |
| Risk | Slow start with OrderedReady | cassandra-kit: Start times out | ✅ Covered (bounded wait with diagnostics) |
| Risk | Apple Silicon: GHCR images are amd64-only | — | ⏭ Excluded: tracked by #1036 (multi-arch images); Docker Official images work |
| Risk | Registry check needs network access | — | ⏭ Excluded: the image pull needs the same access, so the check adds no new failure mode |
| Scope | Existing Cassandra dashboards show kit pods | cassandra-kit: Overview dashboard shows the kit | ✅ Covered |
| Scope | Docs | cassandra-kit: Docs list the kit | ✅ Covered |
