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
- [ ] 4.2 `docs/user-guide/large-clusters.md`: the datastore is etcd and why (#1134's sqlite write latency); the 80-node numbers once task 6 has them
- [ ] 4.3 `docs/user-guide/tutorial.md` line on the control node's data disk: add the K3s datastore
- [ ] 4.4 `.claude/skills/k8-expert/SKILL.md` and `README.md`: name etcd as the server's datastore where they describe the K3s server
- [ ] 4.5 Confirm the `cluster-lifecycle` and `instance-storage-validation` deltas still match the code and the tests, and `openspec validate issue-1157` passes

## 5. Local gates

- [ ] 5.1 `./gradlew ktlintFormat`, then `./gradlew check` on JDK 21 (in a subagent); every test passes and detekt reports nothing new

## 6. Real AWS: 80 nodes

- [ ] 6.1 `./gradlew installDist`; record the cluster name and workspace in the state file before `up`
- [ ] 6.2 `init` with 1 control (m5d.xlarge), 76 db (i4i.xlarge), 2 app (c6id.4xlarge), the default profile, `--tag embark_offhours=off`, then `up` from `main` plus this change only; time `up`, and the first agent start to the last node Ready
- [ ] 6.3 During the join and after `up`: `kubectl get --raw /metrics` from the workstation, recording `etcd_request_duration_seconds` p50/p99 for `create` `nodes` and `update` `leases`, `apiserver_flowcontrol_rejected_requests_total` by priority level, and the `node-high` request wait; cilium-operator restarts; whether a manual step was needed; the k3s process RSS on control0
- [ ] 6.4 Check the three facts of design D5 on control0, and `df` of `/` and `/mnt/db1`
- [ ] 6.5 Put the comparison with #1134 (table: each metric, before and after) in the PR and as a comment on #1134; `down --auto-approve` and confirm the instances and VPC are gone

## 7. Real AWS: small-cluster regression

- [ ] 7.1 A 3-db, 1-app cluster with the tag `embark_offhours=off`: run the D5 checks
- [ ] 7.2 Install, start and stop a kit; open the system overview dashboard and confirm metrics, logs and traces from every node
- [ ] 7.3 Back up the cluster state and restore it into a new workspace; confirm `kubectl get nodes` works from the restored workspace
- [ ] 7.4 `sudo systemctl restart k3s` on control0; every node returns to Ready and the kit's objects are intact
- [ ] 7.5 On control0, `k3s etcd-snapshot list` and `ls /mnt/db1/k3s/server/db/snapshots`: the directory is on `/mnt/db1`, and if the run spans 00:00 or 12:00 a scheduled `etcd-snapshot-*` file is there (no on-demand snapshot is taken)
- [ ] 7.6 `down --auto-approve`: the flush completes and the cluster is torn down; record the output in the PR
