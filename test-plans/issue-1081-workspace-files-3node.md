# Lab Plan: Workspace files resolve against the workspace (#1081, closes #1033)

## Objective

Prove on a real 3-node cluster that every workspace file the CLI reads or writes lives in the
workspace, never in some other directory. Cover these commands: `up`, `cassandra start`,
`update-config` and `download-config`, `upload-keys`, `setup-instances`, the profiling fetch,
`kubectl`, and kit status. A command run outside the workspace must not touch this cluster or create
files where it ran. `down` must exit 0 and leave nothing behind in AWS.

The CLI selects its workspace only from the process's current directory; there is no
`--workspace` option. Step 9 runs the CLI from a directory that is not the workspace. There it must
fail with the absolute path of the `state.json` it looked for, and create nothing.

## Cluster Name

edbl-1081

## Datacenters

single

## Environment

Single DC, `us-west-2`. 1 control node, 3 db nodes, 0 app nodes. Built from this worktree with
`./gradlew installDist`, run as `bin/easy-db-lab`. Default easy-db-lab profile. Tag
`embark_offhours=off`.

## Steps

### 1. Provision

```bash
$EDB init edbl-1081 --db.count 3 --tag embark_offhours=off --up
```

Pass: `up` completes with no error. The workspace holds `state.json`, `kubeconfig`, `sshConfig`,
`env.sh`, `environment.sh`, `setup_instance.sh` and `cassandra-jmx-rules.yaml`.

### 2. Node setup files came from the workspace

```bash
$EDB exec run -t cassandra -- "ls -l /etc/profile.d/stress.sh /etc/easy-db-lab/cassandra-jmx-rules.yaml"
```

Pass: both files exist on db0-db2. Their contents match the workspace copies. Use
`CLUSTER_DIR=$(dirname "$EDB")` and compare `$CLUSTER_DIR/environment.sh` with the output of
`$EDB exec run --hosts db0 -- cat /etc/profile.d/stress.sh`.

### 3. Cassandra start and AxonOps file

```bash
$EDB cassandra use 5.0
$EDB cassandra start
```

Pass: start completes. If the workspace holds `axonops-workbench.json`, start reports it.

### 4. download-config and update-config

```bash
$EDB cassandra download-config
$EDB cassandra update-config
```

Pass: `download-config` writes `<workspace>/<version>/` (for example `5.0/`). `update-config`
uploads it and finishes with no error.

### 5. upload-keys from the workspace default

Make `authorized_keys/` in the workspace with two `.pub` files (throwaway keys from
`ssh-keygen -t ed25519 -N "" -f`). Run:

```bash
$EDB upload-keys
```

Pass:
- Both keys are in `~/.ssh/authorized_keys_extra` on db0 and in `~/.ssh/authorized_keys`.
- No `authorized_keys_extra` file is in the workspace.

### 6. setup-instances

```bash
$EDB setup-instances
```

Pass: completes on every node with no error.

### 7. Profiling fetch

```bash
$EDB cassandra profile start -- -e cpu
# wait about 45s for chunks to rotate
$EDB cassandra profile fetch --last 1
$EDB cassandra profile stop
```

Pass: chunks land in `<workspace>/profiles/db0/` and so on.

### 8. kubectl and kit status

```bash
CLUSTER_DIR=$(dirname "$EDB")
KUBECONFIG=$CLUSTER_DIR/kubeconfig $CLUSTER_DIR/bin/kubectl get nodes
$EDB kit install memcached
$EDB memcached start
$EDB memcached status
$EDB memcached stop
```

Pass: `kubectl` lists 4 Ready nodes, and `memcached status` reports running pods.

### 9. Run from a different current directory

This step is the one deliberate exception to "always use `$EDB`": the wrapper `cd`s into the
workspace, which is the very thing this step must avoid. Make an empty directory
`OTHER=$(mktemp -d)`. Run the binary the wrapper calls (read its path from `$EDB`) as
`(cd "$OTHER" && <that binary> cassandra download-config)`.  Do not use `status` here: it reports "Cluster state does not exist yet" and exits 0 by design.

Pass:
- It exits 1 with "No cluster state at $OTHER/state.json ... or run 'init' there first".
- `ls -A "$OTHER"` is empty afterwards.
- `$EDB status` (in the workspace) still succeeds.

### 10. Spark logs path (no EMR)

The binding is checked in `AwsModulesResolutionTest` (`EMR logs are saved under the workspace`).
No EMR cluster is started.

### 11. Teardown

```bash
$EDB down --auto-approve
```

Pass:
- Exits 0.
- No instance, VPC or security group tagged with this cluster remains (check with `aws ec2
  describe-instances` and `describe-vpcs`, filtered on the cluster tag, using the easy-db-lab
  profile).
