# Overrides and conflicts

## Overrides existing behavior

### networking: Pod-network datapath (Cilium ENI native routing, selectable)

This requirement is in `openspec/specs/networking/spec.md`. The `cilium-native-routing` change
added it and has been archived (PR 959); this change MODIFIES it there.

**Currently:** "The CNI SHALL be selectable via `--cni=<cilium|flannel>` at init time; the default
SHALL be `flannel` (K3s's built-in datapath). … (Flipping the default to `cilium` is tracked
separately.)" Its scenario "Default provision uses Flannel" asserts: WHEN a cluster is provisioned
with no `--cni` option, THEN it uses K3s's built-in Flannel datapath.

**This change:** the default is `cilium`, and `--cni=flannel` selects Flannel. The "tracked
separately" sentence is removed — this change is that tracking. "Default provision uses Flannel" is
replaced by "Default provision uses Cilium ENI native routing", and "Flannel remains selectable" is
added. A cluster with no recorded CNI is still treated as Flannel (new scenario). All other
scenarios are kept, with `--cni=cilium` givens widened to "a Cilium cluster".

### networking: Cilium metrics are collected only on a Cilium cluster

Also in `openspec/specs/networking/spec.md`, added by the archived `cilium-native-routing` change.
**Currently:** the scenario "Flannel cluster
renders no Cilium scrape jobs" is given as "a cluster provisioned with `--cni=flannel` (the
default)". **This change:** removes "(the default)", which would otherwise be false, and marks
Cilium as the default in "Cilium cluster scrapes agent, operator, and Hubble". Behaviour is unchanged.

## Conflicts with other in-flight changes

None. `issue-819` is the only change under `openspec/changes/`. `cilium-native-routing`, which
added the two requirements this change MODIFIES, is archived (PR 959) at
`openspec/changes/archive/2026-09-22-cilium-native-routing/`; its tasks 6.2 and 8.4 were closed by
this change's live validation (task 6.3). The other changes previously in flight
(`cassandra-local-builds`, `issue-888`, `issue-892`, `issue-932`, `issue-937`, `issue-939`) were
archived in the same batch.
