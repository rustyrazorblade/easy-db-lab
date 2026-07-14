# Apache Ignite 3 — SQL validation + readiness diagnosis (live lab)

Live end-to-end validation of the `ignite3` kit (PR #684). The prior attempt stalled at
`Running (0/1 pods ready)` with **no logs captured**, so this plan's readiness step is
**diagnostic-first**: on any stall it captures `kubectl describe pod` + `kubectl logs`
(current AND `--previous`) so we fix the crashloop from real signal instead of guessing.

## Cluster Name

ignite3-sql-validation

## Datacenters

single

## Success Criteria

- Ignite pods reach `1/1 Ready` (readiness probe on client-connector :10800 passes).
- `ignite3 status` reports the server nodes running.
- `ignite3 sql` DDL + DML + query round-trip: the `SELECT` returns exactly the rows inserted
  (THE pass/fail gate — proves the `sql` capability + JDBC endpoint + thin driver).
- Ignite native OTLP metrics reach VictoriaMetrics / Grafana.

## Notes

- Run everything from the **#684 worktree** (`/Users/jhaddad/dev/easy-db-lab-668-add-ignite`)
  so the wrapper uses the ignite-enabled binary — `main`'s binary has no `ignite3` kit.
- AWS profile: `sandbox-admin`. Instance type i4i.xlarge. Storage profile: `aipersist`.
- **Do NOT leave the cluster running.** Tear down on success. On a failure we're actively
  debugging, keep it up ONLY while iterating, and tear down before ending the session.

## Steps

1. **Build the ignite-enabled binary + scaffold workspace** (from the #684 worktree)
   ```bash
   ./gradlew installDist            # bundles the ignite3 kit + readiness-probe fix into build/install
   CLUSTER_DIR="clusters/ignite3-sql-$(date +%Y%m%d-%H%M%S)"
   mkdir -p "$CLUSTER_DIR"
   bin/create-easy-db-lab-wrapper "$CLUSTER_DIR"
   EDB="$CLUSTER_DIR/easy-db-lab"
   ```

2. **Init + up** a single-DC cluster with 3 db nodes (i4i.xlarge)
   ```bash
   $EDB init --name ignite3-sql-validation --instance-type i4i.xlarge <3 db nodes per `$EDB commands`>
   $EDB up
   ```
   Verify: `$EDB status` shows 3 db nodes reachable.

3. **Install + start Ignite 3**
   ```bash
   $EDB kit install ignite3 --replicas 3 --storage aipersist
   $EDB ignite3 start
   ```

4. **Readiness gate — DIAGNOSTIC ON STALL.** While/after start, watch pod readiness:
   ```bash
   kubectl get pods -l app=ignite -n default -w    # expect ignite-cluster-0..2 → 1/1 Ready
   ```
   **If any pod is not `1/1 Ready` within ~2 min, immediately capture (do NOT tear down):**
   ```bash
   kubectl describe pod -l app=ignite -n default
   kubectl logs -l app=ignite -n default --all-containers --tail=200
   kubectl logs -l app=ignite -n default --all-containers --previous --tail=200
   kubectl get events -n default --sort-by=.lastTimestamp | tail -30
   ```
   Save output to `$CLUSTER_DIR/docs/`, then STOP and hand the logs back for a manifest fix.
   Iterate: apply fix in the kit → `./gradlew installDist` → `$EDB kit install ignite3 ...`
   → `$EDB ignite3 stop && $EDB ignite3 start` → re-check readiness.

5. **SQL round-trip (the core gate)** — only once pods are `1/1 Ready` and cluster-init completed
   ```bash
   $EDB ignite3 status
   $EDB ignite3 sql "CREATE TABLE lab_kv (id INT PRIMARY KEY, val VARCHAR)"
   $EDB ignite3 sql "INSERT INTO lab_kv (id, val) VALUES (1, 'hello'), (2, 'world')"
   $EDB ignite3 sql "SELECT id, val FROM lab_kv ORDER BY id"
   ```
   Verify: the SELECT returns exactly `(1, hello)` and `(2, world)`.

6. **Metrics check**
   - Confirm Ignite native OTLP metrics land in VictoriaMetrics (query `{job="ignite3"}` or via
     Grafana). From the cluster workspace: `export-workload-metrics ignite3` for the catalog.
   - This feeds the dashboard authoring step in the kit's TODO.md.

7. **Tear down** (mandatory unless mid-debug)
   ```bash
   $EDB down --auto-approve
   ```
   Confirm no lingering EC2 instances for this cluster.

## On failure

Keep the cluster up ONLY while actively iterating on a fix. The moment we stop for the session,
run `$EDB down --auto-approve` — never leave instances orphaned.
