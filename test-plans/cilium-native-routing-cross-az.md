# Lab Plan: Cilium ENI Native Routing Cross-AZ Validation (PR 820)

## Objective

Prove that a cluster provisioned with `--cni=cilium` runs Cilium in ENI native-routing mode with no tunnel, that all agents reach Ready, that pods get VPC-routable IPs from their node's AZ subnet, and that cross-AZ pod-to-pod, pod-to-ClusterIP, and pod-to-external traffic all work.  This is task 6.2 of `openspec/changes/cilium-native-routing/tasks.md`.  The plan also proves the Cilium observability additions on the same PR: agent and operator metrics scraped, kube-state-metrics up, Cilium pod logs in Loki, `platform cni` read-back, Hubble UI reachable, install annotations present, and the three networking dashboards populated.  Success: every check in steps 2 through 17 passes.

## Cluster Name

cilium-native

## Datacenters

single

## Environment

Single DC, `us-west-2`.  1 control node plus 2 db nodes on `i4i.xlarge` (local NVMe, no EBS), 0 app nodes.  AZs limited to `a` and `b` so the two db nodes land in different AZs.  Tailscale on (profile default).  No Cassandra, no kits.  AWS CLI profile for the one `aws` check: `sandbox-admin`.

## Steps

### 1. Provision with Cilium

```bash
$EDB init cilium-native --db.count 2 --db.instance-type i4i.xlarge --azs ab --cni cilium --up
```

Pass: `up` completes with no error and the Cilium install event fires.  Fail: any worker agent times out joining (the bootstrap deadlock this PR removes).  If the Cilium install itself fails, capture `$SSH control0 'sudo journalctl -u k3s --since -10m'` and the `cilium install` output before teardown.

### 2. Bind workspace paths and node names

```bash
CLUSTER_DIR=$(dirname "$EDB")
export KUBECONFIG=$CLUSTER_DIR/kubeconfig
SSH="ssh -F $CLUSTER_DIR/sshConfig"
CTL_IP=$($EDB ip --private control0 | tail -1)
DB0_IP=$($EDB ip --private db0 | tail -1)
DB1_IP=$($EDB ip --private db1 | tail -1)
node_for() { kubectl get nodes -o json | jq -r --arg ip "$1" '.items[] | select(.status.addresses[] | select(.type=="InternalIP" and .address==$ip)) | .metadata.name'; }
CTL_NODE=$(node_for "$CTL_IP"); DB0_NODE=$(node_for "$DB0_IP"); DB1_NODE=$(node_for "$DB1_IP")
echo "control0=$CTL_NODE db0=$DB0_NODE db1=$DB1_NODE"
```

Pass: all three node variables are non-empty.

### 3. All nodes Ready, db nodes in different AZs

```bash
kubectl get nodes -L topology.kubernetes.io/zone -o wide
```

Pass: 3 nodes `Ready`; `$DB0_NODE` and `$DB1_NODE` show different zones.  Fail: any `NotReady`, or both db nodes in one zone.  A single-zone result voids the cross-AZ checks: tear down and re-provision.

### 4. Datapath is native, no VXLAN, kube-proxy retained

```bash
kubectl -n kube-system exec ds/cilium -c cilium-agent -- cilium status | grep -E 'Routing|Masquerading|KubeProxyReplacement|IPAM'
kubectl -n kube-system get cm cilium-config -o json | jq -r '.data | {"routing-mode","tunnel-protocol","ipam","kube-proxy-replacement","egress-masquerade-interfaces"}'
for h in control0 db0 db1; do echo "== $h"; $SSH $h 'ip -d link | grep -c vxlan || true'; done
```

Pass: `Routing: Network: Native`, `KubeProxyReplacement: False`, `Masquerading: IPTables [ens+]`; `routing-mode` is `native`, `ipam` is `eni`; vxlan count is `0` on all three hosts.

### 5. ciliumnode shows ENIs with allocated IPs per node

```bash
kubectl get ciliumnode -o json | jq -r '.items[] | "\(.metadata.name) enis=\(.status.eni.enis | length) subnets=\([.status.eni.enis[].subnet.cidr] | unique | join(",")) used=\(.status.ipam.used | length)"'
```

Pass: each of the 3 nodes has `enis >= 1` and `used >= 1`.  Record each node's subnet CIDR.

### 6. `platform cni` read-back matches steps 4 and 5

```bash
$EDB platform cni
```

Pass: prints CNI `cilium`, routing mode `native`, IPAM `eni`, kube-proxy replacement `false`, masquerade interfaces `ens+`, and per-node ENI/IP counts equal to step 5.  Fail: any value differs from steps 4 or 5, or the command errors.

### 7. Start one server pod on each db node

```bash
for n in db0 db1; do eval NODE=\$$(echo $n | tr a-z A-Z)_NODE; cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: Pod
metadata: { name: srv-$n, labels: { app: srv, node: $n } }
spec:
  nodeName: $NODE
  containers:
  - name: web
    image: nicolaka/netshoot
    command: ["python3", "-m", "http.server", "8080"]
EOF
done
kubectl wait pod/srv-db0 pod/srv-db1 --for=condition=Ready --timeout=180s
kubectl get pod srv-db0 srv-db1 -o wide
```

Pass: both pods `Running` with an IP.

### 8. Each pod IP falls in its node's AZ subnet

```bash
for n in db0 db1; do
  eval NODE=\$$(echo $n | tr a-z A-Z)_NODE
  POD_IP=$(kubectl get pod srv-$n -o jsonpath='{.status.podIP}')
  SUBNETS=$(kubectl get ciliumnode $NODE -o json | jq -r '[.status.eni.enis[].subnet.cidr] | unique | join(" ")')
  echo "$n pod=$POD_IP node-subnets=$SUBNETS"
  echo "$SUBNETS" | tr ' ' '\n' | cut -d. -f1-3 | grep -qx "$(echo $POD_IP | cut -d. -f1-3)" && echo "  IN SUBNET" || echo "  NOT IN SUBNET"
done
```

Pass: both print `IN SUBNET` (subnets are /24 per AZ, so the /24 prefix match is exact).  Fail: `NOT IN SUBNET`, or a pod IP in a `10.42.x.x` overlay range.

### 9. Cross-AZ pod-to-pod by pod IP

```bash
DB1_POD_IP=$(kubectl get pod srv-db1 -o jsonpath='{.status.podIP}')
kubectl exec srv-db0 -- curl -s -o /dev/null -w '%{http_code}\n' --max-time 5 http://$DB1_POD_IP:8080/
```

Pass: `200`.  Fail: `000` or timeout.  ICMP is not permitted by the security group; do not use ping.

### 10. Pod-to-ClusterIP and pod-to-external

```bash
kubectl expose pod srv-db1 --name=srv-db1-svc --port=8080
SVC_IP=$(kubectl get svc srv-db1-svc -o jsonpath='{.spec.clusterIP}')
kubectl exec srv-db0 -- curl -s -o /dev/null -w '%{http_code}\n' --max-time 5 http://$SVC_IP:8080/
kubectl exec srv-db0 -- curl -s -o /dev/null -w '%{http_code}\n' --max-time 10 https://example.com/
```

Pass: `200` for the ClusterIP (kube-proxy path) and `200` for the external URL (masquerade path).

### 11. Hubble sees the cross-AZ flow and exports metrics

```bash
kubectl -n kube-system exec ds/cilium -c cilium-agent -- hubble observe --last 50 --from-pod default/srv-db0 --to-pod default/srv-db1
curl -s --max-time 5 http://$DB0_IP:9965/metrics | grep -c '^hubble_'
```

Pass: at least one flow line `srv-db0 -> srv-db1` with `FORWARDED`; metric count `> 0`.

### 12. Cilium agent, operator, and kube-state-metrics are scraped into Mimir

Wait 60 seconds after step 11 so at least three scrape intervals have run.

```bash
sleep 60
TENANT=${TENANT:-default}
vmq() { curl -s --max-time 10 -H "X-Scope-OrgID: $TENANT" "http://$CTL_IP:9009/prometheus/api/v1/query" --data-urlencode "query=$1" | jq -r '.data.result[] | "\(.metric.instance // .metric.node // .metric.daemonset) \(.value[1])"'; }
echo "== cilium-agent up"; vmq 'up{job="cilium-agent"}'
echo "== cilium-operator up"; vmq 'up{job="cilium-operator"}'
echo "== kube-state-metrics up"; vmq 'up{job="kube-state-metrics"}'
echo "== cilium daemonset ready"; vmq 'kube_daemonset_status_number_ready{daemonset="cilium"}'
echo "== operator ipam"; vmq 'sum by (type) (cilium_operator_ipam_ips)'
echo "== endpoint state"; vmq 'sum by (endpoint_state) (cilium_endpoint_state)'
```

Pass: `up{job="cilium-agent"}` is `1` on 3 instances; `up{job="cilium-operator"}` is `1` on 1 instance; `up{job="kube-state-metrics"}` is `1`; `kube_daemonset_status_number_ready{daemonset="cilium"}` is `3`; `cilium_operator_ipam_ips` has `available` and `used` series with non-zero values; `cilium_endpoint_state{endpoint_state="ready"}` is `> 0`.  Fail: any query returns no series.  `TENANT` is the cluster's observability tenant (`init --tenant`, `default` if none was given); Mimir and Loki return nothing to a query without it.

### 13. Cilium pod logs are in Loki

```bash
$EDB logs query -q '{k8s_namespace_name="kube-system", k8s_pod_name=~"cilium-operator.*"}' --limit 5
$EDB logs query -q '{k8s_namespace_name="kube-system", k8s_pod_name=~"cilium-.*"}' --limit 5
```

Pass: both return at least one line.  Fail: zero lines from either.  `-q` sends the LogQL unchanged; pod logs carry the `k8s_namespace_name` and `k8s_pod_name` stream labels.

### 14. Hubble UI answers over Tailscale, and the install annotations exist

Read the NodePort from `Constants` in this branch (`grep -n -i "hubble" src/main/kotlin/com/rustyrazorblade/easydblab/Constants.kt`) and bind it to `HUBBLE_UI_PORT`.

```bash
curl -s -o /dev/null -w '%{http_code}\n' --max-time 10 http://$CTL_IP:$HUBBLE_UI_PORT/
curl -s --max-time 10 "http://$CTL_IP:3000/api/annotations?tags=cilium" | jq -r '.[] | "\(.time) \(.text)"'
```

Pass: Hubble UI returns `200`; the annotations query returns two entries, one containing `started` and one containing `finished`.  Fail: `000`/`404` from the UI, or fewer than two annotations.

### 15. Networking dashboards are deployed and every panel has data

Spawn the `dashboard-editor` agent against this live cluster with the worktree path, `$CLUSTER_DIR`, and Grafana at `http://$CTL_IP:3000`.  It writes `dashboards/networking/cilium-datapath.json`, `dashboards/networking/cilium-eni-ipam.json`, and `dashboards/networking/hubble-flows.json`, verifies every metric name against Mimir before it goes in a query, runs `./gradlew installDist` and `$EDB grafana update-config`, and reads each dashboard back from Grafana.  For each panel it runs the panel's query against Mimir and reports the series count.

Pass: all three dashboards exist in the `networking` folder in Grafana; every panel's query returns at least one series.  A panel with zero series is a failure, not a note.  The only allowed zero-series panel is the ICMP panel on `hubble-flows`, which must be zero because the security group has no ICMP rule; the agent must label it as such in the panel description.

### 16. cilium-operator on a worker node allocates ENIs (IMDS hop-limit fix)

Pin the operator to db1, restart it, then push db0 past the primary ENI's IP capacity so the operator must attach a second ENI.

```bash
aws --profile sandbox-admin --region us-west-2 ec2 describe-instances --filters "Name=private-ip-address,Values=$DB0_IP,$DB1_IP" --query 'Reservations[].Instances[].[PrivateIpAddress,Placement.AvailabilityZone,MetadataOptions.HttpPutResponseHopLimit]' --output text
kubectl -n kube-system patch deploy cilium-operator -p "{\"spec\":{\"template\":{\"spec\":{\"nodeSelector\":{\"kubernetes.io/hostname\":\"$DB1_NODE\"}}}}}"
kubectl -n kube-system rollout status deploy/cilium-operator --timeout=180s
kubectl -n kube-system get pod -l io.cilium/app=operator -o wide
kubectl create deployment fanout --image=nicolaka/netshoot --replicas=20 -- sleep infinity
kubectl patch deploy fanout -p "{\"spec\":{\"template\":{\"spec\":{\"nodeName\":\"$DB0_NODE\"}}}}"
kubectl rollout status deploy/fanout --timeout=300s
kubectl get pods -l app=fanout -o wide | grep -c Running
kubectl get ciliumnode $DB0_NODE -o json | jq -r '"enis=\(.status.eni.enis | length) used=\(.status.ipam.used | length)"'
kubectl -n kube-system logs deploy/cilium-operator --since=10m | grep -iE 'imds|credential|metadata|unauthorized' | grep -ci error || true
sleep 60; vmq 'up{job="cilium-operator"}'; vmq 'sum by (type) (cilium_operator_ipam_ips)'
```

Pass: hop limit `2` on both db nodes; operator pod `Running` on `$DB1_NODE` with `0` restarts; `20` fanout pods `Running`; db0 now shows `enis >= 2`; operator IMDS/credential error count `0`; after the move, `up{job="cilium-operator"}` is still `1` (the SD-based scrape followed the pod to db1) and `cilium_operator_ipam_ips{type="used"}` rose.  Then re-open `cilium-eni-ipam` in Grafana and confirm the ENI count and used-IP panels for db0 show the step change.  Fail: operator in `CrashLoopBackOff`, pods stuck `ContainerCreating` with no IP, `enis` still `1`, or the operator scrape went to `0` after the move.

### 17. OS leaves the secondary ENI unmanaged, host stays single-homed

Run after step 16, which attached `ens6` on db0.

```bash
for h in db0 db1; do echo "== $h"; $SSH $h 'networkctl list; ls /etc/systemd/network/; ip route show default'; done
```

Pass: on db0, `ens5` is `configured`/managed and `ens6` is `unmanaged`; both drop-ins `05-cilium-eni-primary.network` and `06-cilium-eni-unmanaged.network` are present on both hosts; exactly one default route per host.  SSH itself succeeding here is the host-reachability check.

### 17b. Cassandra smoke test on the Cilium cluster

Cassandra runs on the EC2 hosts, not in K8s, but its CLI path (SSH, nodetool, CQL over the tunnel) must be unaffected by the CNI.

```bash
$EDB cassandra use 5.0
$EDB cassandra start
$EDB cassandra nt status
$EDB cassandra cql "SELECT release_version FROM system.local"
```

Pass: both db nodes `UN` in `nt status`; the CQL query returns `5.0.x`.

### 18. Tear down

```bash
$EDB down --auto-approve
```

## Notes

- The security group has no ICMP rule.  All reachability checks use `curl`; never `ping`.
- `i4i.xlarge` allows 4 ENIs with 15 IPs each.  20 fanout pods plus system pods on db0 exceeds one ENI, which is what forces the operator to allocate a second one in step 16.
- Step 3 is a hard gate: if both db nodes land in one AZ, the cross-AZ checks prove nothing.  Tear down and re-provision.
- Steps 12 through 15 depend on the observability code on this branch.  Run `./gradlew installDist` in the worktree before step 1 so the cluster is built from it.
- Step 17 depends on the base AMI carrying `configure_cilium_eni_networkd.sh` with the `Driver=ena` match and the cloud-init no-hotplug drop-in; `build-image` must have run from this branch.
- This plan is a no-intervention run: any manual change to a node between step 1 and step 18 is a FAIL and a defect.
- Record every pass/fail value in the journal.  The PR's task 6.2 is checked off from this run, and the dashboard JSONs from step 15 are committed on the PR branch.
