# Kits


When developing a new kit, the following is essential:

- Full integration with the observability stack.
- Use the open telemetry agant for JVM workloads
- Dashboards
- **Kits expose client ports through a NodePort Service** (range 30000-32767) on a fixed port no other kit uses — never `hostPort` or `hostNetwork`. `hostPort` depends on the CNI chaining the `portmap` plugin, and `hostNetwork` pins the pod to a host and turns a port clash into a CrashLoop. Follow postgres (30432) and clickhouse (30123). See `docs/development/kits.md`.
- **Kit scripts that run pods source the shared kit shell library** (`configuration/edl-kit-lib.sh`, found through `EDL_KIT_LIB`) instead of writing their own app-node check, pod placement, Pending wait, log follow, exit-code wait or gauge push. It runs under bash 3.2; see "Shared shell library" in `docs/development/kits.md` for the functions and the rules. `sysbench` and `clickhouse-benchmark` are the reference adopters.
