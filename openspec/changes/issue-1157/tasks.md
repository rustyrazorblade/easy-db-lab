## 1. Start script tests first

- [ ] 1.1 `StartK3sScriptsIntegrationTest`: a server run with `/mnt/db1` mounted writes `/etc/rancher/k3s/config.yaml.d/20-datastore.yaml` holding `cluster-init: true` and `quota-backend-bytes=8589934592`; two runs leave each line once; `10-kubelet-eviction.yaml` is unchanged
- [ ] 1.2 Same test: a server run with no `/mnt/db1` mount exits non-zero, its output names `/mnt/db1` and the datastore, `/var/lib/rancher/k3s` is not a link, no datastore drop-in exists, and the stubbed installer and `systemctl` were not called (the stubs record their calls to a file)
- [ ] 1.3 Same test: the existing `a host without a data disk ...` case keeps running for the agent only; an agent run never writes `20-datastore.yaml`
- [ ] 1.4 Run the class (in a subagent) and confirm 1.1 and 1.2 fail for the right reason

## 2. Start script

- [ ] 2.1 `start-k3s-server.sh`: fail at the top, before any relocation or install, when `/mnt/db1` is not a mount point, with one line naming `/mnt/db1` and why the datastore needs it
- [ ] 2.2 Same script: write `20-datastore.yaml` with a fixed heredoc on every run, before `systemctl start k3s`, with a comment that names kine/sqlite's single writer (#1134), the one-member etcd, the 8 GiB quota and why, and that scheduled snapshots keep K3s's defaults and are K3s's internal files, not owner data
- [ ] 2.3 Remove the server script's "if available" wording for the data disk relocation; leave the agent script's relocation as it is
- [ ] 2.4 Run `StartK3sScriptsIntegrationTest` (in a subagent) and confirm it passes

## 3. K3s boots on etcd with the real drop-in

- [ ] 3.1 New integration test (own `K3sContainer` from `SharedK3s.K3S_IMAGE`, not the shared one): copy the server script's datastore drop-in, extracted from the packaged `start-k3s-server.sh` heredoc, into `/etc/rancher/k3s/config.yaml.d/` before start; assert `/readyz?verbose` lists `[+]etcd ok`, the node has `node-role.kubernetes.io/etcd=true`, `/var/lib/rancher/k3s/server/db/etcd/member` exists and `state.db` does not
- [ ] 3.2 Same test: restart the container's K3s and confirm an object created before the restart (a ConfigMap) is still there and the node is Ready again
- [ ] 3.3 Run it (in a subagent) and confirm it passes, and that it fails when the drop-in is left out

## 4. Specs and docs

- [ ] 4.1 `docs/user-guide/kubernetes.md` (Architecture): the server's datastore is one-member embedded etcd on `/mnt/db1/k3s/server/db/etcd`; scheduled snapshots twice a day into `/mnt/db1/k3s/server/db/snapshots`, five kept, removed by K3s, K3s's files and not owner data; how to check (`/readyz?verbose`, the `etcd` node label)
- [ ] 4.2 `docs/user-guide/large-clusters.md`: the datastore is etcd and why (#1134's sqlite write latency); that the 80-node comparison is a later run, after #1157 and #1158 are merged
- [ ] 4.3 `docs/user-guide/tutorial.md` line on the control node's data disk: add the K3s datastore
- [ ] 4.4 `.claude/skills/k8-expert/SKILL.md` and `README.md`: name etcd as the server's datastore where they describe the K3s server
- [ ] 4.5 Confirm the `cluster-lifecycle` and `instance-storage-validation` deltas still match the code and the tests, and `openspec validate issue-1157` passes

## 5. Local gates

- [ ] 5.1 `./gradlew ktlintFormat`, then `./gradlew check` on JDK 21 (in a subagent); every test passes and detekt reports nothing new

## 6. Real AWS: small cluster

- [ ] 6.1 `./gradlew installDist`; one cluster of 2 db (c6id.xlarge), 1 app (c6id.xlarge) and the default control, no Spark, no OpenSearch, default profile, `--tag embark_offhours=off`; record the cluster name and workspace in the state file before `up`
- [ ] 6.2 The D5 checks on control0: the etcd node label, `server/db/etcd/member` present, no `state.db`, `/readyz?verbose` lists `[+]etcd ok`; etcd is on `/mnt/db1`, not `/`
- [ ] 6.3 `sudo systemctl restart k3s` on control0; every node returns to Ready
- [ ] 6.4 The snapshot directory `/mnt/db1/k3s/server/db/snapshots` exists on `/mnt/db1` (no on-demand snapshot is taken)
- [ ] 6.5 Install, start and stop two kits (for example clickhouse and postgres); open the Grafana dashboards and confirm metrics from every node; `status`
- [ ] 6.6 Back up the cluster state and restore it into a new workspace; `kubectl get nodes` works from it
- [ ] 6.7 `down --dry-run`, then `down --auto-approve`: the flush completes, 0 instances remain and the VPC is gone; record the output in the PR
