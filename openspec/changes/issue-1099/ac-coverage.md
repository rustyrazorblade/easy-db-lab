| Source | Requirement | Covering scenario(s) | Status |
|--------|-------------|----------------------|--------|
| AC1 | `--version 5.0.x` on amd64 and arm64 starts from the Apache tarball; CQL answers from the operator's machine | `cassandra-kit: Release on amd64 and arm64`; `Default version runs the Apache tarball`; `Both platforms published`; `Missing platform fails publication`; `CQL answers on the NodePort` (unchanged) | amd64 in the K3s test (task 9.2); arm64 in the lab plan (task 11) |
| AC2 | `--build <ref>` runs that build's tarball, with no image built or pushed | `cassandra-kit: Branch build without an image`; `Build ref is never read as a release` | D5 |
| AC3 (amended by D9) | Same version on the VM path and in the kit: `cassandra.yaml` differs only in addresses, ports, paths and the snitch | `cassandra-kit: Same version on both paths`; `Placement wins over VM keys`; `User tuning reaches the pods`; `No workspace patch off AWS`; `cassandra: Patches merge in order` | D9 approved: "ports" added because the native port is the NodePort 30942 (#1101 forbids host networking) |
| AC4 | A version change on a running kit restarts pods one at a time and keeps serving at QUORUM | `cassandra-kit: Rolling upgrade keeps QUORUM`; `Upgrade changes the pod template`; `Recorded arg updated`; `kit-variable-contributors: One arg rewritten` | D7. Three anti-affine pods do not fit one K3s container, so QUORUM is verified in the lab plan (task 11) |
| AC5 | A tarball already on the PVC is not downloaded again | `cassandra-kit: Cache hit makes no download`; `Restart with the URL unreachable`; `Rollback is a cache hit` | D1 |
| Scope | Multi-arch JRE base image | `cassandra-kit: Both platforms published`; `JDK picks the tag`; `yq matches the AMI` | D1, D6 |
| Scope | Checksum-verified fetch | `cassandra-kit: Checksum mismatch`; `No digest fails before any pod` | |
| Scope | Fetch sources: Apache, cassandra-builds, catalog presigned URL | `cassandra-kit: Default version runs the Apache tarball`; `Branch build without an image`; `Catalog build gets a presigned URL` | |
| Scope | Same scripts as the VM path | `cassandra-kit: Scripts come from the distribution`; `cassandra: patch-config contract unchanged`; `VM defaults unchanged`; `Overrides applied under dash` | D3: the scripts ship in the kit ConfigMap, not "packaged in the image" as the issue says (owner-approved departure) |
| Scope | `--image` stays and bypasses the tarball path | `cassandra-kit: Image override bypasses the tarball path`; `Unknown image fails before any pod`; `Build and image together are refused` | |
| Scope | Plain StatefulSet | `cassandra-kit: StatefulSet topology` (unchanged requirement) | |
| Scope | Memory per environment | `cassandra-kit: Default memory off owned nodes`; `Default memory on owned nodes`; `platform-substrate: Placement variables follow the db nodes`; `Not Guaranteed off owned nodes` | D8 |
| Scope | CPU request equals the limit where Guaranteed | `cassandra-kit: Guaranteed on owned nodes`; `No CPU limit elsewhere` | D8 |
| Scope | Resolution in the CLI reaches the kit | `kit-variable-contributors: Contributed variable reaches the steps`; `Contributed file written before the first step`; `Contributor failure stops the start`; `Unknown contributor name`; `Cluster state not shadowed` | D2 |
| Owner | Config-hash annotation rolls pods on a patch edit | `cassandra-kit: Patch edit rolls the pods`; `Nothing changed, nothing rolls` | |
| Owner | Pod Security restricted, `fsGroup: 999` | `cassandra-kit: Restricted namespace admits the pods` | |
| Risk | `cassandra.in.sh` bashism breaks VMs under dash | `cassandra: Overrides applied under dash`; `VM defaults unchanged`; `Runs under dash` | |
| Risk | Presigned URL expires before a cold-cache reschedule | `cassandra-kit: Expired presigned URL` | |
| Risk | Half-extracted cache reused | `cassandra-kit: Interrupted extract is not used` | |
| Risk | `cassandra.in.sh` resource attributes drop `host.name` | `cassandra: Extra resource attributes keep host.name`; `cassandra-kit: host.name kept on the tarball path` | |
| Risk | Major-version downgrade during upgrade | `cassandra-kit: Major downgrade refused`; `Unknown upgrade target` | |
| Risk | Stale `cassandra.in.sh` snippet in the cache | `cassandra-kit: Scripts come from the distribution` (the snippet comes from the ConfigMap through `CASSANDRA_INCLUDE`, never from the cache) | |
| Risk | Downloads during a roll lengthen each pod's outage | `cassandra-kit: Rolling upgrade keeps QUORUM` | Only one pod is down at a time; pre-fetching excluded (YAGNI) |
| Excluded | 3.x tarballs ship a Python 2 cqlsh, so in-pod `cqlsh` fails on 3.0 and 3.11 | — | Documentation only (`Kit documentation`); CQL from the operator's machine is unaffected |
| Excluded | Per-version config directory (`download-config` JVM options files) | — | Not in v1 (design Non-Goals); documented as a known difference |
| Excluded | Heap sizing parity with the VM | — | The kit keeps sizing the heap from the container limit by design |
| Excluded | Pruning old cached tarballs | — | YAGNI; kept so a rollback is a cache hit |
| Excluded | #961 deletions (`build-cassandra-ref.yml`, `.github/cassandra-image/`, GHCR `cassandra`, the drop-in image requirement, the `ReleaseLinesBuildPlanTest` cross-check) | — | Out of scope; #961 does them after this change lands |
| Excluded | Multi-DC / per-pod DC and rack | — | #1097 |
