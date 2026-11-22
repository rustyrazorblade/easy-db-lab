# Lab Plan: cassandra-pods from tarballs, rolling upgrade on 3 nodes, and arm64 (issue 1099)

## Objective

Prove on real clusters what the unit and K3s tiers cannot: that the `cassandra-pods` kit runs
Cassandra from an Apache tarball on the `cassandra-runtime` image, that `upgrade` rolls three
pods one at a time while a QUORUM workload with RF=3 sees no unavailable errors, and that the same
start works on Graviton (arm64) db nodes. The Cassandra dashboards must show the pods.

The run passes when every result below is a pass.

## Cluster Name

cassandra-pods-tarball

## Datacenters

single

## Environment

- Run A: 3 db nodes `i4i.xlarge` (amd64), 1 app node `c6id.2xlarge`, the default control node.
- Run B: 3 db nodes `i8g.xlarge` (arm64, Graviton), the same app and control nodes. Run B is a second
  workspace that `/easy-db-lab:run` creates; it needs only steps 1-4 and 10.
- Region `us-west-2`. Tenant `default`. CNI default (Cilium).
- The `cassandra-runtime` images `ghcr.io/rustyrazorblade/cassandra-runtime:1-jre11` and `1-jre17` must
  be published for both platforms before the run (the `publish-cassandra-runtime` job). Step 2 checks it.
- The laptop needs `kubectl`, `jq` and `docker` (`buildx`, for the manifest check).

## Steps

Every step after step 1 starts with `KUBECTL="$(dirname "$EDB")/bin/kubectl"`: the workspace's
kubectl wrapper, which reaches the cluster through the SOCKS tunnel.

1. Provision.

   ```bash
   $EDB init cassandra-pods-tarball --db.count 3 --db.instance-type i4i.xlarge --app.count 1 --app.instance-type c6id.2xlarge --up
   ```

   Pass: `up` exits 0.

2. Check both runtime images have amd64 and arm64.

   ```bash
   for tag in 1-jre11 1-jre17; do
     docker buildx imagetools inspect --raw "ghcr.io/rustyrazorblade/cassandra-runtime:${tag}" |
       jq -r '[.manifests[].platform | "\(.os)/\(.architecture)"] | sort | join(",")'
   done
   ```

   Pass: both lines contain `linux/amd64` and `linux/arm64`.

3. Install and start 5.0.5 from its tarball.

   ```bash
   $EDB kit install cassandra-pods --version 5.0.5
   $EDB cassandra-pods start
   "$KUBECTL" get pods -l easydblab/kit=cassandra-pods -o jsonpath='{range .items[*]}{.metadata.name} {.status.qosClass} {.spec.containers[0].image}{"\n"}{end}'
   ```

   Pass: `start` exits 0 after "3 nodes are UN"; every pod is `Guaranteed` on
   `ghcr.io/rustyrazorblade/cassandra-runtime:1-jre17`.

4. Check config parity with the AMI path.

   ```bash
   "$KUBECTL" exec cassandra-pods-0 -c cassandra -- sh -c 'cat /edl/conf/cassandra.yaml' > pod-cassandra.yaml
   grep -E '^(num_tokens|concurrent_reads|trickle_fsync|endpoint_snitch|native_transport_port):' pod-cassandra.yaml
   ```

   Pass: `num_tokens: 4`, `trickle_fsync: true`, the workspace patch's `concurrent_reads`,
   `endpoint_snitch: GossipingPropertyFileSnitch` and `native_transport_port: 30942`.

5. Create an RF=3 keyspace and start a QUORUM workload on the app node.

   ```bash
   $EDB cassandra stress start --name quorum -- KeyValue -d 30m --rate 5k -t 4 --cl QUORUM \
     --host cassandra-pods-0.cassandra-pods-gossip.default.svc.cluster.local \
     --replication "{'class': 'NetworkTopologyStrategy', 'dc1': 3}"
   $EDB cassandra stress status
   ```

   Pass: the job runs and its report shows writes and reads with 0 errors after 2 minutes.

6. Roll to 5.0.6 under load.

   ```bash
   "$KUBECTL" get pods -l easydblab/kit=cassandra-pods -w > rollout-watch.log &
   watch_pid=$!
   $EDB cassandra-pods upgrade --version 5.0.6
   kill "${watch_pid}"
   ```

   Pass: `upgrade` exits 0 after "3 nodes are UN"; `rollout-watch.log` shows the pods restarting in
   the order 2, 1, 0, never two of them not Ready at once.

7. Check the workload saw no unavailable errors.

   Pass: the stress report's error count stays 0 across the whole upgrade window, and its log holds no
   `UnavailableException`.

8. Check the new build is what runs, and that it sticks.

   ```bash
   "$KUBECTL" exec cassandra-pods-0 -c cassandra -- sh -c '"$EDL_CASSANDRA_HOME/bin/nodetool" version'
   $EDB cassandra-pods stop && $EDB cassandra-pods start
   "$KUBECTL" exec cassandra-pods-0 -c cassandra -- sh -c '"$EDL_CASSANDRA_HOME/bin/nodetool" version'
   ```

   Pass: both print `ReleaseVersion: 5.0.6`. The second start makes no tarball download: the
   `cassandra-fetch` log of each pod says it is cached.

9. Check a major downgrade is refused and changes nothing.

   ```bash
   before=$("$KUBECTL" get statefulset cassandra-pods -o jsonpath='{.metadata.generation}')
   $EDB cassandra-pods upgrade --version 4.1; echo "exit=$?"
   after=$("$KUBECTL" get statefulset cassandra-pods -o jsonpath='{.metadata.generation}')
   ```

   Pass: a non-zero exit naming `5.0.6` and `4.1`; `before` equals `after`.

10. Check the dashboards.

    Pass: in Grafana, the Cassandra overview dashboard shows the three pods' nodes under `cassandra_build`
    `5.0.6` (and `5.0.5` before step 6), with the read and write latency panels populated.

11. Run B: repeat steps 1-4 and 10 with `--db.instance-type i8g.xlarge` in a second workspace.

    Pass: as in run A, on arm64 nodes.

12. Stop the workload and tear down (each workspace).

    ```bash
    $EDB cassandra stress stop --all --force
    $EDB down --auto-approve
    ```
