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
$EDB cassandra start
$EDB cassandra nodetool status
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

### 5. Typed-steps QA kit (manifest-url, kustomize, values file, set-based selector)

```bash
$EDB kit install --from test-plans/cluster-tooling-ssh-qa/kit --kit tooling-qa --size 1Gi
$EDB tooling-qa start
$EDB tooling-qa status
ssh -F "$(dirname "$EDB")/sshConfig" control0 'KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl get cm -n default -l tooling-qa -o name; KUBECONFIG=/etc/rancher/k3s/k3s.yaml helm get values tooling-qa-podinfo -n tooling-qa'
$EDB tooling-qa stop
ssh -F "$(dirname "$EDB")/sshConfig" control0 'KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl get cm -n default -l tooling-qa -o name'
$EDB tooling-qa uninstall
```

Expect: install runs helm-repo, namespace (one `kubectl apply -f /tmp/easydblab-input-*.yaml`) and
manifest-url. `start` installs podinfo with the values file (replicaCount 2) and
`ui.message=tooling qa`, applies the kustomization, applies the three ConfigMaps, and both waits
pass. `status` reports Running from the helm release. After `stop`, only `tooling-qa-kept`
remains: the set-based selector `tooling-qa in (selected, other)` reached kubectl as one argument.
`uninstall` deletes the namespace.

### 6. Deliberate kubectl failure

Add a stop step that deletes an unknown kind with `ignore-not-found: false`, then run `stop`:

```bash
KIT_DIR="$(dirname "$EDB")/tooling-qa"
printf '  - type: delete\n    kind: nosuchkind\n    name: x\n    namespace: default\n    ignore-not-found: false\n' > /tmp/edbl-1078-bad-step.yaml
```

Insert that block at the end of the `stop:` list in `$KIT_DIR/kit.yaml`, then:

```bash
$EDB tooling-qa stop; echo "exit=$?"
```

Expect: a non-zero exit, and the message
`kubectl delete nosuchkind/x -n default failed on control0 (<ip>) with exit status 1` followed by
`stderr:` and kubectl's `the server doesn't have a resource type "nosuchkind"`.

### 7. No input files left on the control node

```bash
ssh -F "$(dirname "$EDB")/sshConfig" control0 'ls /tmp/easydblab-input-* 2>/dev/null | wc -l'
```

Expect: `0`.

### 8. Grafana reachable

```bash
curl -s -o /dev/null -w "%{http_code}\n" --socks5-hostname 127.0.0.1:"$(grep EDL_SOCKS_PORT "$(dirname "$EDB")/.socks5-proxy.env" | cut -d= -f2)" http://control0:3000/api/health
```

Expect: HTTP 200.

### 9. Tear down

Record the cluster id before `down`, then check that nothing tagged with it is left:

```bash
CLUSTER_ID=$(jq -r .clusterId "$(dirname "$EDB")/state.json")
$EDB down --auto-approve; echo "exit=$?"
aws ec2 describe-instances --profile easy-db-lab --region us-west-2 \
  --filters "Name=tag:ClusterId,Values=$CLUSTER_ID" "Name=instance-state-name,Values=pending,running,stopping" \
  --query 'Reservations[].Instances[].InstanceId' --output text
aws ec2 describe-vpcs --profile easy-db-lab --region us-west-2 \
  --filters "Name=tag:ClusterId,Values=$CLUSTER_ID" --query 'Vpcs[].VpcId' --output text
```

Expect: `exit=0`, and no instances, VPC or security groups left for `edbl-1078-qa`.
