# Flink Metrics

Apache Flink exposes metrics via its built-in Prometheus reporter on `:9249` (both the
JobManager and every TaskManager). The OTel DaemonSet scrapes both and forwards them to
VictoriaMetrics with `job="flink"` and `cluster="<cluster-name>"` labels.

All metric names below are present in `metrics-catalog.json` (exported from a live session
cluster running the `TopSpeedWindowing` example job).

## Common labels

| Label | Description |
|-------|-------------|
| `cluster` | easy-db-lab cluster name |
| `job` | Always `flink` |
| `host_name` | App node the pod runs on (`app0`, `app1`, …) |
| `instance` | `<host>:9249` scrape target |
| `job_name` | Flink job name (task/operator metrics only) |
| `task_name` | Operator/task name (task metrics only) |
| `subtask_index` | Parallel subtask index (task metrics only) |
| `tm_id` | TaskManager id (task metrics only) |

## JobManager — cluster & slots

| Metric | Description |
|--------|-------------|
| `flink_jobmanager_numRegisteredTaskManagers` | Registered TaskManagers |
| `flink_jobmanager_numPendingTaskManagers` | TaskManagers requested but not yet registered |
| `flink_jobmanager_numRunningJobs` | Running jobs in the session cluster |
| `flink_jobmanager_taskSlotsTotal` | Total task slots |
| `flink_jobmanager_taskSlotsAvailable` | Free task slots |

## Job — health & checkpoints

| Metric | Description |
|--------|-------------|
| `flink_jobmanager_job_uptime` | Time since the job started (ms) |
| `flink_jobmanager_job_downtime` | Time the job has not been running (ms) |
| `flink_jobmanager_job_numRestarts` | Cumulative restarts |
| `flink_jobmanager_job_fullRestarts` | Full (not fine-grained) restarts |
| `flink_jobmanager_job_numberOfCompletedCheckpoints` | Completed checkpoints |
| `flink_jobmanager_job_numberOfFailedCheckpoints` | Failed checkpoints |
| `flink_jobmanager_job_numberOfInProgressCheckpoints` | In-progress checkpoints |
| `flink_jobmanager_job_lastCheckpointDuration` | Duration of the last checkpoint (ms) |
| `flink_jobmanager_job_lastCheckpointSize` | Size of the last checkpoint (bytes) |
| `flink_jobmanager_job_lastCheckpointFullSize` | Full size of the last checkpoint (bytes) |

## Task — throughput & backpressure

These carry `job_name`, `task_name`, and `subtask_index` labels — group by `task_name` for
per-operator views.

| Metric | Description |
|--------|-------------|
| `flink_taskmanager_job_task_numRecordsInPerSecond` | Records consumed per second |
| `flink_taskmanager_job_task_numRecordsOutPerSecond` | Records emitted per second |
| `flink_taskmanager_job_task_numBytesInPerSecond` | Bytes consumed per second |
| `flink_taskmanager_job_task_numBytesOutPerSecond` | Bytes emitted per second |
| `flink_taskmanager_job_task_busyTimeMsPerSecond` | Time per second the task was busy (0–1000) |
| `flink_taskmanager_job_task_idleTimeMsPerSecond` | Time per second the task was idle |
| `flink_taskmanager_job_task_backPressuredTimeMsPerSecond` | Time per second the task was back-pressured |
| `flink_taskmanager_job_task_isBackPressured` | 1 if the task is currently back-pressured |
| `flink_taskmanager_job_task_buffers_inPoolUsage` | Input buffer pool usage (0–1) |
| `flink_taskmanager_job_task_buffers_outPoolUsage` | Output buffer pool usage (0–1) |

## TaskManager / JobManager — JVM

Available with both `flink_taskmanager_Status_JVM_*` and `flink_jobmanager_Status_JVM_*` prefixes.

| Metric | Description |
|--------|-------------|
| `flink_taskmanager_Status_JVM_Memory_Heap_Used` | Heap memory used (bytes) |
| `flink_taskmanager_Status_JVM_Memory_Heap_Max` | Heap memory limit (bytes) |
| `flink_taskmanager_Status_JVM_Memory_NonHeap_Used` | Non-heap memory used (bytes) |
| `flink_taskmanager_Status_JVM_CPU_Load` | Process CPU load (0–1) |
| `flink_taskmanager_Status_JVM_Threads_Count` | Live JVM threads |
| `flink_taskmanager_Status_JVM_GarbageCollector_All_TimeMsPerSecond` | GC time per second (ms) |
| `flink_taskmanager_Status_Flink_Memory_Managed_Used` | Flink managed memory used (bytes) |
| `flink_taskmanager_Status_Flink_Memory_Managed_Total` | Flink managed memory total (bytes) |

## TaskManager — network

| Metric | Description |
|--------|-------------|
| `flink_taskmanager_Status_Network_TotalMemorySegments` | Total network memory segments |
| `flink_taskmanager_Status_Network_AvailableMemorySegments` | Free network memory segments |
| `flink_taskmanager_job_task_buffers_inputQueueLength` | Input queue length (buffers) |
| `flink_taskmanager_job_task_buffers_outputQueueLength` | Output queue length (buffers) |

See `metrics-catalog.json` for the full set of 300+ series, including per-operator
(`flink_taskmanager_job_task_operator_*`) and Netty shuffle (`*_Shuffle_Netty_*`) metrics.
