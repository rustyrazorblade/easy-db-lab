# Lab Plan: FerrosaDB kit on a 3-node ring (issue 969)

## Objective

Prove on a real cluster every part of the `ferrosa` kit and its engine and node changes that the unit and integration tiers cannot reach. The question is: does `ferrosa start` form one FerrosaDB ring on the db nodes, serve all seven client listeners, honor every start option, keep data on the data disk, and feed metrics, logs and CPU profiles to the observability stack?

The run passes when every acceptance criterion of issue 969 has a pass result below. It also produces the kit's `metrics-catalog.json`, `METRICS.md` and `dashboards/ferrosa.json` (tasks 10.3 to 10.5), and it settles the cassandra-easy-stress replication argument for the docs (task 10.6).

## Cluster Name

ferrosa-kit-test

## Datacenters

single

## Environment

- 3 db nodes, `i4i.xlarge` (local NVMe, no EBS).
- 1 control node, `m5d.xlarge` (the `init` default; `init` has no control instance-type flag).
- 1 app node, `c6id.2xlarge` (runs the cassandra-easy-stress Jobs and the `cqlsh` client).
- Tenant `default`, CNI default (Cilium).
- The AMIs were just rebaked: `init` picks the newest `cassandra-amd64` image, which sits on a base image with the kubelet ECR credential provider.
- Region `us-west-2`. Every step reads it from `$EDB aws region`.
- `AWS_PROFILE`: the owner's account profile. The owner gives the name to the run session. This public file does not record it, or any account number or bucket name.
- The laptop needs `kubectl`, `docker` (with `buildx`), `aws`, `gh`, `jq` and `curl`. The kit's shell steps run `kubectl` and `jq` on the laptop. It must reach the control node's private IP over Tailscale for `bin/export-workload-metrics` (step 2 checks this).

Images under test:

- `ghcr.io/ferrosadb/ferrosa:<tag>`: the published build. `<tag>` is the CalVer tag of the newest successful upstream `Release` dispatch run (`vYYYY.MM.DD.HHMM`), derived in step 4. At plan time (2026-10-09) it was `v2026.10.09.0608`.
- `<registry>/ferrosa-profiling:<version>`: the upstream `ferrosa-profiling` OCI archive for amd64, nightly channel, from the same upstream run, loaded and pushed to the account ECR. The URL pattern comes from upstream `.github/workflows/release.yml` (job `publish-oci-downloads`): `https://downloads.ferrosa.ai/images/amd64/nightly/ferrosa-profiling/linux/oci/<version>/oci.tar`, with `SHA256SUMS` in the same directory. A dispatch run on a tag publishes `<version>` as the tag without its `v`. At plan time the job log showed `Published https://downloads.ferrosa.ai/images/amd64/nightly/ferrosa-profiling/linux/oci/2026.10.09.0608`.
- `<registry>/cassandra-easy-stress:latest`: a registry-to-registry copy of `ghcr.io/apache/cassandra-easy-stress:latest`.

## Steps

### Conventions for every step

Each step after step 2 starts with `. "$(dirname "$EDB")/ferrosa-plan.env"`. That file holds the values the run derives and four helpers:

- `sshn <host> <command>`: SSH through the workspace `sshConfig`.
- `k <kubectl args>`: `sudo k3s kubectl` on `control0`. The arguments pass through a remote shell, so never give it an argument with a space or a quote. Ask for `-o json` and filter with `jq` on the laptop.
- `mq '<PromQL>'`: an instant Mimir query on `control0`, in the cluster tenant. It prints the JSON reply.
- `cq <host> <port>`: `cqlsh` (Cassandra 5.0's, protocol v4) on `app0`. It reads the CQL statements from stdin, with no username or password.

The blocks run in bash or zsh. They never rely on word splitting of a variable.

A failed `ferrosa start` leaves its pods behind (except a validation failure, which creates nothing). Every step that expects a failed start runs `ferrosa stop` after the check, so the next `start` does not hit the collision check.

Every `ferrosa start` after step 9 pins the build with `--version="$FERROSA_TAG"` or `--image`, except step 26. The `nightly` tag moves several times a day, and two different nightly builds must not share one data directory during the run. Step 26 is the one default-image check, and it runs just before the PVs are wiped.

### 1. Provision the cluster

```bash
$EDB init ferrosa-kit-test --db.count 3 --db.instance-type i4i.xlarge --app.count 1 --app.instance-type c6id.2xlarge --up
```

**Pass:** the command exits 0. `up` runs the node-image preflight before K3s starts, so a node without the ECR credential provider files would stop `up` with `K3s.NodeImageMissingCredentialProvider`, and a node without a data disk would stop `up` naming the host.
**Fail:** a non-zero exit. Record the event text. Do not continue.

### 2. Write the plan helpers and check the node preconditions

```bash
CLUSTER_DIR=$(dirname "$EDB")
EDB_BIN=$(sed -n 's/^exec "\(.*\)" "\$@"$/\1/p' "$EDB")
REPO_ROOT=$(cd "$(dirname "$EDB_BIN")/.." && pwd)
ls -d "$REPO_ROOT/src/main/resources/com/rustyrazorblade/easydblab/kits/ferrosa"
REGION=$($EDB aws region)
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
DB_IPS=$($EDB hosts --db --private)
CONTROL_PRIVATE=$($EDB ip control0 --private)
cat > "$CLUSTER_DIR/ferrosa-plan.env" <<EOF
CLUSTER_DIR='$CLUSTER_DIR'
EDB_BIN='$EDB_BIN'
REPO_ROOT='$REPO_ROOT'
KIT_SRC='$REPO_ROOT/src/main/resources/com/rustyrazorblade/easydblab/kits/ferrosa'
REGION='$REGION'
TENANT='default'
REG='$ACCOUNT.dkr.ecr.$REGION.amazonaws.com'
DB_IPS='$DB_IPS'
CONTROL_PRIVATE='$CONTROL_PRIVATE'
EOF
cat >> "$CLUSTER_DIR/ferrosa-plan.env" <<'EOF'
sshn() { ssh -F "$CLUSTER_DIR/sshConfig" "$@"; }
k() { sshn control0 "sudo k3s kubectl $*"; }
mq() { sshn control0 "curl -s -H 'X-Scope-OrgID: $TENANT' http://localhost:9009/prometheus/api/v1/query --data-urlencode query@-" <<<"$1"; }
cq() { sshn app0 "/usr/local/cassandra/5.0/bin/cqlsh --protocol-version=4 $1 $2"; }
EOF
. "$CLUSTER_DIR/ferrosa-plan.env"

for t in kubectl jq docker aws gh curl; do command -v "$t" >/dev/null || echo "MISSING $t"; done
docker buildx version
aws sts get-caller-identity --query Arn --output text

$EDB status
$EDB hosts

# Every node: /mnt/db1 is a mount of a device that is not the root device.
for h in control0 db0 db1 db2 app0; do
  sshn "$h" 'r=$(findmnt -n -o SOURCE /); d=$(findmnt -n -o SOURCE /mnt/db1); if [ -n "$d" ] && [ "$d" != "$r" ]; then echo "$(hostname) PASS root=$r db1=$d"; else echo "$(hostname) FAIL root=$r db1=$d"; fi'
done

# Every node: the credential provider files exist, and K3s found them.
for h in control0 db0 db1 db2 app0; do
  sshn "$h" 'ls -l /var/lib/rancher/credentialprovider/bin/ecr-credential-provider /var/lib/rancher/credentialprovider/config.yaml; grep -c "dkr.ecr" /var/lib/rancher/credentialprovider/config.yaml; sudo journalctl -u k3s -u k3s-agent --no-pager | grep -c "Kubelet image credential provider bin dir and configuration file found"'
done

# The laptop reaches Mimir on the control node's private IP (needed by step 22).
curl -sf --max-time 5 "http://$CONTROL_PRIVATE:9009/ready"; echo " exit=$?"

# cqlsh works on app0.
sshn app0 '/usr/local/cassandra/5.0/bin/cqlsh --version'
```

**Pass:**
- `hosts` lists `control0`, `db0`, `db1`, `db2` and `app0`. If the app node has another alias, use that alias in every step and in the `cq` helper.
- Every node prints `PASS`, with an NVMe device for `/mnt/db1`.
- Every node lists both credential-provider files, the config count is at least 1, and the journal count is at least 1.
- `curl` prints `ready` and `exit=0`.
- `cqlsh --version` prints a version.

**Fail:**
- A `FAIL` line means data lands on the root volume.
- A missing file or a journal count of 0 means the kubelet has no ECR provider; steps 19 and 20 would then fail with `no basic auth credentials`.
- If `curl` fails, run `$EDB tailscale start` and repeat the `curl` once. If it still fails, record it; step 22 needs it.
- A `MISSING` line names a laptop tool to install before step 4.
- If `cqlsh` is missing, record the output of `sshn app0 'ls /usr/local/cassandra/*/bin/cqlsh; command -v cqlsh'`, and point `cq` at a `cqlsh` that exists.

### 3. `init` refuses an app instance type with no instance store

This runs in a scratch directory, never in the workspace, because `init` writes its config to the current directory. The `$EDB` wrapper always changes to the workspace, so this one step calls the binary that the wrapper runs (`$EDB_BIN`). `init` without `--up` creates no AWS resource, whatever the result. `init` checks the app instance type only when the app count is above 0, so the command sets `--app.count 1`. If `run` scaffolded the workspace with `--jdk`, export the same `JAVA_HOME` first, because `$EDB_BIN` does not go through the wrapper.

```bash
. "$(dirname "$EDB")/ferrosa-plan.env"
SCRATCH=$(mktemp -d)
(cd "$SCRATCH" && "$EDB_BIN" init storage-check --app.count 1 --app.instance-type c5.2xlarge); echo "exit=$?"
(cd "$SCRATCH" && "$EDB_BIN" init storage-check --clean --db.instance-type c5.2xlarge); echo "exit=$?"
```

**Pass:** both commands exit non-zero. The first error says that the app instance type `c5.2xlarge` has no local instance store, and that app nodes need an instance type with instance store (`--ebs.type` applies to db nodes only). The second error names the db instance type `c5.2xlarge` and says to set `--ebs.type`.
**Fail:** either command exits 0. Then `init` would let `up` put that node's data on the root volume.

### 4. Put the profiling build and the stress image in ECR

The profiling version and the published tag come from the same upstream run, so both builds have the same source.

```bash
. "$(dirname "$EDB")/ferrosa-plan.env"
gh run list -R ferrosadb/ferrosa --workflow release.yml --event workflow_dispatch --status success --limit 1 --json databaseId,headBranch,headSha,createdAt
FERROSA_TAG=$(gh run list -R ferrosadb/ferrosa --workflow release.yml --event workflow_dispatch --status success --limit 1 --json headBranch --jq '.[0].headBranch')
RUN_ID=$(gh run list -R ferrosadb/ferrosa --workflow release.yml --event workflow_dispatch --status success --limit 1 --json databaseId --jq '.[0].databaseId')
gh run view "$RUN_ID" -R ferrosadb/ferrosa --json jobs --jq '.jobs[] | select(.name == "Publish ferrosa-profiling OCI / amd64") | .conclusion'
PROF_VERSION=${FERROSA_TAG#v}
PROF_IMAGE="$REG/ferrosa-profiling:$PROF_VERSION"
STRESS_IMAGE="$REG/cassandra-easy-stress:latest"
cat >> "$CLUSTER_DIR/ferrosa-plan.env" <<EOF
FERROSA_TAG='$FERROSA_TAG'
PROF_VERSION='$PROF_VERSION'
PROF_IMAGE='$PROF_IMAGE'
STRESS_IMAGE='$STRESS_IMAGE'
EOF

# The published tag exists on ghcr.io.
docker buildx imagetools inspect "ghcr.io/ferrosadb/ferrosa:$FERROSA_TAG"

aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "$REG"
for repo in ferrosa-profiling cassandra-easy-stress; do
  aws ecr describe-repositories --region "$REGION" --repository-names "$repo" >/dev/null 2>&1 \
    || aws ecr create-repository --region "$REGION" --repository-name "$repo"
done

# Download and verify the profiling OCI archive.
WORK=$(mktemp -d)
BASE="https://downloads.ferrosa.ai/images/amd64/nightly/ferrosa-profiling/linux/oci/$PROF_VERSION"
curl -fSL -o "$WORK/oci.tar" "$BASE/oci.tar"
curl -fsSL -o "$WORK/SHA256SUMS" "$BASE/SHA256SUMS"
cat "$WORK/SHA256SUMS"
EXPECTED=$(awk '/oci\.tar/ {print $1}' "$WORK/SHA256SUMS")
ACTUAL=$(shasum -a 256 "$WORK/oci.tar" | awk '{print $1}')
[ -n "$EXPECTED" ] && [ "$EXPECTED" = "$ACTUAL" ] && echo "checksum PASS" || echo "checksum FAIL expected=$EXPECTED actual=$ACTUAL"

# Load, tag and push it.
LOADED=$(docker load -i "$WORK/oci.tar" | awk -F': ' '/^Loaded image/ {print $2}' | tail -1)
echo "loaded: $LOADED"
docker image inspect "$LOADED" --format '{{.Os}}/{{.Architecture}}'
docker tag "$LOADED" "$PROF_IMAGE"
docker push "$PROF_IMAGE"
docker buildx imagetools inspect "$PROF_IMAGE"

# Copy the stress image registry to registry, all platforms, with no local pull.
docker buildx imagetools create --tag "$STRESS_IMAGE" ghcr.io/apache/cassandra-easy-stress:latest
docker buildx imagetools inspect "$STRESS_IMAGE"
```

**Pass:**
- The profiling job conclusion is `success`, and `imagetools inspect` finds the ghcr.io tag.
- `checksum PASS`.
- The loaded image is `linux/amd64`, and the push succeeds.
- `imagetools inspect` of `$PROF_IMAGE` shows one `linux/amd64` manifest.
- `imagetools inspect` of `$STRESS_IMAGE` lists `linux/amd64`.

**Fail:** any other result. If `docker load` refuses the platform on this arm64 laptop, retry it once with `docker load --platform linux/amd64 -i "$WORK/oci.tar"`, and record that in `issues.md`. If `docker push` reports missing content for another platform, retry it once with `docker push --platform linux/amd64 "$PROF_IMAGE"`. If the SSO session expired, run `aws sso login --no-browser --profile "$AWS_PROFILE"` and repeat the step.

### 5. `kit info` lists every start option and endpoint before install

```bash
$EDB kit list
$EDB kit info ferrosa
```

**Pass:** `kit list` shows `ferrosa`. `kit info` lists, under `start`, the seven options `--version`, `--image`, `--storage` (default `local`), `--log-level` (default `info`), `--heap-profile`, `--heap-sample` and `--env` (marked `[repeatable]`). It lists the seven endpoints with the ports and types CQL 30942 `cql`, Console 30909 `http`, Bolt 30787 `native`, Graph HTTP 30747 `http`, SPARQL 30880 `http`, Postgres 30532 `postgresql` and Flight 30815 `native`.
**Fail:** any option, endpoint, port or type is missing or different.

### 6. Install the kit

```bash
. "$(dirname "$EDB")/ferrosa-plan.env"
$EDB kit install ferrosa
$EDB commands | grep -A6 '^  ferrosa '
k get pv -o json | jq -r '.items[] | select(.metadata.name | startswith("data-ferrosa-")) | "\(.metadata.name) path=\(.spec.local.path) phase=\(.status.phase) affinity=\([.spec.nodeAffinity.required.nodeSelectorTerms[0].matchExpressions[] | "\(.key)=\(.values | join(","))"] | join(" "))"'
for h in db0 db1 db2; do sshn "$h" 'ls -ld /mnt/db1/ferrosa'; done
grep -E '3094|3090|3078|3074|3088|3053|3081' "$CLUSTER_DIR/ferrosa/README.md"
$EDB ferrosa start --help
```

**Pass:**
- `commands` shows a `ferrosa` group with `start`, `status`, `stop` and `uninstall`.
- There are exactly three PVs, `data-ferrosa-0` to `data-ferrosa-2`. Each has `path=/mnt/db1/ferrosa`, phase `Available`, and affinity `type=db` with `easydblab.com/node-ordinal` equal to its own ordinal.
- `/mnt/db1/ferrosa` exists on all three db nodes.
- The scaffolded `README.md` shows all seven NodePorts with connection details.
- `ferrosa start --help` lists the seven start options with their defaults.

**Fail:** a missing or extra PV, a wrong path or ordinal, a missing directory, or a README without an endpoint.

### 7. Every validation error fails `start` and applies nothing

Nothing of the kit exists yet, so a gate that ran too late would leave a ConfigMap, a claim or a Deployment behind.

```bash
. "$(dirname "$EDB")/ferrosa-plan.env"
count_objects() { k get deployment,pod,service,configmap,pvc -l easydblab/kit=ferrosa -o json | jq '.items | length'; }
echo "before: $(count_objects)"
$EDB ferrosa start --image="ghcr.io/ferrosadb/ferrosa:$FERROSA_TAG" --version="$FERROSA_TAG"; echo "exit=$?"
$EDB ferrosa start --storage=bogus; echo "exit=$?"
$EDB ferrosa start --heap-sample=17; echo "exit=$?"
$EDB ferrosa start --env NOEQUALS; echo "exit=$?"
$EDB ferrosa start --env FERROSA_SEED=x:17000; echo "exit=$?"
$EDB ferrosa start --env FERROSA_HOST_ID=00000000-0000-0000-0000-000000000009; echo "exit=$?"
echo "after: $(count_objects)"
```

**Pass:** `before: 0` and `after: 0`. Every command exits non-zero, with these errors in order:
- it says `--image` and `--version` were both given and to use one of them;
- it names `bogus` and lists `local` and `s3`;
- it says `--heap-sample needs --heap-profile`;
- it names the line `NOEQUALS` and says it is not `KEY=VALUE`;
- it names `FERROSA_SEED` as a key the kit sets for each pod;
- it names `FERROSA_HOST_ID` the same way.

**Fail:** an exit of 0, a different message, or `after` above 0.

### 8. A missing tag fails `start` and names the image and the tag

```bash
. "$(dirname "$EDB")/ferrosa-plan.env"
MISSING_TAG="edl-missing-$(date +%s)"
time $EDB ferrosa start --version="$MISSING_TAG"; echo "exit=$?"
$EDB ferrosa stop
k get deployment,pod,service -l easydblab/kit=ferrosa -o json | jq '.items | length'
```

**Pass:** `start` exits non-zero within about 2 minutes. The error names a `ferrosa-<i>` pod and `ghcr.io/ferrosadb/ferrosa:<MISSING_TAG>`, with `ErrImagePull`, `ImagePullBackOff` or `InvalidImageName`. It never prints `All 3 FerrosaDB pods are ready.` After `stop` the count is 0.
**Fail:** `start` exits 0, waits for the full 600-second timeout, or prints an error that omits the image or the tag.

### 9. A readiness timeout fails `start` and names the pods

`start`'s readiness wait reads `FERROSA_READY_TIMEOUT_SECONDS` from its environment, and the shell step inherits the CLI's environment. Five seconds is shorter than one image pull, so no pod can be ready in time.

```bash
. "$(dirname "$EDB")/ferrosa-plan.env"
FERROSA_READY_TIMEOUT_SECONDS=5 $EDB ferrosa start --version="$FERROSA_TAG"; echo "exit=$?"
$EDB ferrosa stop
```

**Pass:** `start` exits non-zero with `FerrosaDB pods not ready after 5s:`, followed by the names of the not-ready pods.
**Fail:** an exit of 0, or an error with no pod name. If `start` exits 0, the five-second window did not exercise the timeout: record that, and do not mark the check as passed.

### 10. Start the pinned build and check the topology, the pod spec and readiness

```bash
. "$(dirname "$EDB")/ferrosa-plan.env"
time $EDB ferrosa start --version="$FERROSA_TAG"; echo "exit=$?"
k get pods -l easydblab/kit=ferrosa -o json > "$CLUSTER_DIR/ferrosa-pods.json"
k get nodes -o json > "$CLUSTER_DIR/ferrosa-nodes.json"
k get deployment -l easydblab/kit=ferrosa -o json | jq -r '.items[] | "\(.metadata.name) replicas=\(.spec.replicas) strategy=\(.spec.strategy.type)"'
jq -r --slurpfile n "$CLUSTER_DIR/ferrosa-nodes.json" '
  ($n[0].items | map({key: .metadata.name, value: .metadata.labels}) | from_entries) as $labels
  | .items[]
  | {pod: .metadata.name,
     ord: .metadata.labels["easydblab/ferrosa-ordinal"],
     node: .spec.nodeName,
     nodeType: $labels[.spec.nodeName].type,
     nodeOrdinal: $labels[.spec.nodeName]["easydblab.com/node-ordinal"],
     ip: .status.podIP,
     images: ([.spec.initContainers[].image, .spec.containers[].image] | unique),
     pullPolicies: ([.spec.initContainers[].imagePullPolicy, .spec.containers[].imagePullPolicy] | unique),
     pullSecrets: (.spec.imagePullSecrets // []),
     hostNetwork: (.spec.hostNetwork // false),
     hostPorts: [.spec.containers[].ports[]? | .hostPort // empty],
     grace: .spec.terminationGracePeriodSeconds,
     claim: (.spec.volumes[] | select(.name == "data") | .persistentVolumeClaim.claimName),
     ready: ([.status.containerStatuses[].ready] | all)}' "$CLUSTER_DIR/ferrosa-pods.json"
k get pvc -l easydblab/kit=ferrosa -o json | jq -r '.items[] | "\(.metadata.name) volume=\(.spec.volumeName) phase=\(.status.phase)"'
for i in 0 1 2; do
  echo "--- ferrosa-$i"
  k exec deploy/ferrosa-$i -c ferrosa -- env | grep -E '^(FERROSA_|RUST_LOG|MALLOC_CONF)' | sort
done
k get configmap cluster-config -o json | jq -r '.data.cluster_name'
k get configmap ferrosa-settings -o json | jq -r '.data | to_entries[] | "\(.key)=\(.value)"' | grep -E 'BIND|BOLT_PORT'
for ip in $(jq -r '.items[].status.podIP' "$CLUSTER_DIR/ferrosa-pods.json"); do
  sshn app0 "curl -s -o /dev/null -w '$ip readyz=%{http_code}\n' http://$ip:9090/readyz"
done
```

**Pass:**
- `start` exits 0 and prints `All 3 FerrosaDB pods are ready.` just before it returns.
- There are three Deployments, `ferrosa-0` to `ferrosa-2`, each with `replicas=1` and `strategy=Recreate`.
- Each pod `i` has `nodeType` `db` and `nodeOrdinal` equal to `ord`. The three pods are on three different db nodes. No pod is on the control node or the app node.
- Each pod has `images` `["ghcr.io/ferrosadb/ferrosa:<FERROSA_TAG>"]`, `pullPolicies` `["Always"]`, `pullSecrets` `[]`, `hostNetwork` `false`, `hostPorts` `[]`, `grace` `90`, `claim` `ferrosa-data-<ord>`, and `ready` `true`.
- Each claim `ferrosa-data-<i>` has `volume=data-ferrosa-<i>` and `phase=Bound`.
- The environment of pod `i` contains:
  - `FERROSA_HOST_ID=00000000-0000-0000-0000-00000000000<i+1>`;
  - `FERROSA_INTERNODE_BROADCAST=ferrosa-<i>.default.svc.cluster.local:17000`;
  - `FERROSA_SEED` with the two other pods' Service names on `:17000`, and not its own;
  - `FERROSA_EXPECTED_CLUSTER_SIZE=3`;
  - `FERROSA_CLUSTER_NAME` equal to the `cluster_name` value, the same on all three pods;
  - `FERROSA_CQL_BROADCAST=<pod IP>:9042` and `FERROSA_FLIGHT_BROADCAST=<pod IP>:8815`;
  - `RUST_LOG=info`.
- No pod has a `FERROSA_S3_*` variable or `MALLOC_CONF`.
- Every bind in `ferrosa-settings` uses `0.0.0.0`.
- `/readyz` answers `200` on every pod IP.

**Fail:** any other value. A pod on a non-db node, a missing seed, a seed that names the pod itself, or any `FERROSA_S3_*` variable in local mode is a kit defect.

### 11. The three pods form one ring

Each pod is asked directly, on its pod IP, which host it is and which peers it knows.

```bash
. "$(dirname "$EDB")/ferrosa-plan.env"
for ip in $(jq -r '.items[].status.podIP' "$CLUSTER_DIR/ferrosa-pods.json"); do
  echo "--- $ip"
  cq "$ip" 9042 <<'CQL'
SELECT cluster_name, host_id, data_center, rack FROM system.local;
SELECT peer, rpc_address, host_id, data_center FROM system.peers;
CQL
done
```

**Pass:** each pod reports its own pinned `host_id` in `system.local`. Its `system.peers` lists exactly the two other pinned host ids, with their pod IPs as `rpc_address`. All three report the same `cluster_name` and `data_center` equal to `$REGION`.
**Fail:** a pod with fewer than two peers, an unknown host id, or a different cluster name. Then the pods formed more than one ring.

### 12. The seven listeners answer on every db node, and `kit info` and `status` list them

```bash
. "$(dirname "$EDB")/ferrosa-plan.env"
sshn app0 bash -s -- "$DB_IPS" <<'EOS'
for ip in $(echo "$1" | tr ',' ' '); do
  echo "=== $ip"
  echo "console $(curl -s -o /dev/null -w '%{http_code}' --max-time 5 http://$ip:30909/readyz)"
  echo "graph   $(curl -s -o /dev/null -w '%{http_code}' --max-time 5 http://$ip:30747/)"
  echo "sparql  $(curl -s -o /dev/null -w '%{http_code}' --max-time 5 http://$ip:30880/)"
  for p in 30787 30532 30815; do
    if timeout 5 bash -c "</dev/tcp/$ip/$p" 2>/dev/null; then echo "tcp $p open"; else echo "tcp $p CLOSED"; fi
  done
  echo "bolt $(printf '\x60\x60\xb0\x17\x00\x00\x05\x05\x00\x00\x04\x04\x00\x00\x00\x00\x00\x00\x00\x00' | timeout 5 bash -c "exec 3<>/dev/tcp/$ip/30787; cat >&3; head -c 4 <&3 | od -An -tx1")"
  echo "postgres $(printf '\x00\x00\x00\x08\x04\xd2\x16\x2f' | timeout 5 bash -c "exec 3<>/dev/tcp/$ip/30532; cat >&3; head -c 1 <&3")"
  /usr/local/cassandra/5.0/bin/cqlsh --protocol-version=4 "$ip" 30942 -e "SELECT release_version FROM system.local" </dev/null
done
EOS
$EDB kit info ferrosa
$EDB ferrosa status
k get service -A -o json | jq -r '[.items[].spec.ports[]? | .nodePort // empty] | group_by(.) | map(select(length > 1) | .[0]) | "duplicate NodePorts: \(.)"'
k get service -l easydblab/kit=ferrosa -o json | jq -r '.items[] | select(.spec.type == "NodePort") | "\(.metadata.name) \(.spec.ports[0].nodePort) \(.spec.selector)"'

# A second start on the running kit is refused, and the pods are untouched.
UIDS_BEFORE=$(k get pods -l easydblab/kit=ferrosa -o json | jq -r '[.items[].metadata.uid] | sort | join(",")')
$EDB ferrosa start --version="$FERROSA_TAG"; echo "exit=$?"
UIDS_AFTER=$(k get pods -l easydblab/kit=ferrosa -o json | jq -r '[.items[].metadata.uid] | sort | join(",")')
[ "$UIDS_BEFORE" = "$UIDS_AFTER" ] && echo "pods unchanged" || echo "pods CHANGED"
```

**Pass:**
- On every db IP: `console 200`; `graph` and `sparql` give any HTTP status other than `000`; `tcp 30787`, `tcp 30532` and `tcp 30815` are `open`; the Bolt handshake returns 4 bytes that are not all `00`; and `cqlsh` returns one `release_version` row.
- Record the Postgres reply byte (`N` or `S` for a full SSLRequest answer). The stub may close the connection instead; the open TCP port is the pass condition.
- `kit info` lists the seven endpoints, with CQL type `cql` and Postgres type `postgresql`.
- `ferrosa status` shows `Running (3/3 pods ready)` and each endpoint at every db private IP.
- The output is `duplicate NodePorts: []`, so no other Service in the cluster (other kits, Hubble UI 31234) shares a port.
- There are seven NodePort Services, on 30942, 30909, 30787, 30747, 30880, 30532 and 30815, and each selector is `{"easydblab/ferrosa-ordinal":"0"}`.
- The second `start` exits non-zero with a collision error (`Kit.CollisionDetected`), and the output is `pods unchanged`.

**Fail:** `000`, `CLOSED`, an empty or all-zero Bolt reply, a `cqlsh` error, a duplicate NodePort, a selector other than ordinal 0, or a second `start` that runs or changes the pods.

### 13. A CQL round trip through the NodePort, with no credentials, and the replication argument

This step settles the replication argument for the stress runs and for `docs/user-guide/ferrosa.md` (task 10.6). The kit sets each pod's data center to the cluster region, and cassandra-easy-stress sends its requests to that data center (`CASSANDRA_EASY_STRESS_DEFAULT_DC`). So the first choice is `NetworkTopologyStrategy` with 3 replicas in `$REGION`, one replica on each of the three pods.

```bash
. "$(dirname "$EDB")/ferrosa-plan.env"
DB1_IP=$(echo "$DB_IPS" | cut -d, -f2)
cq "$DB1_IP" 30942 <<CQL
CREATE KEYSPACE IF NOT EXISTS edl_plan WITH replication = {'class': 'NetworkTopologyStrategy', '$REGION': 3};
CREATE TABLE IF NOT EXISTS edl_plan.kv (k text PRIMARY KEY, v text);
INSERT INTO edl_plan.kv (k, v) VALUES ('a', '1');
INSERT INTO edl_plan.kv (k, v) VALUES ('b', '2');
INSERT INTO edl_plan.kv (k, v) VALUES ('c', '3');
CONSISTENCY ALL;
SELECT * FROM edl_plan.kv;
DESCRIBE KEYSPACE edl_plan;
CQL
```

If `CREATE KEYSPACE` fails with `NetworkTopologyStrategy`, record the error in the journal and in `issues.md`. Then run the same block with `{'class': 'SimpleStrategy', 'replication_factor': 3}`. Write the strategy that worked into the plan file:

```bash
. "$(dirname "$EDB")/ferrosa-plan.env"
# Use the SimpleStrategy line instead only when NetworkTopologyStrategy failed above.
echo "REPLICATION=\"{'class': 'NetworkTopologyStrategy', '$REGION': 3}\"" >> "$CLUSTER_DIR/ferrosa-plan.env"
# echo "REPLICATION=\"{'class': 'SimpleStrategy', 'replication_factor': 3}\"" >> "$CLUSTER_DIR/ferrosa-plan.env"
. "$CLUSTER_DIR/ferrosa-plan.env"; echo "$REPLICATION"
```

**Pass:** the client connects with no username or password, through the CQL NodePort of `db1` (whose own pod is not the NodePort target, so the request crosses nodes). The `SELECT` at `CONSISTENCY ALL` returns the three rows, so all three replicas answered. `DESCRIBE` shows the chosen replication.
**Fail:** an authentication error, a `CREATE` that fails with both strategies, or a `SELECT` that fails at `ALL`.

### 14. Metrics registration, one series for each pod, and logs in Loki

Wait at least 2 minutes after step 10's `start`, so the collector has scraped each pod several times.

```bash
. "$(dirname "$EDB")/ferrosa-plan.env"
k get configmap easydblab-metrics-ferrosa-ferrosa -o json | jq -r '.metadata.name, .metadata.labels'
k get configmap otel-collector-config -o json | jq -r '.data[]' | grep -c ferrosa
mq 'count(up{job="ferrosa"})' | jq -r '.data.result[].value[1]'
mq 'up{job="ferrosa"}' | jq -r '.data.result[].metric.instance' | sort
jq -r '.items[].metadata.name' "$CLUSTER_DIR/ferrosa-pods.json" | sort
mq 'count by (instance) ({job="ferrosa", __name__=~"ferrosa_.+"})' | jq -r '.data.result[] | "\(.metric.instance) \(.value[1])"'
$EDB logs query -q '{k8s_pod_name=~"ferrosa-[0-9]+-.*"}' --since 15m --limit 20
```

**Pass:**
- The ConfigMap `easydblab-metrics-ferrosa-ferrosa` exists, with the label `easydblab.com/workload-metrics=true`.
- The collector config mentions `ferrosa` at least once.
- `count(up{job="ferrosa"})` is `3`. If every node's collector scraped every pod, it would be 9 or 15.
- The three `instance` values are exactly the three pod names.
- Each instance has more than zero `ferrosa_*` series.
- Loki returns FerrosaDB log lines (tracing output from the `ferrosa` binary) from the `ferrosa-` pods.

**Fail:** a missing ConfigMap, a count other than 3, an instance that is not a pod name, or no log lines.

### 15. A restarted pod gets a new IP and rejoins the ring

This runs before any load, because the ring loses a member while the pod restarts. A deleted pod is how an eviction or a node drain restarts it, and the replacement gets a new pod IP.

The Deployment's ReplicaSet creates the replacement as soon as the old pod starts to terminate, and the old pod drains for up to 30 seconds. Both pods mount the same local volume. The watch below records whether the two FerrosaDB containers ran at the same time.

```bash
. "$(dirname "$EDB")/ferrosa-plan.env"
OLD=$(k get pods -l easydblab/ferrosa-ordinal=1 -o json | jq -r '.items[0] | "\(.metadata.name) \(.status.podIP)"')
SVC_IP_BEFORE=$(k get service ferrosa-1 -o json | jq -r '.spec.clusterIP')
echo "old: $OLD  service: $SVC_IP_BEFORE"
k delete pod "$(echo "$OLD" | cut -d' ' -f1)" --wait=false
for n in $(seq 1 40); do
  k get pods -l easydblab/ferrosa-ordinal=1 -o json | jq -r --arg t "$(date -u +%T)" '"\($t) " + ([.items[] | "\(.metadata.name)[deleting=\(.metadata.deletionTimestamp != null) running=\(any(.status.containerStatuses[]?; .name == "ferrosa" and .state.running != null)) ready=\(any(.status.containerStatuses[]?; .ready))]"] | join(" "))'
  sleep 3
done
k rollout status deployment/ferrosa-1 --timeout=600s
k get pods -l easydblab/kit=ferrosa -o json > "$CLUSTER_DIR/ferrosa-pods.json"
NEW=$(jq -r '.items[] | select(.metadata.labels["easydblab/ferrosa-ordinal"] == "1") | "\(.metadata.name) \(.status.podIP)"' "$CLUSTER_DIR/ferrosa-pods.json")
echo "new: $NEW  service: $(k get service ferrosa-1 -o json | jq -r '.spec.clusterIP')"
jq -r '.items[] | "\(.metadata.name) ready=\([.status.containerStatuses[].ready] | all)"' "$CLUSTER_DIR/ferrosa-pods.json"
for ip in $(jq -r '.items[] | select(.metadata.labels["easydblab/ferrosa-ordinal"] != "1") | .status.podIP' "$CLUSTER_DIR/ferrosa-pods.json"); do
  echo "--- peers seen by $ip"
  cq "$ip" 9042 <<'CQL'
SELECT peer, rpc_address, host_id FROM system.peers;
CQL
done
DB0_IP=$(echo "$DB_IPS" | cut -d, -f1)
cq "$DB0_IP" 30942 <<'CQL'
CONSISTENCY ALL;
SELECT * FROM edl_plan.kv;
CQL
```

**Pass:**
- The new pod's IP is different from the old one, and the Service `ferrosa-1` keeps its ClusterIP.
- All three pods are `ready=true`. With `FERROSA_EXPECTED_CLUSTER_SIZE=3`, a pod reports ready only when the ring has three members.
- `ferrosa-0` and `ferrosa-2` list host `00000000-0000-0000-0000-000000000002` with the new IP as `rpc_address`.
- The `CONSISTENCY ALL` read returns the three rows.
- No watch line shows two `ferrosa-1` pods with `running=true` at the same time.

**Fail:** the pod does not become ready in 600 seconds, a peer keeps the old IP, or the read at `ALL` fails.

If the new IP equals the old one, repeat the delete once. If it is still the same, record that the new-IP case was not exercised.

If a watch line shows two containers running at the same time, record it as a kit defect (two FerrosaDB processes on one data directory) in `issues.md`. Tell the owner before step 16, whatever the other results are.

### 16. `--log-level` and repeated `--env` reach every container

```bash
. "$(dirname "$EDB")/ferrosa-plan.env"
$EDB ferrosa stop
$EDB ferrosa start --version="$FERROSA_TAG" --log-level=debug --env EDL_PLAN_KEY1=value1 --env EDL_PLAN_KEY2=value2 --env EDL_PLAN_DUP=first --env EDL_PLAN_DUP=second
for i in 0 1 2; do
  echo "--- ferrosa-$i"
  k exec deploy/ferrosa-$i -c ferrosa -- env | grep -E '^(RUST_LOG|EDL_PLAN_)' | sort
done
sleep 60
$EDB logs query -q '{k8s_pod_name=~"ferrosa-[0-9]+-.*"} |= "DEBUG"' --since 3m --limit 5
```

**Pass:** every container has `RUST_LOG=debug`, `EDL_PLAN_KEY1=value1`, `EDL_PLAN_KEY2=value2` and `EDL_PLAN_DUP=second`. Loki returns at least one `DEBUG` line from a `ferrosa-` pod in the last 3 minutes.
**Fail:** a missing variable, `EDL_PLAN_DUP=first`, or no `DEBUG` lines. Step 14 ran at `info`, so `DEBUG` lines can come only from this start.

### 17. An `--env` value wins over a named option

```bash
. "$(dirname "$EDB")/ferrosa-plan.env"
$EDB ferrosa stop
$EDB ferrosa start --version="$FERROSA_TAG" --log-level=debug --env RUST_LOG=warn
for i in 0 1 2; do k exec deploy/ferrosa-$i -c ferrosa -- printenv RUST_LOG; done
$EDB ferrosa stop
```

**Pass:** all three print `warn`.
**Fail:** any prints `debug`.

### 18. `--heap-profile` on a build without profiling fails `start` and names the image

```bash
. "$(dirname "$EDB")/ferrosa-plan.env"
$EDB ferrosa start --version="$FERROSA_TAG" --heap-profile; echo "exit=$?"
k exec deploy/ferrosa-0 -c ferrosa -- printenv MALLOC_CONF
k get configmap easydblab-metrics-ferrosa-ferrosa -o json; echo "exit=$?"
$EDB ferrosa stop
```

**Pass:** `start` exits non-zero with `--heap-profile needs a FerrosaDB profiling build`, naming `ghcr.io/ferrosadb/ferrosa:<FERROSA_TAG>` and a pod. `MALLOC_CONF` contains `prof:true,prof_active:true,prof_final:true` and `lg_prof_sample:19` (the default). The metrics ConfigMap is `NotFound`: a `start` that failed, even after its pods were ready, registers no metrics.
**Fail:** `start` exits 0, the error does not name the image, or the metrics ConfigMap exists.

### 19. The profiling build from ECR writes heap profiles, and the ECR stress image pulls with no secret

This step proves three things: the ECR `--image` pull with no credential step, heap profiling on a profiling build, and the custom ECR stress image. The `.heap` files appear when FerrosaDB exits (`prof_final`), so they are read after `stop`. With `--heap-sample=17`, jemalloc writes `heap_v2/131072` (2^17) as the first line of each file. The default sample (19) would write `heap_v2/524288`.

```bash
. "$(dirname "$EDB")/ferrosa-plan.env"
$EDB ferrosa start --image="$PROF_IMAGE" --heap-profile --heap-sample=17; echo "exit=$?"
k get pods -l easydblab/kit=ferrosa -o json | jq -r '.items[] | "\(.metadata.name) images=\([.spec.initContainers[].image, .spec.containers[].image] | unique) pullSecrets=\(.spec.imagePullSecrets // [])"'
for i in 0 1 2; do k exec deploy/ferrosa-$i -c ferrosa -- printenv MALLOC_CONF; done
$EDB cassandra stress start --name kv-heap --image "$STRESS_IMAGE" -- KeyValue -d 5m --rate 5k -t 4 --host ferrosa-0.default.svc.cluster.local --replication "$REPLICATION"
$EDB cassandra stress status
k get pods -l app.kubernetes.io/name=cassandra-easy-stress -o json | jq -r '.items[] | "\(.metadata.name) phase=\(.status.phase) image=\(.spec.containers[] | select(.name == "stress") | .image) pullSecrets=\(.spec.imagePullSecrets // [])"'
k get secret -A -o json | jq -r '[.items[] | select(.metadata.name == "ecr-pull-secret")] | length'
```

Wait until `cassandra stress status` shows the job complete (about 6 minutes), then:

```bash
. "$(dirname "$EDB")/ferrosa-plan.env"
$EDB cassandra stress logs kv-heap --tail 30
$EDB ferrosa stop
for h in db0 db1 db2; do
  echo "--- $h"
  sshn "$h" 'sudo ls -l /mnt/db1/ferrosa/heap-profiles/; for f in /mnt/db1/ferrosa/heap-profiles/*.heap; do sudo head -1 "$f"; done'
done
```

**Pass:**
- `start` exits 0. jemalloc accepted `prof:true`, so the build check found no `Invalid conf pair: prof`.
- Every FerrosaDB container and init container runs `$PROF_IMAGE`, with `pullSecrets=[]`.
- `MALLOC_CONF` is `prof:true,prof_active:true,prof_final:true,prof_prefix:/var/lib/ferrosa/heap-profiles/ferrosa,lg_prof_sample:17` on every pod.
- The stress pod runs `$STRESS_IMAGE` with `pullSecrets=[]`, and the cluster has `0` Secrets named `ecr-pull-secret`.
- The stress log shows traffic.
- After `stop`, each db node has at least one non-empty `ferrosa.*.heap` file, and its first line is `heap_v2/131072`.

**Fail:** `ImagePullBackOff` or `no basic auth credentials` on either image (the credential provider did not work), an `ecr-pull-secret`, no `.heap` file on a node, an empty file, or a different `heap_v2` line.

### 20. Start again after stop, and start the main load

`start` must succeed with no manual cleanup. It must also find on the volumes the rows written in step 13 and the `.heap` files from step 19.

The main run is long on purpose. It must outlast steps 21 to 23: about 30 minutes of checks plus the dashboard work, which can take up to 3 hours. Step 24 stops it. This is not the run that must complete; step 24 runs that one.

```bash
. "$(dirname "$EDB")/ferrosa-plan.env"
$EDB ferrosa start --version="$FERROSA_TAG"; echo "exit=$?"
DB2_IP=$(echo "$DB_IPS" | cut -d, -f3)
cq "$DB2_IP" 30942 <<'CQL'
CONSISTENCY ALL;
SELECT * FROM edl_plan.kv;
CQL
for h in db0 db1 db2; do sshn "$h" 'sudo ls /mnt/db1/ferrosa/heap-profiles/ | grep -c "\.heap$"'; done
$EDB cassandra stress start --name kv-main --image "$STRESS_IMAGE" -- KeyValue -d 4h --rate 5k -t 4 --host ferrosa-0.default.svc.cluster.local --replication "$REPLICATION"
$EDB cassandra stress status
```

**Pass:** `start` exits 0 with no step between `stop` and `start`. The three rows come back at `CONSISTENCY ALL`. Each db node still has at least one `.heap` file. The stress job starts and shows `Running`.
**Fail:** a collision error, a claim that does not bind, missing rows, or a missing `.heap` file.

`--rate 5k` is the global total, shared by the four threads. KeyValue keeps writing over its default 1,000,000 partitions, so the data set stays below a few GB for the whole run.

### 21. Under load: request rates, CPU profiles, and data on the data disk

Start this step at least 5 minutes after the main run started.

```bash
. "$(dirname "$EDB")/ferrosa-plan.env"
mq 'sum by (instance) (rate(ferrosa_cql_requests_total[2m]))' | jq -r '.data.result[] | "\(.metric.instance) \(.value[1])"'
sshn control0 "curl -s -H 'X-Scope-OrgID: $TENANT' 'http://localhost:4040/pyroscope/label-values?label=pod&from=now-15m'"; echo
sshn control0 "curl -s -G -H 'X-Scope-OrgID: $TENANT' http://localhost:4040/pyroscope/render --data-urlencode from=now-10m --data-urlencode until=now --data-urlencode format=json --data-urlencode query@-" <<<'process_cpu:cpu:nanoseconds:cpu:nanoseconds{service_name="default/ferrosa"}' | jq '{numTicks: .flamebearer.numTicks, names: (.flamebearer.names | length)}'
for h in db0 db1 db2; do
  echo "--- $h"
  sshn "$h" 'echo "db1=$(findmnt -n -o SOURCE /mnt/db1) ferrosa=$(findmnt -n -o SOURCE -T /mnt/db1/ferrosa) root=$(findmnt -n -o SOURCE /)"; sudo du -sh /mnt/db1/ferrosa; sudo find /mnt/db1/ferrosa -type f -size +0 | head -40'
done
for i in 0 1 2; do
  echo "--- ferrosa-$i"
  k exec deploy/ferrosa-$i -c ferrosa -- cat /proc/self/mountinfo | grep -E ' /var/lib/ferrosa | / '
done
```

**Pass:**
- Each of the three pods has a request rate above 0. If `ferrosa_cql_requests_total` does not exist, use the request counter in the catalog from step 22, and record the name.
- Pyroscope lists the three `ferrosa-` pods.
- The render has `numTicks` above 0 and more than 10 names.
- On each db node, `ferrosa` equals `db1`, both differ from `root`, and `/mnt/db1/ferrosa` holds FerrosaDB data files and the `heap-profiles` directory. Record the data-file names in the journal; step 29 uses them.
- In each pod, the `/var/lib/ferrosa` mountinfo line has the root field `/ferrosa` and, after ` - `, the same device that `findmnt` printed for `db1` on that pod's node. The `/` line is the container's `overlay`.

**Fail:** a pod with no requests, no CPU profile, data under a root-device path, or `/var/lib/ferrosa` on the container's overlay.

### 22. Export `metrics-catalog.json` (task 10.3)

The export keeps only series with a sample in the last 5 minutes, so it runs while the main load runs and every request series exists.

```bash
. "$(dirname "$EDB")/ferrosa-plan.env"
(cd "$CLUSTER_DIR" && PATH="$CLUSTER_DIR:$PATH" "$REPO_ROOT/bin/export-workload-metrics" ferrosa)
jq '{common: (.common_labels | keys), series: (.series | length), names: [.series[].name]}' "$CLUSTER_DIR/ferrosa/metrics-catalog.json"
cp "$CLUSTER_DIR/ferrosa/metrics-catalog.json" "$KIT_SRC/metrics-catalog.json"
```

The export takes every series with `job="ferrosa"`, so it also has the scrape series (`up`, `scrape_*`, maybe `target_info`). This filter prints any other name that is not a FerrosaDB metric:

```bash
. "$(dirname "$EDB")/ferrosa-plan.env"
jq -r '.series[].name | select(test("^(up|scrape_.*|target_info)$") | not) | select(startswith("ferrosa_") | not)' "$KIT_SRC/metrics-catalog.json"
```

**Pass:** the export exits 0. The catalog has more than 10 `ferrosa_*` series, and it includes the CQL request counter and the request-duration histogram (`_bucket`, `_sum`, `_count`). The filter prints nothing. The file is in the kit source directory. Do not commit it during the run.
**Fail:** an export error, an empty catalog, no CQL request series, or a name from the filter.

If the export cannot reach Mimir (Tailscale is down and `$EDB tailscale start` did not fix it), build the same catalog through `control0`:

```bash
. "$(dirname "$EDB")/ferrosa-plan.env"
mq '{job="ferrosa"}' | jq -L "$REPO_ROOT/bin" --arg ts "$(date -u +%Y-%m-%dT%H:%M:%SZ)" 'include "metrics-catalog"; {workload: "ferrosa", exported_at: $ts} + ([.data.result[].metric | {name: .__name__, labels: .}] | compact_catalog(20))' > "$KIT_SRC/metrics-catalog.json"
```

Record in `issues.md` that the fallback was used.

### 23. Write `METRICS.md` and build the dashboard (task 10.4)

Write `$KIT_SRC/METRICS.md` from the catalog: one entry for each metric, with its type, its labels and what it measures. Use the style of the other kits' `METRICS.md` files.

Then spawn the `dashboard-editor` agent with this brief:

- Build `$KIT_SRC/dashboards/ferrosa.json` from `$KIT_SRC/metrics-catalog.json`. Every metric name in the dashboard must be in the catalog.
- Name the datasources only through the `metrics_datasource` and `logs_datasource` pickers. Add no traces or profiles datasource.
- Give the dashboard the fixed top-level `uid` `ferrosa`, so each install replaces it instead of adding a copy.
- Include CQL throughput (requests per second, grouped by the request counter's labels as the catalog lists them), CQL latency (p50, p99 from the duration histogram), requests in flight, errors, and a logs panel for the FerrosaDB pods with the selector `{k8s_pod_name=~"ferrosa-[0-9]+-.*"}`. Show each pod (`instance`) as its own series where it helps.
- Deploy with `$EDB grafana install "$KIT_SRC/dashboards/ferrosa.json" --folder=ferrosa` while `kv-main` runs, and read every panel back from Grafana with data.
- Run `DashboardDatasourceVariablesTest`, `ClusterFilterTest` and `SeriesClusterTest` in a subagent.

After the agent finishes, check the metric names mechanically:

```bash
. "$(dirname "$EDB")/ferrosa-plan.env"
jq -r '.series[].name' "$KIT_SRC/metrics-catalog.json" | sort -u > "$CLUSTER_DIR/catalog-names.txt"
cat "$KIT_SRC/dashboards/ferrosa.json" "$KIT_SRC/METRICS.md" | grep -oE 'ferrosa_[a-z0-9_]+' | sort -u > "$CLUSTER_DIR/used-names.txt"
echo "names not in the catalog:"; comm -23 "$CLUSTER_DIR/used-names.txt" "$CLUSTER_DIR/catalog-names.txt"
```

**Pass:** the agent reports every panel with data from Grafana, and the three test classes pass. The list of names not in the catalog is empty.
**Fail:** a panel with no data, a failed test, or any name in the list. A name such as `ferrosa_cql_request_duration_seconds` with no suffix is a defect too, because only `_bucket`, `_sum` and `_count` exist as series.

If `kv-main` ends before this step finishes, start it again with the step 20 command and `--name kv-main-2`.

### 24. `start` installs the dashboard, and a KeyValue run completes with every panel showing data (task 10.5)

The workspace kit directory is a copy made at install time, so the new dashboard reaches `start` only through `installDist` and a fresh scaffold. Both run while FerrosaDB is stopped. `kit install --force` keeps the PVs and their data: `platform-pvs` skips a PV that exists.

```bash
. "$(dirname "$EDB")/ferrosa-plan.env"
$EDB cassandra stress stop kv-main --force
$EDB ferrosa stop
(cd "$REPO_ROOT" && ./gradlew --no-watch-fs installDist)
$EDB kit install ferrosa --force
ls "$CLUSTER_DIR/ferrosa/dashboards/"
jq -r '.uid' "$CLUSTER_DIR/ferrosa/dashboards/ferrosa.json"
# Remove the copy that step 23 installed by hand, so only start can put it back.
sshn control0 "curl -s -X DELETE http://localhost:3000/api/dashboards/uid/ferrosa"; echo
sshn control0 "curl -s 'http://localhost:3000/api/search?type=dash-db'" | jq -r '[.[] | select(.folderTitle == "ferrosa")] | "before start: \(length)"'
$EDB ferrosa start --version="$FERROSA_TAG"; echo "exit=$?"
sshn control0 "curl -s 'http://localhost:3000/api/search?type=dash-db'" | jq -r '.[] | select(.folderTitle == "ferrosa") | "\(.folderTitle)/\(.title) uid=\(.uid)"'
$EDB cassandra stress start --name kv-final --image "$STRESS_IMAGE" -- KeyValue -d 45m --rate 5k -t 4 --host ferrosa-0.default.svc.cluster.local --replication "$REPLICATION"
```

Use JDK 21 for `installDist`. If `java -version` is not 21, set `JAVA_HOME` to a JDK 21 first.

At least 10 minutes after `kv-final` starts, and before it ends, give the `dashboard-editor` agent this task: read every panel of the dashboard in folder `ferrosa` back from Grafana over the last 10 minutes, and report each panel's series count. Render the dashboard to `docs/images/`.

After the run ends (45 minutes), check its result:

```bash
. "$(dirname "$EDB")/ferrosa-plan.env"
k get job kv-final -o json | jq -r '"succeeded=\(.status.succeeded // 0) failed=\(.status.failed // 0)"'
$EDB cassandra stress logs kv-final --tail 60
```

**Pass:**
- The scaffolded dashboard has `uid` `ferrosa`. The search shows `before start: 0`. After `start` exits 0, the folder `ferrosa` holds exactly one dashboard, with `uid=ferrosa`.
- During the run, every panel has at least one series with data.
- The job reports `succeeded=1 failed=0`.
- The stress log ends with a summary and shows no exceptions. Its error counts are 0 for reads and writes.
- The throughput and latency panels show the run's 45-minute window.

**Fail:** no dashboard in `ferrosa`, a panel with no data, a failed job, or any error or exception in the stress log.

### 25. `stop` keeps the data and removes the metrics registration

```bash
. "$(dirname "$EDB")/ferrosa-plan.env"
for h in db0 db1 db2; do sshn "$h" 'sudo find /mnt/db1/ferrosa -type f | wc -l'; done
$EDB ferrosa stop
k get deployment,replicaset,pod,service,configmap -l easydblab/kit=ferrosa -o json | jq '.items | length'
k get pvc -l easydblab/kit=ferrosa -o json | jq -r '.items[] | "\(.metadata.name) \(.status.phase)"'
k get pv -o json | jq -r '.items[] | select(.metadata.name | startswith("data-ferrosa-")) | "\(.metadata.name) \(.status.phase)"'
for h in db0 db1 db2; do sshn "$h" 'sudo find /mnt/db1/ferrosa -type f | wc -l; sudo ls /mnt/db1/ferrosa/heap-profiles/'; done
k get configmap easydblab-metrics-ferrosa-ferrosa -o json; echo "exit=$?"
k get configmap otel-collector-config -o json | jq -r '.data[]' | grep -c ferrosa
# The user guide's copy-off command for .heap files works.
(cd "$CLUSTER_DIR" && for host in db0 db1 db2; do ssh -F sshConfig "$host" 'sudo tar czf - -C /mnt/db1/ferrosa heap-profiles' > "$host-heap-profiles.tgz"; done; for host in db0 db1 db2; do tar tzf "$host-heap-profiles.tgz" | grep -c '\.heap$'; done)
```

**Pass:**
- No Deployment, ReplicaSet, pod, Service or ConfigMap of the kit is left (count 0).
- The three claims stay `Bound`, and the three PVs stay `Bound`.
- Each node has at least as many files after `stop` as before it, and still has its `.heap` files.
- `easydblab-metrics-ferrosa-ferrosa` is `NotFound`, and the collector config has 0 lines with `ferrosa`. Step 14 counted at least 1 while FerrosaDB ran.
- Each tarball holds at least one `.heap` file.

**Fail:** any kit object left, a claim or PV that is gone or `Released`, fewer files, missing `.heap` files, or a registration that is still there.

### 26. The default image is `ghcr.io/ferrosadb/ferrosa:nightly`

This is the only start with no image option. It runs now because step 27 wipes the volumes next, so no other build reads what this build writes.

```bash
. "$(dirname "$EDB")/ferrosa-plan.env"
$EDB ferrosa start; echo "exit=$?"
k get pods -l easydblab/kit=ferrosa -o json | jq -r '.items[] | "\(.metadata.name) \([.spec.initContainers[].image, .spec.containers[].image] | unique)"'
docker buildx imagetools inspect ghcr.io/ferrosadb/ferrosa:nightly | head -3
$EDB ferrosa stop
```

**Pass:** `start` exits 0, and every container runs `ghcr.io/ferrosadb/ferrosa:nightly`. Record whether the `nightly` digest equals the `$FERROSA_TAG` digest.
**Fail:** any other image.

### 27. Switch to S3 storage: uninstall, then install again

Switching `--storage` on a volume that holds data is not supported, and the kit does not check for it. `uninstall` deletes the claims, the PVs and `/mnt/db1/ferrosa` on each node. A new `install` makes empty volumes, so the S3 runs never read local-mode data.

```bash
. "$(dirname "$EDB")/ferrosa-plan.env"
$EDB ferrosa uninstall
k get pvc -l easydblab/kit=ferrosa -o json | jq '.items | length'
k get pv -o json | jq '[.items[] | select(.metadata.name | startswith("data-ferrosa-"))] | length'
for h in db0 db1 db2; do sshn "$h" 'ls -d /mnt/db1/ferrosa 2>&1'; done
$EDB kit install ferrosa
for h in db0 db1 db2; do sshn "$h" 'sudo find /mnt/db1/ferrosa -mindepth 1 | wc -l'; done
```

**Pass:** after `uninstall`, there are 0 claims and 0 kit PVs, and `/mnt/db1/ferrosa` is gone on each node. After `install`, each node has an empty `/mnt/db1/ferrosa` (count 0).
**Fail:** a claim, a PV or a directory is left, or the new directory is not empty.

### 28. An S3 failure fails `start` and does not fall back, and local mode wrote nothing to S3

`--env FERROSA_S3_BUCKET` points FerrosaDB at a bucket that does not exist. `ferrosa-settings` still records the cluster's real data bucket, so this step also reads the bucket name from the tool's own output.

```bash
. "$(dirname "$EDB")/ferrosa-plan.env"
MISSING_BUCKET="edl-missing-$(date +%s)"
time $EDB ferrosa start --version="$FERROSA_TAG" --storage=s3 --env FERROSA_S3_BUCKET="$MISSING_BUCKET"; echo "exit=$?"
k get pods -l easydblab/kit=ferrosa -o json | jq -r '.items[] | "\(.metadata.name) ready=\([.status.containerStatuses[]?.ready] | all) restarts=\([.status.containerStatuses[]?.restartCount] | add)"'
DATA_BUCKET=$(k get configmap ferrosa-settings -o json | jq -r '.data.FERROSA_S3_BUCKET')
echo "DATA_BUCKET='$DATA_BUCKET'" >> "$CLUSTER_DIR/ferrosa-plan.env"
aws s3 ls "s3://$DATA_BUCKET/ferrosa/" --recursive | wc -l
$EDB ferrosa stop
```

**Pass:**
- `start` exits non-zero within 10 minutes and prints the end of a FerrosaDB log with `S3 access failed and FERROSA_S3_REQUIRED is set`.
- No pod is ready, so FerrosaDB did not fall back to local storage.
- `DATA_BUCKET` is not empty.
- The `aws s3 ls` count is `0`: nothing of the local phase, and nothing of this failed start, is under `ferrosa/` in the data bucket.

**Fail:** `start` exits 0, a pod becomes ready, the error has no S3 message, or objects exist under `ferrosa/`.

### 29. S3 mode keeps the data in the data bucket

```bash
. "$(dirname "$EDB")/ferrosa-plan.env"
$EDB ferrosa start --version="$FERROSA_TAG" --storage=s3; echo "exit=$?"
for i in 0 1 2; do echo "--- ferrosa-$i"; k exec deploy/ferrosa-$i -c ferrosa -- env | grep '^FERROSA_S3_' | sort; done
DB0_IP=$(echo "$DB_IPS" | cut -d, -f1)
cq "$DB0_IP" 30942 <<CQL
CREATE KEYSPACE IF NOT EXISTS edl_plan WITH replication = $REPLICATION;
CREATE TABLE IF NOT EXISTS edl_plan.kv (k text PRIMARY KEY, v text);
INSERT INTO edl_plan.kv (k, v) VALUES ('s3a', '1');
INSERT INTO edl_plan.kv (k, v) VALUES ('s3b', '2');
CQL
$EDB cassandra stress start --name kv-s3 --image "$STRESS_IMAGE" -- KeyValue -d 20m --rate 5k -t 4 --host ferrosa-0.default.svc.cluster.local --replication "$REPLICATION"
```

After `kv-s3` completes (about 21 minutes):

```bash
. "$(dirname "$EDB")/ferrosa-plan.env"
k get job kv-s3 -o json | jq -r '"succeeded=\(.status.succeeded // 0) failed=\(.status.failed // 0)"'
aws s3 ls "s3://$DATA_BUCKET/ferrosa/" --recursive > "$CLUSTER_DIR/s3-before-stop.txt"; wc -l < "$CLUSTER_DIR/s3-before-stop.txt"
$EDB ferrosa stop
aws s3 ls "s3://$DATA_BUCKET/ferrosa/" --recursive > "$CLUSTER_DIR/s3-after-stop.txt"; wc -l < "$CLUSTER_DIR/s3-after-stop.txt"
head -20 "$CLUSTER_DIR/s3-after-stop.txt"
awk '{print $4}' "$CLUSTER_DIR/s3-after-stop.txt" | xargs -n1 basename | sort -u > "$CLUSTER_DIR/s3-names.txt"
for h in db0 db1 db2; do sshn "$h" 'sudo find /mnt/db1/ferrosa -type f -size +0'; done > "$CLUSTER_DIR/pv-files.txt"
cat "$CLUSTER_DIR/pv-files.txt"
aws s3api get-bucket-lifecycle-configuration --bucket "$DATA_BUCKET"; echo "exit=$?"
```

From `pv-files.txt`, take the files whose names match the data-file names recorded in step 21 (the SSTable files). Check that each one's basename is in `s3-names.txt`.

**Pass:**
- Every container has `FERROSA_S3_ENDPOINT=https://s3.<REGION>.amazonaws.com`, `FERROSA_S3_REGION=<REGION>`, `FERROSA_S3_BUCKET=<DATA_BUCKET>`, `FERROSA_S3_PREFIX=ferrosa/` and `FERROSA_S3_REQUIRED=true`, and no access key.
- The job reports `succeeded=1 failed=0`.
- After `stop` (whose drain syncs the SSTables), the data bucket holds data objects under `ferrosa/`, not only the `.ferrosa/connectivity-check` probe.
- The count after `stop` is at least the count before `stop`.
- Every SSTable file on the volumes has an object with the same basename in S3. So the volume holds only the cache.
- `get-bucket-lifecycle-configuration` fails with `NoSuchLifecycleConfiguration`, or it lists no rule whose filter covers `ferrosa/`.

**Fail:** a missing `FERROSA_S3_*` variable or an access key, a failed job, no data objects in S3, fewer objects after `stop`, an SSTable on a volume with no S3 copy, or a lifecycle rule that covers `ferrosa/`.

### 30. `start` again after `stop` in S3 mode, and the S3 objects stay

```bash
. "$(dirname "$EDB")/ferrosa-plan.env"
$EDB ferrosa start --version="$FERROSA_TAG" --storage=s3; echo "exit=$?"
DB1_IP=$(echo "$DB_IPS" | cut -d, -f2)
cq "$DB1_IP" 30942 <<'CQL'
CONSISTENCY ALL;
SELECT * FROM edl_plan.kv;
CQL
aws s3 ls "s3://$DATA_BUCKET/ferrosa/" --recursive | wc -l
$EDB ferrosa status
```

**Pass:** `start` exits 0 with no cleanup step. The rows `s3a` and `s3b` come back at `CONSISTENCY ALL`. The S3 count is at least the step 29 count after `stop`. `status` shows `Running (3/3 pods ready)`.
**Fail:** a start error, missing rows, or fewer S3 objects.

### 31. Leave the cluster up

The cluster stays up for the owner. Record in the journal what is still running and where the evidence lives:

- FerrosaDB runs in S3 mode on the pinned build.
- The `.heap` tarballs are in the workspace (`db0-heap-profiles.tgz` and the others).
- The S3 objects are under `ferrosa/` in the data bucket.
- The catalog, `METRICS.md` and the dashboard are in the kit source directory, not committed.
- The images stay in the ECR repositories `ferrosa-profiling` and `cassandra-easy-stress`. Nothing in this plan deletes them.

```bash
$EDB status
$EDB ferrosa status
```

### 32. Teardown: run only when the owner says so

**Do not run this step unless the owner explicitly tells you to tear down the cluster.** Before it, confirm that the `.heap` tarballs from step 25 are in the workspace, because `down` destroys the db nodes' disks.

```bash
$EDB down --auto-approve
```

## Notes

- **Acceptance criteria and where they are checked.**
  - Image and version: default image (26), `--version` (10), ECR `--image` with no credential step (19), missing tag (8), `--image` with `--version` (7), stress ECR image with no pull secret (19, 20).
  - Topology: N Deployments on db hosts with their own PVs (10), one ring (11), restart rejoin (15), readiness before return (10), readiness timeout (9), no pods off the db nodes (10), data on the data disk (21), `init` storage rule (3), the data disk on every node (2).
  - Storage: S3 mode (29), local mode writes nothing to S3 (28), invalid `--storage` (7), S3 failure (28), no lifecycle rule (29).
  - Client access: listeners on every db node (12), `kit info` endpoints and options (5, 12), NodePort uniqueness (12), CQL round trip (13), no host networking (10), no credentials (13).
  - Runtime settings: 16, 17, 7, 18 and 19.
  - Load: 24.
  - Observability: 14, 25, 16, 21, 24 and 23.
  - Stop: 25, 29 and 30. Start after stop: 20 and 30.
  - Docs: the `.heap` copy-off command (25). Task 10.6 takes the settled command from step 13 and step 20.
- **Not checked on this cluster, and covered by the unit tier:**
  - `init` for a control instance type without instance store (`init` has no control instance-type flag).
  - `up` failing on a node with no data disk, or a failed mount (the `setup_instance.sh` shell tests).
  - `start` failing on a failed metrics registration or a rejected dashboard (`KitRunnerCommand` tests).
  - The sidecar ECR path (`cassandra start --sidecar-image`), whose code path is the same as stress.
- **The settled stress command** for `docs/user-guide/ferrosa.md` (task 10.6) is the step 20 command with the `$REPLICATION` value that step 13 chose. The run reports it. The docs, the catalog, `METRICS.md` and the dashboard are committed after the run, not during it.
- **Load totals.** `--rate 5k` is one global rate shared by `-t 4` threads. The main run is at most 4 hours at 5,000 operations a second; the final run is 45 minutes (about 13.5 million operations); the S3 run is 20 minutes. KeyValue writes over 1,000,000 partitions, so the data does not grow with the run length.
- **Elapsed time.** Expect 6 to 8 hours: about 15 minutes to provision, 20 minutes for the images, 1 hour for steps 5 to 19, up to 3 hours for steps 20 to 23, 1 hour for step 24, and 1 hour for the S3 phase.
- **Pod deletion and the shared volume.** A pod delete under a Deployment starts the replacement while the old pod drains. Step 15 records whether two FerrosaDB processes ran on one data directory. If they did, it is a kit defect for this patch, not a test artifact.
- **`FERROSA_READY_TIMEOUT_SECONDS`** is a test knob of the start script, read from the environment. Users do not set it.
- **Reporting.** Render the FerrosaDB dashboard to `docs/images/` during step 24, and the Pyroscope flame graph of the FerrosaDB pods during step 21. Report the step 24 throughput and p99 latency under Performance Results.
