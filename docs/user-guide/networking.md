# Pod Networking (CNI)

The K3s cluster runs one pod-network datapath. You select it at init time with `--cni`. Cilium is the default. `--cni=flannel` selects Flannel.

```bash
# Default: Cilium in ENI IPAM native-routing mode (no encapsulation)
easy-db-lab init my-cluster --db 3

# K3s's built-in Flannel VXLAN overlay
easy-db-lab init my-cluster --db 3 --cni=flannel
```

A cluster whose saved state records no CNI was provisioned before Cilium existed and is treated as Flannel.

AMIs built before the Cilium node fixes lack the base image's cloud-init hotplug setting and the systemd-networkd ENI drop-ins that Cilium's secondary ENIs need. If your AMI predates them, rebuild it with `easy-db-lab build-image` before provisioning a Cilium cluster.

On a Cilium cluster, `up` checks every node for those fixes before it starts K3s, and stops with an error naming each node that lacks them and the files it is missing:

- `/etc/systemd/network/05-cilium-eni-primary.network`
- `/etc/systemd/network/06-cilium-eni-unmanaged.network`
- `/etc/cloud/cloud.cfg.d/90-easydblab-no-network-hotplug.cfg`

To recover, rebuild the images with `easy-db-lab build-image`, then run `easy-db-lab down` and `easy-db-lab up` so the nodes launch from the new AMI. Or initialize the cluster with `--cni=flannel`, which needs none of them. Without the check, such a node joins the cluster normally and drops off the network only once Cilium attaches its second ENI.

## Flannel

Flannel is the K3s built-in CNI. Pod traffic between nodes is encapsulated in VXLAN. Nothing in this page beyond the `platform cni` one-liner applies to a Flannel cluster.

## Cilium

With Cilium (the default, or `--cni=cilium`), Cilium runs in ENI IPAM native-routing mode. Each pod gets a real VPC-routable secondary IP on its node's ENIs, so the VPC routes cross-AZ pod traffic with no tunnel. K3s keeps its own kube-proxy; Cilium's kube-proxy replacement is off.

`up` is safe to re-run on a Cilium cluster. If Cilium is already installed, the hook upgrades the release in place with the same flags instead of failing on the release name.

Two K3s add-ons are not installed on a Cilium cluster: Traefik and ServiceLB. ServiceLB publishes every node IP as a LoadBalancer ingress, and Cilium's port-0 wildcard for a LoadBalancer IP rejects pod traffic to node IPs, which blocks kubelet probes. A Flannel cluster keeps both.

With Tailscale enabled, `up` installs a small nftables chain on the control node (`table ip edl_tailscale`, loaded at boot by `edl-tailscale-masquerade.service`). The control node is the tailnet subnet router, and Cilium's own NAT chain would otherwise pass tailnet packets to the db nodes without masquerading them, so the db nodes' private IPs would time out from your machine while the control node worked. The chain masquerades Tailscale-forwarded traffic before Cilium sees it. It is installed only when Tailscale is enabled; re-running `up` replaces it in place.

`hostPort` works on a Cilium cluster through portmap chaining. With kube-proxy replacement off, Cilium does not implement `hostPort` itself, so it is installed with `cni.chainingMode=portmap`: the `portmap` CNI plugin runs after `cilium-cni` for every pod and publishes its host ports on the node. The plugin binary is the one K3s bundles; each node links it into `/opt/cni/bin` when K3s starts. Kits that publish a port through `hostPort` (Trino, Presto, Flink) are reachable at their node's private IP and scraped, as on Flannel. A pod started before the chaining was applied keeps its old networking until it is restarted.

Cilium's devices are pinned to the ENA interfaces (`ens+`). The tailnet interface `tailscale0` is not a Cilium device, so its 1280 MTU does not lower the NIC MTU below the 9001 AWS offers.

### Reading the datapath back

`platform cni` reads the live configuration and the per-node ENI state off the cluster. It is read-only.

```bash
easy-db-lab platform cni
```

On a Cilium cluster it prints the routing mode, IPAM mode, kube-proxy replacement, masquerade interfaces, native routing CIDR, and the Hubble UI URL. Then it prints one block per node: ENI count, subnet CIDRs, and IPs allocated, used, and available. The values come from the `cilium-config` ConfigMap and the `CiliumNode` objects, so they show what the cluster runs, not what init requested.

On a Flannel cluster it prints one line that names Flannel and exits 0.

### Hubble UI

Hubble UI is exposed as a NodePort on port 31234. Open it on any node's private IP over the tailnet or the SOCKS tunnel; no port-forward is needed:

```
http://<control node private IP>:31234
```

`platform cni` prints the exact URL.

### Metrics

On a Cilium cluster the OTel collector scrapes three Cilium jobs:

| Job | Target | Source |
|-----|--------|--------|
| `cilium-agent` | `localhost:9962` on every node | The Cilium agent (hostNetwork DaemonSet) |
| `hubble` | `localhost:9965` on every node | Hubble metrics (`dns`, `drop`, `tcp`, `flow`, `port-distribution`, `icmp`, `http`) |
| `cilium-operator` | Port 9963 on the one node that runs the operator | Found by pod discovery in `kube-system` on the `io.cilium/app=operator` label |

The metrics land in Mimir with the `cluster` label like every other scrape. A Flannel cluster renders none of these jobs.

### Logs

The Cilium agent, operator, and Hubble pods run in `kube-system`. Their stdout and stderr are collected by the same container log pipeline as every other pod, so they are in Loki under the stream label `k8s_namespace_name="kube-system"`. Nothing needs to be enabled.

### Install markers on the timeline

`up` marks the Cilium install on the Grafana timeline with two annotations tagged `cilium`: `Cilium install started` and `Cilium install finished` (or `Cilium install failed: <error>`). Cilium installs before Grafana exists, so the timestamps are taken when the install runs and the annotations are posted once the observability stack is up. A telemetry-redirect cluster has no local Grafana and posts nothing. See [Annotations](monitoring.md#annotations).

## kube-state-metrics

Every cluster, on either CNI, runs kube-state-metrics on the control node. It turns the state of Kubernetes objects (pods, deployments, nodes, PVCs, jobs) into Prometheus metrics such as `kube_pod_status_phase` and `kube_node_status_condition`. The OTel collector scrapes it once, through pod discovery on the control node, and the metrics carry the `cluster` label. See [Monitoring](monitoring.md#kube-state-metrics).
