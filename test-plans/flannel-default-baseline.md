# Lab Plan: Flannel Default Baseline (PR 820 regression check)

## Objective

Prove that the default CNI path is unchanged by PR 820: a cluster provisioned with no `--cni` uses Flannel, comes up clean, runs Cassandra, keeps Traefik and ServiceLB, renders no Cilium scrape jobs, and tears down clean.  No manual intervention anywhere; any is a FAIL.

## Cluster Name

flannel-baseline

## Datacenters

single

## Environment

Single DC, `us-west-2`.  1 control node plus 2 db nodes on `i4i.xlarge`, 0 app nodes, AZs `a` and `b`.  Tailscale on.  No `--cni` flag.

## Steps

### 1. Provision with the default CNI

```bash
$EDB init flannel-baseline --db.count 2 --db.instance-type i4i.xlarge --azs ab --up
```

Pass: `up` completes with the observability stack ready and no error.

### 2. Bind paths

```bash
CLUSTER_DIR=$(dirname "$EDB")
export KUBECONFIG=$CLUSTER_DIR/kubeconfig
CTL_IP=$($EDB ip --private control0 | tail -1); DB0_IP=$($EDB ip --private db0 | tail -1); DB1_IP=$($EDB ip --private db1 | tail -1)
```

### 3. Flannel is the datapath; Cilium is absent

```bash
kubectl get nodes --no-headers
kubectl -n kube-system get pods --no-headers | grep -c cilium
kubectl -n kube-system get svc traefik -o jsonpath='{.spec.type}{"\n"}'
kubectl get pods -A --no-headers | grep -vE 'Running|Completed' | wc -l
$EDB platform cni
kubectl get cm otel-collector-config -o json | jq -r '.data | to_entries[0].value' | grep -cE 'job_name: .(cilium-agent|cilium-operator|hubble).'
```

Pass: 3 nodes Ready; cilium pod count `0`; traefik type `LoadBalancer`; 0 non-running pods; `platform cni` prints the Flannel one-liner and exits 0; Cilium/Hubble scrape job count `0`.  Check the ConfigMap name with `kubectl get cm` if it differs.

### 4. kube-state-metrics and scrapes

```bash
TENANT=${TENANT:-default}
vmq() { curl -s --max-time 10 -H "X-Scope-OrgID: $TENANT" "http://$CTL_IP:9009/prometheus/api/v1/query" --data-urlencode "query=$1" | jq -r '.data.result[] | "\(.metric.instance // .metric.daemonset // "-") \(.value[1])"'; }
vmq 'up{job="kube-state-metrics"}'
vmq 'kube_daemonset_status_number_ready{daemonset="otel-collector"}'
vmq 'up{job="cilium-agent"}'
```

Pass: kube-state-metrics `1`; otel-collector DaemonSet ready `3`; `up{job="cilium-agent"}` returns no series.  `TENANT` is the cluster's observability tenant (`init --tenant`, `default` if none was given); Mimir and Loki return nothing to a query without it.

### 5. Tailscale route to db nodes from this machine

```bash
nc -zv -w 5 $DB0_IP 22; nc -zv -w 5 $DB1_IP 22
```

Pass: both succeed.

### 6. Pod network sanity

```bash
kubectl run smoke --image=nicolaka/netshoot --overrides='{"spec":{"nodeName":"db0"}}' --command -- sleep infinity
kubectl wait pod/smoke --for=condition=Ready --timeout=180s
kubectl exec smoke -- sh -c 'nslookup -timeout=3 kubernetes.default | grep -c Address; curl -s -o /dev/null -w "external %{http_code}\n" --max-time 10 https://example.com/; nc -zv -w 3 '"$CTL_IP"' 6443'
```

Pass: DNS resolves; external `200`; pod → control0:6443 succeeds.

### 7. Cassandra smoke test

```bash
$EDB cassandra use 5.0
$EDB cassandra start
$EDB cassandra nt status
$EDB cassandra cql "SELECT release_version FROM system.local"
```

Pass: both db nodes `UN`; query returns `5.0.x`.

### 8. Grafana: no networking dashboards on Flannel is acceptable; core dashboards present

```bash
curl -s "http://$CTL_IP:3000/api/search?query=" | jq -r '[.[] | .folderTitle] | unique | join(",")'
```

Pass: the folder list includes `cassandra`, `infrastructure`, `observability`.  `networking` may be present (the tree is uploaded whole); its panels are empty on Flannel and that is documented.

### 9. Tear down

```bash
$EDB down --auto-approve
```

Pass: "Teardown completed successfully"; `aws --profile sandbox-admin --region us-west-2 ec2 describe-vpcs --filters Name=tag:Name,Values=flannel-baseline* --query 'length(Vpcs)'` returns `0` after a minute.

## Notes

- This is a regression check for the Flannel path, which PR 820 must not change.  The K3s server args for Flannel are untouched; Traefik and ServiceLB stay.
- No manual intervention.  A node touched by hand between step 1 and step 9 is a FAIL.
