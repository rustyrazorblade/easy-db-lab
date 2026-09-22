# Overrides and conflicts

## Overrides existing behavior

### networking: Pod-network datapath (Cilium ENI native routing, selectable)

This requirement is **not yet in `openspec/specs/networking/spec.md`**. It is an unarchived ADDED
delta in `openspec/changes/cilium-native-routing/specs/networking/spec.md`.

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

Also from the `cilium-native-routing` ADDED delta. **Currently:** the scenario "Flannel cluster
renders no Cilium scrape jobs" is given as "a cluster provisioned with `--cni=flannel` (the
default)". **This change:** removes "(the default)", which would otherwise be false, and marks
Cilium as the default in "Cilium cluster scrapes agent, operator, and Hubble". Behaviour is unchanged.

## Conflicts with other in-flight changes

- `cilium-native-routing` touches `networking` — **sequential, not incompatible.** It ADDs the two
  requirements this change MODIFIES. It must be archived before `issue-819`; alphabetical archive
  order already puts it first. Its tasks 6.2 and 8.4 are closed by this change's live validation
  (task 6.3). Its `observability` delta is untouched here.
- `cassandra-local-builds`, `issue-888`, `issue-892`, `issue-932`, `issue-937`, `issue-939` —
  **no conflict.** None has a `networking`, `memcached-kit`, or `neo4j-kit` delta, and none mentions
  the CNI, `hostPort`, memcached, or Neo4j.
