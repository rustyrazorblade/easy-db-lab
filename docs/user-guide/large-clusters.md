# Large Clusters

`up` provisions clusters of 1,000 database nodes or more. Before it launches any instance it
checks that your AWS account can hold the new nodes, so a cluster that does not fit fails at
once, naming what is short, instead of part-way through the launch.

## Pre-launch capacity checks

The checks count only the instances `up` is about to launch: the node numbers, from 0 up to the
configured count, that no running or pending instance of that type already has. A re-run of `up`
with a larger count checks only the added nodes, and a re-run after a partial launch checks only
the nodes that are missing, in the zones they go to. When nothing is to be launched, `up` makes no capacity
call.

A node an earlier `up` launched that is still starting (pending, or without a public IP yet) is
kept, not launched again: `up` waits until it runs and passes its status checks, and records it
with its public IP.

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
- Per-node work (setup, the SSH readiness wait, K3s agents, registry TLS) runs on at most 50
  nodes at once.
- After K3s starts, `up` waits until every node is registered. The wait grows with the node
  count, and a node that never registers is named.
- K3s uses the pod range `10.224.0.0/12` and the service range `10.240.0.0/16`, which hold a
  `/24` for each of 4,096 nodes. K3s's default `10.42.0.0/16` holds 256. `init --cidr` refuses a
  VPC block that overlaps either range, and `up` never picks one.
