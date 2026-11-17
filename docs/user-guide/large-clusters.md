# Large Clusters

`up` provisions clusters of 1,000 database nodes or more. Before it launches any instance it
checks that your AWS account can hold the new nodes, so a cluster that does not fit fails at
once, naming what is short, instead of part-way through the launch.

## Control plane datastore

The K3s server runs on embedded etcd, not the default kine/sqlite. sqlite takes one writer at a
time, and on a 79-node run its write latency (0.4-1.0 s per write) while the agents registered
coincided with the apiserver rejecting thousands of requests with 429. The single writer is the
suspected cause; a later large-cluster run measures the effect of etcd. See
[Kubernetes](kubernetes.md#datastore) for where etcd's data and snapshots live.

## Pre-launch capacity checks

The checks count only the instances `up` is about to launch: the node numbers, from 0 up to the
configured count, that no running or pending instance of that type already has. A re-run of `up`
with a larger count checks only the added nodes, and a re-run after a partial launch checks only
the nodes that are missing, in the zones they go to. When nothing is to be launched, `up` makes no capacity
call.

A node an earlier `up` launched that is still starting (pending, or without a public IP yet) is
kept, not launched again: its own pipeline waits until it runs and passes its status checks, and
records it with its public IP.

| Check | What it compares |
|-------|------------------|
| Availability zones | The configured zones (`--azs`) against the eight subnets the VPC is split into |
| Zone offerings | Each instance type against the zones its new nodes land in (node `i` lands in zone `i % zones`) |
| vCPU quota | The On-Demand standard vCPU quota (`L-1216C47A`) minus the vCPUs of the region's running and pending On-Demand standard instances, against the new nodes' vCPUs |
| Network interfaces | The network interface quota (`L-DF5E4CA3`) minus the interfaces in the region, against the interfaces the new nodes need |
| Subnet addresses | Each zone's free subnet addresses against the addresses its new nodes and their pods need |
| Launch dry run | A `RunInstances` dry run of each node type's first launch call, the exact request that call makes: its subnet and node count, and the real AMI, type, security group, instance profile and key |

Every shortfall is reported, each with what is needed, what is available and the difference,
before `up` stops. The dry run runs once the VPC's subnets and security group exist and before
the first real launch. A refused dry run is reported with AWS's own error code and message, for
example a missing permission or an AMI the region does not have.

### Which instance types the vCPU check covers

The vCPU check covers the standard families: A, C, D, H, I, M, R, T and Z, which share the
On-Demand standard quota. Each other family (for example G, P, Inf, Trn, X or U) has a quota of
its own, which `up` does not check. It says so for each such type. If that quota is too low, the
launch fails with `VcpuLimitExceeded`.

### Network interfaces and addresses per node

Under Cilium (the default CNI), pods take VPC addresses from their node's subnet through the
node's ENIs. `up` counts each node as its own address plus 16 pod addresses: Cilium keeps 8
spare addresses per node, and each node runs about 8 system and observability pods. A kit's pods
come on top. A node needs as many interfaces as those addresses fill, up to the most its type
can attach. Under Flannel each node needs one interface and one address.

With the default `/16` VPC each zone's subnet is a `/19`, about 8,000 addresses, so 1,000 nodes
over three zones fit with room to spare. When a subnet is short, spread the cluster over more
zones with `--azs` or use a larger block with `init --cidr`.

### Raising a quota

Request an increase in the AWS console under Service Quotas: the EC2 service for the vCPU quota,
and the VPC service for the network interface quota. The checks read the quota values with
`servicequotas:GetServiceQuota`, which the `EasyDBLabEC2` policy grants; run `bin/set-policies`
after an upgrade so your user has it.

## What changes at scale

- Instances are launched with one `RunInstances` call per subnet, at most 100 instances a call.
  A throttled call is retried with the same client token, so a retry cannot launch a second set.
  Node names stay `db0`, `db1`, and so on, and node `dbN` goes to subnet `N % subnets`. A
  re-run launches only the numbers that are missing: after a launch that failed part-way (for
  example `db0`, `db3` and `db6` of 9), it fills in `db1`, `db2`, `db4`, `db5`, `db7` and `db8`.
- Each instance is written to the cluster state as soon as AWS returns it, before it is named.
  If `up` fails later, even while naming the instances, `down` still finds every instance it
  launched, and a re-run does not launch them again. Each instance carries launch tags
  (`easydblab/first-ordinal`, `easydblab/ordinal-stride`) from which its name is known even if
  its `Name` tag was never set.
- The pod readiness wait fails as soon as a pod's container is in `CrashLoopBackOff`,
  `ImagePullBackOff`, `ErrImagePull` or `Error`, naming the pod and the reason, instead of
  waiting out the deadline.
- K3s uses the pod range `10.224.0.0/12` and the service range `10.240.0.0/16`, which hold a
  `/24` for each of 4,096 nodes. K3s's default `10.42.0.0/16` holds 256. `init --cidr` refuses a
  VPC block that overlaps either range, and `up` never picks one.

## One pipeline per node

Each node is brought up by its own pipeline, which starts as soon as its launch call returns.
A slow node never holds another back. A database or app node runs these steps, in order:

1. `running`: the instance runs with a public IP.
2. `status-checks`: it passes EC2's own instance and system status checks.
3. `ssh`: it answers on SSH.
4. `setup`: `setup_instance.sh`, and for the first database node the database versions file.
5. `image-check`: under Cilium, the AMI carries the Cilium node fixes.
6. `registry-tls`: the node trusts the cluster registry's certificate, once the control node has
   made it.
7. `join-wait`, then `join`: it waits for the control-plane gate and a join slot, starts its K3s
   agent, and holds the slot until its Node registers.
8. `readiness`: its Node is Ready.

The control node runs `running`, `status-checks`, `ssh`, `setup` and `image-check`, then:

1. `tunnel`: the workspace files are written and this machine connects (the SOCKS tunnel, or the
   tailnet route).
2. `registry-cert`: the registry certificate is made and installed on the control node.
3. `k3s-server`: the K3s server starts and the API server answers.
4. `cni`: the control node is labelled and, under Cilium, Cilium is installed.
5. `gate`: the control-plane gate opens.

When every pipeline has ended, `up` writes the final workspace files, applies the storage classes,
deploys the observability stack and configures AxonOps on the database nodes that are Ready.

### The control-plane gate and the join limit

No node joins until the gate opens: the API server answers `/readyz`, the control node is registered, and under
Cilium the operator is ready and every CRD its agents wait for is established. The gate waits up to 10 minutes.

At most 16 nodes are in their join step at once, from starting the agent until the Node
registers. 79 agents registering at once saturated the datastore; 16 at a time join 1,000 nodes
in about 16 minutes. SSH steps across all pipelines share a limit of 50 sessions.

### Retry and replacement

A step that fails is retried. A node whose step still fails, or whose instance ends (EC2 reports
it `shutting-down`, `terminated`, `stopping` or `stopped`), is replaced:

1. the instance is terminated, and `up` waits until EC2 reports it gone;
2. a new instance launches under the same alias, in the same subnet, after the capacity checks
   and a dry run for that one instance;
3. `state.json` is updated, and the new instance runs the pipeline from the start;
4. just before the new instance first joins, the alias's Node object and its
   `<alias>.node-password.k3s` secret are deleted, so it can join under the same name. This
   happens whatever step the old instance stopped at, because that instance, or one from an
   earlier `up`, may have registered.

An instance that ended is replaced at once, with no retry. A missing Cilium node fix is not
replaced, because a new instance from the same AMI would lack the same files. An image check
that could not run at all, for example because its SSH command died, is retried and replaced
like any other step.

Each node may be replaced twice, so one alias uses at most 3 instances. The whole cluster may
make at most `max(3, ceil(5% of the nodes))` replacements: 3 up to 60 nodes, 50 at 1,000. The
cluster limit stops a fault every node has, such as a broken AMI, from replacing every node.

The control node is never replaced. Each of its steps is tried twice; if one still fails, `up`
fails at once, stops every node pipeline and keeps every instance.

### Control-plane health

While nodes join, `up` samples the API server every 5 seconds. A sample is unhealthy when a
minimal `get nodes` takes more than 2 seconds, or the API server rejects more than 25 requests
(API priority and fairness) since the last sample. It reports:

```
Control plane unhealthy: get nodes took 3400ms (limit 2000ms), 41 rejected requests (limit 25). Joins and replacements wait until it recovers.
```

While unhealthy, no node starts a join and no replacement starts, and a join that times out is
not counted against the node. Three healthy samples in a row end the unhealthy period. If the
control plane stays unhealthy for 15 minutes, `up` fails; it does not restart K3s itself.

### Progress

Every 15 seconds `up` prints where every node is:

```
[4m30s] 1000 nodes: setup 120, join-wait 300, join 16, readiness 40, join 16/16, ready 520, replaced 2, failed 0, control plane healthy
```

The per-step counts are the nodes in that step now. `join` is the join slots in use against the
limit. Each step's start and end is published to Redis only, not printed.

### When a node fails

When a node has used its replacements, or the cluster has used its limit, the node fails and
keeps its last instance for debugging. The other nodes and the cluster-wide steps still finish.
Then `up` names each failed node, its instance, its step and the reason, and exits non-zero:

```
1 node(s) could not be brought up; their last instances are kept for debugging:
  db7 (i-0abc...): setup: setup failed (ERROR: no data disk); the node had its 2 replacements
```

Run `up` again after fixing the cause. `down` removes the kept instances with the rest of the
cluster.
