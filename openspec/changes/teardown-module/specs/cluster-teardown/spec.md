## ADDED Requirements

### Requirement: down changes only the confirmed plan

`down` MUST discover the resources to remove once, before the preview and the confirmation prompt.  After the operator confirms (or passes `--auto-approve`), `down` MUST remove or change only resources in that plan.  While it removes a planned resource, it MAY read the state of that resource and of the network that holds it.  It MUST NOT remove, detach or disassociate anything the plan does not list.  A route MUST be removed only when the plan lists it as a route to a planned gateway, and the preview MUST show how many such routes it will remove.

A resource that appears after the preview MUST NOT be removed by that `down`.  When such a resource blocks the removal of a planned resource (a subnet, a security group, the gateway or the network itself), `down` MUST report the failure and exit non-zero.  Unless the workspace's own network was removed, it MUST also leave the cluster state as it was, so that a later `down` previews the new resource before removing it.

Before it discovers anything, `down` MUST fail, exit non-zero and change nothing when the region recorded in the cluster state differs from the region the tool is configured to use.

A preview that lists no network MUST NOT prompt and MUST NOT remove anything.  A preview that lists at least one network MUST be confirmed before anything is removed, even when every resource group in it is empty.

`init --clean` and `down` MUST use the same rule to resolve which resources belong to the workspace's cluster: the whole network when easy-db-lab created it, and only the cluster's own tagged resources when the network was supplied with `init --vpc`.  `init --clean` MUST NOT emit any teardown event.

#### Scenario: A resource created during the tail save in a supplied network is not removed

- **GIVEN** a running cluster in a network supplied with `init --vpc`, whose teardown the operator confirmed
- **WHEN** a new resource tagged with the cluster's `ClusterId` is created in the network while the tail save runs
- **THEN** `down` removes only the resources the preview listed and succeeds
- **AND** the new resource is still there after `down` ends

#### Scenario: A late resource that blocks the network delete fails down

- **GIVEN** a confirmed teardown of a network easy-db-lab created, and a subnet created in it after the preview
- **WHEN** `down` removes the planned resources
- **THEN** the network delete fails, `down` reports the error and exits non-zero
- **AND** the cluster state is not marked down
- **AND** the next `down` lists the new subnet in its preview

#### Scenario: A late subnet keeps its route table association

- **GIVEN** a confirmed teardown, and a subnet created after the preview and associated with a planned route table
- **WHEN** `down` removes the route table
- **THEN** it disassociates only the associations the plan listed, and the late subnet's association is left as it is

#### Scenario: --all removes only the networks it previewed

- **GIVEN** `down --all` whose preview listed two networks
- **WHEN** a third tagged network is created before the operator confirms
- **THEN** only the two previewed networks are torn down

#### Scenario: A late gateway route in a kept network survives

- **GIVEN** a confirmed teardown of a cluster in a network supplied with `init --vpc`
- **WHEN** a route to the planned gateway is added after the preview
- **THEN** `down` removes only the routes the preview counted, and the new route is still there

#### Scenario: A region mismatch changes nothing

- **GIVEN** a cluster state that records one region, and a profile configured for another
- **WHEN** the operator runs `down`
- **THEN** `down` reports both regions and exits non-zero
- **AND** nothing is removed and the cluster state is unchanged

#### Scenario: A network with nothing left in it still asks first

- **GIVEN** a preview that lists one network whose resource groups are all empty
- **WHEN** `down` runs without `--auto-approve`
- **THEN** it shows the network and prompts before it removes the network

#### Scenario: init --clean and down agree on the cluster's resources

- **GIVEN** a workspace whose cluster runs in a network supplied with `init --vpc`
- **WHEN** the operator runs `down --dry-run`, and separately `init --clean`
- **THEN** both look only at the resources tagged with the cluster's `ClusterId`
- **AND** `init --clean` prints no teardown message

### Requirement: Teardown of the workspace's own cluster

`down` MUST treat a teardown as including the workspace's own cluster in three cases: the current-cluster teardown, a `down <vpc-id>` that names the workspace's network, and a `down --all` whose preview lists the workspace's network.  It MUST NOT treat `down --packer` as including it.

When a confirmed teardown includes the workspace's own cluster and its preview lists a network, `down` MUST run the pre-teardown save before any infrastructure is removed, under the rules of "Cluster Teardown" (`--force` and a telemetry redirect skip it).  After the removals, `down` MUST stop the SOCKS tunnel whatever the result.  The preview MUST state that the workspace will be reset.  When the workspace's own network was removed, or was confirmed as gone, it MUST then:
- remove the control node's Tailscale device;
- delete the Tailscale auth key;
- stop the data bucket's request metrics;
- mark the cluster state down.

`down` MUST do this even when another network in the same `down --all` failed; it still exits non-zero.  A network supplied with `init --vpc` or not created by easy-db-lab is never removed, so for it these steps MUST run only when its removal reported no error.  When the removal of the workspace's own network fails, or is not attempted because an earlier network failed, `down` MUST NOT change the cluster state.  These steps MUST run before the empty data buckets are deleted.

When a teardown does not include the workspace's own cluster, finds nothing recorded to tear down, or is declined at the prompt, `down` MUST NOT do any of these:
- run the save;
- stop the SOCKS tunnel;
- remove the Tailscale device or delete the auth key;
- stop the bucket metrics;
- change the cluster state.

When AWS reports that the workspace's own recorded network does not exist (the error code `InvalidVpcID.NotFound`), `down` MUST list that network in the preview as gone and MUST ask for confirmation as for any other network.  A dry run MUST show the same preview.  After confirmation, `down` MUST remove nothing in that network and MUST run the steps above.  Any other lookup failure, and a not-found network that is not the workspace's, MUST fail `down` with a non-zero exit and change nothing.

#### Scenario: down of another VPC leaves the workspace alone

- **GIVEN** a workspace with a running cluster and a recorded Tailscale device
- **WHEN** the operator runs `down <vpc-id>` for a different VPC and confirms
- **THEN** that VPC's resources are removed
- **AND** the workspace's cluster state, Tailscale device and SOCKS tunnel are left as they were

#### Scenario: down of the workspace's VPC by id saves the tail

- **GIVEN** a running cluster
- **WHEN** the operator runs `down <vpc-id>` naming the cluster's own network and confirms
- **THEN** the pre-teardown save runs before any infrastructure is removed
- **AND** after a successful removal the cluster state is marked down

#### Scenario: down --all that includes the workspace's network saves the tail and resets the workspace

- **GIVEN** a running cluster in this workspace, and other easy-db-lab networks in the account
- **WHEN** the operator runs `down --all` and confirms
- **THEN** the pre-teardown save runs before any network is removed
- **AND** after the workspace's network is removed, the cluster state is marked down and its Tailscale device is removed

#### Scenario: down --all where another network fails still resets the workspace

- **GIVEN** `down --all` whose preview lists the workspace's network and one other network
- **WHEN** the workspace's network is removed and the other network's removal fails
- **THEN** the cluster state is marked down
- **AND** `down` reports the other network's error and exits non-zero

#### Scenario: A recorded network that no longer exists is confirmed, then reset

- **GIVEN** a cluster state that records a VPC id AWS no longer has
- **WHEN** the operator runs `down`
- **THEN** the preview lists the network as gone and states that the workspace will be reset
- **AND** only after the operator confirms is the cluster state marked down, and nothing is removed

#### Scenario: A supplied network whose removal fails keeps the state

- **GIVEN** a running cluster in a network supplied with `init --vpc`
- **WHEN** the removal of one of the cluster's resources fails
- **THEN** `down` exits non-zero, and the cluster state is still up with its VPC id

#### Scenario: A failure before the workspace's network keeps the state

- **GIVEN** `down --all` whose preview lists another network before the workspace's network
- **WHEN** the removal of the other network stops with an error before the workspace's network is reached
- **THEN** the cluster state is unchanged and `down` exits non-zero

#### Scenario: A mistyped VPC id fails

- **GIVEN** a workspace with a running cluster
- **WHEN** the operator runs `down <vpc-id>` with an id AWS does not have
- **THEN** `down` exits non-zero and changes nothing

#### Scenario: A declined prompt keeps the tunnel

- **GIVEN** a running cluster whose SOCKS tunnel is up
- **WHEN** the operator declines the `down` prompt
- **THEN** the SOCKS tunnel is still running and the cluster state is unchanged
- **AND** `down` exits non-zero

#### Scenario: Nothing to tear down keeps the tunnel

- **GIVEN** a workspace with a SOCKS tunnel up and a cluster state that records neither a VPC nor a Tailscale device
- **WHEN** the operator runs `down`
- **THEN** it reports that there is nothing to tear down and exits non-zero
- **AND** the SOCKS tunnel is still running
