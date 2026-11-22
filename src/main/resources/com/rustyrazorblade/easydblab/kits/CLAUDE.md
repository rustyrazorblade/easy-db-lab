# Kits


When developing a new kit, the following is essential:

- Full integration with the observability stack.
- Use the open telemetry agant for JVM workloads
- Dashboards
- **Kits expose client ports through a NodePort Service** (range 30000-32767) on a fixed port no other kit uses — never `hostPort` or `hostNetwork`. `hostPort` depends on the CNI chaining the `portmap` plugin, and `hostNetwork` pins the pod to a host and turns a port clash into a CrashLoop. Follow postgres (30432) and clickhouse (30123). See `docs/development/kits.md`.
- **Kits never set the pod network sysctls themselves.** `up`'s admission policy (`easydblab-pod-network-sysctls`) adds the tuned `net.*` values to every non-hostNetwork pod; a kit adds no `sysctls` block for those keys, and a value a kit pod does set is kept. See "Pod network tuning" in `docs/development/kits.md`.
- **Kit scripts that run pods source the shared kit shell library** (`configuration/edl-kit-lib.sh`, found through `EDL_KIT_LIB`) instead of writing their own app-node check, pod placement, Pending wait, log follow, exit-code wait or gauge push. It runs under bash 3.2; see "Shared shell library" in `docs/development/kits.md` for the functions and the rules. `sysbench` and `clickhouse-benchmark` are the reference adopters.
- **Kits act in the cluster namespace, never a hard-coded one.** Every kit gets it as `NAMESPACE`: `${NAMESPACE}` in `kit.yaml`, `__NAMESPACE__` in a `.template` file and `$NAMESPACE` in a script. A step or `runtime` block that names no namespace acts in it. Put `-n "${NAMESPACE}"` after the kubectl verb and its objects (`kubectl apply -f - -n "${NAMESPACE}"`), so the shell test stubs can dispatch on the verb. In-cluster addresses are `<svc>.${NAMESPACE}.svc`. An operator gets its own namespace from a `namespace` step, which labels it with the cluster's ownership labels, and never `kube-system`. `NoDefaultNamespaceLiteralTest` fails on a `default` namespace literal anywhere under `src/main/resources`.
