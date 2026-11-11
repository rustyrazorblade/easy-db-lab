## ADDED Requirements

### Requirement: Packaged kit shell library in the workspace bin directory
The CLI SHALL package one bash library, `edl-kit-lib.sh`, as the classpath resource `/com/rustyrazorblade/easydblab/configuration/edl-kit-lib.sh` (`Constants.ToolWrappers.KIT_LIBRARY_RESOURCE`), outside `kits/`, so the built-in kit scan never lists it as a kit.  `ToolWrapperInstaller` SHALL write it to `<workspace>/bin/edl-kit-lib.sh` (`Constants.ToolWrappers.KIT_LIBRARY`) when the file is absent, and SHALL NOT rewrite it when it is present, whatever its content.  The write SHALL be atomic.  The installer SHALL include the library in its foreign-file check, so a `bin/` with no marker that holds `edl-kit-lib.sh` makes the CLI fail and write nothing.  `clean` SHALL remove the library with the tool wrappers, the tunnel script and the marker.  The library SHALL work from a Homebrew install with no source checkout.

#### Scenario: Library written on the first kit process
- **WHEN** a kit process runs in a workspace whose `bin/` holds the tool wrappers and the marker but no `edl-kit-lib.sh`
- **THEN** `<workspace>/bin/edl-kit-lib.sh` exists and its content equals the packaged resource

#### Scenario: Existing library is left as it is
- **WHEN** `<workspace>/bin/edl-kit-lib.sh` exists with content that differs from the packaged resource
- **AND** a kit process runs
- **THEN** the file content is unchanged

#### Scenario: Library in a foreign bin directory
- **WHEN** `<workspace>/bin/` holds `edl-kit-lib.sh` and has no marker
- **THEN** the CLI fails with the foreign-file message and writes nothing

#### Scenario: clean removes the library
- **WHEN** the user runs `clean` in a workspace whose `bin/` holds the library
- **THEN** `<workspace>/bin/edl-kit-lib.sh` no longer exists

#### Scenario: Library is never a phase command
- **WHEN** a kit is installed and the CLI registers its subcommands
- **THEN** no subcommand is registered for `edl-kit-lib.sh`

### Requirement: Kit processes find the library through EDL_KIT_LIB
`KitProcessEnvironment` SHALL set `EDL_KIT_LIB` (`Constants.Kit.LIBRARY_ENV`) to the absolute path of `<workspace>/bin/edl-kit-lib.sh` for every kit process it launches: phase scripts, `shell` steps and hooks.  A kit script SHALL source it with `. "${EDL_KIT_LIB:?run this through easy-db-lab, which sets EDL_KIT_LIB}"`.  Sourcing the library SHALL change no `set` option, trap or working directory, and SHALL define only `edl_` functions and `EDL_` variables.

#### Scenario: Phase script gets EDL_KIT_LIB
- **WHEN** a kit phase script runs
- **THEN** its environment holds `EDL_KIT_LIB` set to the absolute path of `<workspace>/bin/edl-kit-lib.sh`

#### Scenario: Install shell step gets EDL_KIT_LIB
- **WHEN** a kit `shell` step runs during `kit install`
- **THEN** its environment holds `EDL_KIT_LIB`

#### Scenario: Script run outside easy-db-lab
- **WHEN** a kit script that sources the library runs with no `EDL_KIT_LIB` set
- **THEN** it exits non-zero with `run this through easy-db-lab, which sets EDL_KIT_LIB`

#### Scenario: Sourcing leaves shell options unchanged
- **WHEN** a shell with `set -euo pipefail` sources the library
- **THEN** the output of `set -o` is the same before and after

### Requirement: Library runs under bash 3.2
The library and every kit script that sources it SHALL run under bash 3.2, the macOS `/bin/bash`.  They SHALL use no associative arrays, `mapfile`, `${v,,}` or `wait -n`, and SHALL NOT expand an array that can be empty as `"${arr[@]}"` under `set -u`.  The library tests and the kit script tests SHALL also run under `/bin/bash` when it is bash 3.x.

#### Scenario: Tests run under bash 3.x
- **WHEN** `./gradlew testScripts` runs on a machine whose `/bin/bash` is version 3.x
- **THEN** the library tests and the kit script tests run under `/bin/bash` as well as under `bash` from `PATH`

#### Scenario: Empty optional list under set -u
- **WHEN** `edl_pod_overrides` runs with no ConfigMap argument under bash 3.2 and `set -u`
- **THEN** it prints valid JSON and exits 0

### Requirement: App-node check and pod placement
`edl_require_app_node <kit>` SHALL run `kubectl get nodes -l type=app -o name` and, when it returns no node, SHALL print `<kit> needs an app node (type=app); this cluster has none` and exit non-zero.  `edl_pod_overrides [<configmap> <mount-path> <container>]` SHALL print literal JSON, with no `jq`, for `kubectl run --overrides=<json> --override-type=strategic`, that sets `nodeSelector: {type: app}` and, when a ConfigMap is given, a volume and a volume mount for it.  It SHALL reject a ConfigMap or container name that is not a DNS-1123 name.

#### Scenario: Cluster has no app node
- **WHEN** `edl_require_app_node clickhouse-benchmark` runs and `kubectl get nodes -l type=app` returns nothing
- **THEN** it prints `clickhouse-benchmark needs an app node (type=app); this cluster has none` and exits non-zero

#### Scenario: Cluster has an app node
- **WHEN** `kubectl get nodes -l type=app` returns one node
- **THEN** `edl_require_app_node` exits 0

#### Scenario: Overrides JSON pins to app nodes
- **WHEN** `edl_pod_overrides edl-q-1 /edl run` runs
- **THEN** its output is valid JSON with `spec.nodeSelector.type` equal to `app`, one ConfigMap volume for `edl-q-1` and a mount at `/edl` on container `run`

#### Scenario: Bad name rejected
- **WHEN** `edl_pod_overrides 'Bad_Name' /edl run` runs
- **THEN** it exits non-zero and names `Bad_Name`

### Requirement: Names and labels for kit resources
`edl_resource_name <role>` SHALL print `${KIT_NAME}-<role>-$(date +%s)`.  `edl_labels <role>` SHALL print `easydblab/kit=${KIT_NAME},easydblab/role=<role>`.

#### Scenario: Resource name ends in a timestamp
- **WHEN** `KIT_NAME=clickhouse-benchmark-clickhouse` and `date +%s` returns `1700000000`
- **THEN** `edl_resource_name run` prints `clickhouse-benchmark-clickhouse-run-1700000000`

#### Scenario: Labels name kit and role
- **WHEN** `KIT_NAME=sysbench-tidb`
- **THEN** `edl_labels sysbench-run` prints `easydblab/kit=sysbench-tidb,easydblab/role=sysbench-run`

### Requirement: Labelled ConfigMap creation with a size limit
`edl_create_configmap <name> <role> <key>=<path>...` SHALL check that each path exists and is not empty, and that the combined size is at most `EDL_CONFIGMAP_MAX_BYTES` (1,000,000), before it calls the cluster.  It SHALL create the ConfigMap and its labels in one call: `kubectl create configmap --dry-run=client -o yaml`, piped to `kubectl label --local -f - ... -o yaml`, piped to `kubectl create -f -`.  It SHALL end with `create`, not `apply`.

#### Scenario: Missing file
- **WHEN** a path does not exist
- **THEN** the function exits non-zero, names the path and calls no `kubectl create`

#### Scenario: Empty file
- **WHEN** a path is an empty file
- **THEN** the function exits non-zero and names the path

#### Scenario: Size limit
- **WHEN** the combined size of the files is more than 1,000,000 bytes
- **THEN** the function exits non-zero, gives the size and the limit, and calls no `kubectl create`

#### Scenario: ConfigMap carries its labels
- **WHEN** the files exist and fit
- **THEN** the ConfigMap is created with `easydblab/kit=${KIT_NAME}` and `easydblab/role=<role>` in the same call

### Requirement: Active pod listing
`edl_active_pods <role>` SHALL list the pods of this instance with that role that have not ended, with `--field-selector=status.phase!=Succeeded,status.phase!=Failed` and `-l easydblab/kit=${KIT_NAME},easydblab/role=<role>`.

#### Scenario: Pending and Running pods listed
- **WHEN** one pod with the role is Pending and one is Succeeded
- **THEN** `edl_active_pods` prints only the Pending pod's name

### Requirement: Wait for a pod to leave Pending
`edl_wait_for_pod_running <pod>` SHALL return 0 once the pod's phase is no longer Pending (Running, Succeeded or Failed).  When a container's waiting reason is `ErrImagePull`, `ImagePullBackOff` or `InvalidImageName`, it SHALL fail at once with the reason, the message and the `kubectl describe` events.  Otherwise it SHALL fail after `EDL_POD_START_TIMEOUT_SECONDS` (600) and print `kubectl describe`.  A failed `kubectl get` SHALL count as a retry, never as a phase.  When the pod is NotFound, it SHALL fail with `pod <name> was deleted`.

#### Scenario: Pod finishes before it is seen Running
- **WHEN** the first poll reports the pod as Succeeded
- **THEN** the function returns 0 at once

#### Scenario: Pull error fails fast
- **WHEN** the pod is Pending with waiting reason `ImagePullBackOff`
- **THEN** the function exits non-zero at once, prints `ImagePullBackOff`, the message and the describe events

#### Scenario: 10-minute limit
- **WHEN** the pod stays Pending with no pull error for longer than `EDL_POD_START_TIMEOUT_SECONDS`
- **THEN** the function exits non-zero, says the pod did not start within the limit and prints `kubectl describe`

#### Scenario: Pod deleted while waiting
- **WHEN** `kubectl get pod` reports NotFound
- **THEN** the function exits non-zero with `pod <name> was deleted`

### Requirement: Follow pod logs with resume
`edl_follow_pod_logs <pod> <handler-fn>` SHALL run `kubectl logs -f --timestamps`, strip the timestamp and pass each line to the handler.  When `kubectl logs -f` returns, the function SHALL check the pod phase: while the phase is Running it SHALL resume with `--since-time` set to the last timestamp seen; when the pod has ended it SHALL return 0; when the pod is NotFound it SHALL fail with `pod <name> was deleted`.  A non-zero `kubectl logs` SHALL NOT end the calling script under `set -euo pipefail`.  A resumed stream can repeat up to one second of lines, because `--since-time` has one-second precision; the stream drives display and live gauges only.

#### Scenario: Stream cut while the pod runs
- **WHEN** `kubectl logs -f` returns while the pod is Running
- **THEN** the function runs `kubectl logs -f` again with `--since-time` and keeps passing lines to the handler

#### Scenario: Stream ends with the pod
- **WHEN** `kubectl logs -f` returns and the pod is Succeeded or Failed
- **THEN** the function returns 0

#### Scenario: Pod deleted mid-run
- **WHEN** `kubectl logs -f` returns and the pod is NotFound
- **THEN** the function exits non-zero with `pod <name> was deleted`

#### Scenario: Failed log stream under pipefail
- **WHEN** a script with `set -euo pipefail` calls the function and `kubectl logs -f` exits non-zero while the pod is Running
- **THEN** the script continues and the function resumes the stream

### Requirement: Wait for a pod to exit and report its exit code
`edl_wait_for_pod_exit <pod>` SHALL poll until the pod phase is Succeeded or Failed, with no `kubectl wait` and no fixed timeout, and SHALL print `.status.containerStatuses[0].state.terminated.exitCode`.  A failed `kubectl get` SHALL count as a retry.  When the pod is NotFound, it SHALL fail with `pod <name> was deleted`.

#### Scenario: Pod exits non-zero
- **WHEN** the pod ends in phase Failed with exit code 3
- **THEN** the function prints `3`

#### Scenario: Pod exits 0
- **WHEN** the pod ends in phase Succeeded
- **THEN** the function prints `0`

#### Scenario: Pod deleted while waiting for exit
- **WHEN** the pod is NotFound
- **THEN** the function exits non-zero with `pod <name> was deleted`

### Requirement: Gauge push never fails the run
`edl_push_gauges <service-name> <name> <value> ...` SHALL POST one OTLP gauge per name and value pair to the control node collector (`CONTROL_HOST_PRIVATE`), each labelled `kit=${KIT_NAME}`.  A value that is missing or not a number SHALL be sent as 0.  A failed POST SHALL NOT end the calling script.

#### Scenario: Gauge body
- **WHEN** `edl_push_gauges clickhouse-benchmark clickhouse_benchmark_qps 12.5` runs
- **THEN** the POST body holds a gauge named `clickhouse_benchmark_qps` with value `12.5` and attribute `kit=${KIT_NAME}`

#### Scenario: Non-numeric value
- **WHEN** a value is `abc` or empty
- **THEN** the gauge is sent with value 0

#### Scenario: Collector unreachable
- **WHEN** the POST fails
- **THEN** the function returns 0
