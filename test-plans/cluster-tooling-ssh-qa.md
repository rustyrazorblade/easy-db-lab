# Lab Plan: Cluster tooling over SSH (#1078)

## Objective

Validate on a real 3-node cluster that every helm and kubectl call the CLI makes for kits goes
through `SshClusterTooling` and works: the typed steps (helm-repo, helm with a values file and
`--set`, namespace, manifest, manifest-url, kustomize, wait, delete by name and by a set-based
selector, helm-uninstall), the `kit status` helm lookup, the postgres and memcached kits, a
deliberate failure, and input-file cleanup on the control node.

Success: every step below passes, the deliberate failure prints `ClusterToolFailedException`'s
message (the command, `failed on control0 (<ip>)`, the exit status, and stderr), no
`/tmp/easydblab-input-*` file is left on the control node, Grafana answers, and `down` exits 0 and
leaves no AWS resources.

## Cluster Name

edbl-1078-qa

## Datacenters

single

## Environment

3 db nodes, 1 app node (default instance types). Database: Cassandra (default version). Uses the
worktree build (`./gradlew installDist`) as `$EDB`.

## Steps

### 1. Provision

```bash
$EDB init edbl-1078-qa --db 3 --app 1 --up --tag embark_offhours=off
```

Expect: `up` installs Cilium through helm on the control node and finishes with exit 0.

### 2. Cassandra start, 3 UN

```bash
$EDB cassandra use 5.0
$EDB cassandra start
$EDB cassandra nt status
```

Expect: three `UN` lines.

### 3. postgres: install, start, status, refused second start, upgrade, stop

```bash
$EDB kit install postgres
$EDB postgres start
$EDB postgres status
$EDB postgres start; echo "exit=$?"
$EDB kit install postgres --force
$EDB postgres stop
```

Expect: `kit install` runs helm-repo and `helm upgrade --install cnpg-operator`. `start` succeeds.
`status` shows Running with the endpoint. The second `start` is refused with a collision and a
non-zero exit. `kit install --force` re-runs `helm upgrade --install` (the upgrade path) and
succeeds. `stop` deletes the Cluster, Service, Secret and ImageCatalog by name.

### 4. memcached: wait and delete-by-selector

```bash
$EDB kit install memcached
$EDB memcached start
$EDB memcached status
$EDB memcached stop
```

Expect: `start` waits for the deployment to be Available. `stop` looks the objects up by
`easydblab/kit=memcached` and deletes exactly those, then returns once the pods are gone.

### 5. Typed-steps QA kit (namespace, manifest-url, kustomize, values file, set-based selector)

The kit lives next to this plan, in `test-plans/cluster-tooling-ssh-qa/tooling-qa/`. Register its
parent as a kit source by absolute path, so `kit install tooling-qa` takes the named path and runs
the kit's `install:` steps. `kit install --from` only scaffolds and never runs them. `REPO` is the
checkout that holds this plan, derived from the binary the wrapper runs.

```bash
EDB_BIN=$(sed -n 's/^exec "\(.*\)" "\$@"$/\1/p' "$EDB")
REPO=$(cd "$(dirname "$EDB_BIN")/.." && pwd)
ls -d "$REPO/test-plans/cluster-tooling-ssh-qa/tooling-qa"
$EDB kit source add tooling-qa-plan "$REPO/test-plans/cluster-tooling-ssh-qa"
$EDB kit install tooling-qa
$EDB tooling-qa start
$EDB tooling-qa status
"$(dirname "$EDB")/bin/ssh" control0 'KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl get cm -n default -l tooling-qa -o name; KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl get hpa -n default podinfo -o name; KUBECONFIG=/etc/rancher/k3s/k3s.yaml helm get values tooling-qa-podinfo -n tooling-qa'
```

Expect: install runs helm-repo, namespace (one `kubectl apply -f /tmp/easydblab-input-*.yaml`) and
manifest-url (`hpa/podinfo`). `start` installs podinfo with the values file (replicaCount 2) and
`ui.message=tooling qa`, applies the kustomization, applies the three ConfigMaps, and both waits
pass. `status` (the kit status command: `helm status` of the runtime release, then its pods) reports
`Running (2/2 pods ready)`.

### 6. Deliberate kubectl failure

The kit's `backup` phase deletes an unknown kind with `ignore-not-found: false`:

```bash
$EDB tooling-qa backup; echo "exit=$?"
```

Expect: a non-zero exit, and the message
`kubectl delete nosuchkind/x -n default failed on control0 (<ip>) with exit status 1` followed by
`stderr:` and kubectl's `the server doesn't have a resource type "nosuchkind"`.

### 7. Stop, uninstall, and the selector

```bash
$EDB tooling-qa stop
"$(dirname "$EDB")/bin/ssh" control0 'KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl get cm -n default -l tooling-qa -o name; KUBECONFIG=/etc/rancher/k3s/k3s.yaml helm list -A'
$EDB tooling-qa uninstall
"$(dirname "$EDB")/bin/ssh" control0 'KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl get ns tooling-qa'
$EDB kit source remove tooling-qa-plan
```

Expect: after `stop`, only `configmap/tooling-qa-kept` remains: the set-based selector
`tooling-qa in (selected, other)` reached kubectl as one argument. The `tooling-qa-podinfo`
release is gone. `uninstall` deletes the namespace (`NotFound`, or `Terminating` for a short time).

### 8. No input files left on the control node

```bash
"$(dirname "$EDB")/bin/ssh" control0 'ls /tmp/easydblab-input-* 2>/dev/null | wc -l'
```

Expect: `0`.

### 9. Grafana reachable

The workspace `bin/curl` wrapper resolves the network mode and the tunnel itself.

```bash
"$(dirname "$EDB")/bin/curl" -s -o /dev/null -w "%{http_code}\n" http://control0:3000/api/health
```

Expect: HTTP 200.

### 10. Tear down

Take the cluster id from `$EDB status` before `down`, then check that nothing tagged with it is
left. The kit source removal repeats step 7's, so a run that stopped early leaves no
registration; "not found" is fine here.

```bash
CLUSTER_ID=$($EDB status | awk '/^Cluster ID:/{print $3}')
test -n "$CLUSTER_ID" || { echo "no cluster id"; exit 1; }
$EDB kit source remove tooling-qa-plan || true
$EDB down --auto-approve; echo "exit=$?"
aws ec2 describe-instances --profile easy-db-lab --region us-west-2 \
  --filters "Name=tag:ClusterId,Values=$CLUSTER_ID" "Name=instance-state-name,Values=pending,running,stopping,stopped" \
  --query 'Reservations[].Instances[].InstanceId' --output text
aws ec2 describe-vpcs --profile easy-db-lab --region us-west-2 \
  --filters "Name=tag:ClusterId,Values=$CLUSTER_ID" --query 'Vpcs[].VpcId' --output text
aws ec2 describe-security-groups --profile easy-db-lab --region us-west-2 \
  --filters "Name=tag:ClusterId,Values=$CLUSTER_ID" --query 'SecurityGroups[].GroupId' --output text
```

Expect: `exit=0`, and no instances, VPC or security groups left for the cluster id.
