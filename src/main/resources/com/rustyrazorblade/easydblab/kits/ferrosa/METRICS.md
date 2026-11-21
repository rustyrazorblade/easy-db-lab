# FerrosaDB Metrics

All metrics have `job="ferrosa"` and `cluster=<cluster-name>-<cluster-id>`. They are scraped from the FerrosaDB
console listener (`/metrics` on container port 9090) by pod discovery: each pod is scraped once, by
the collector on its own node, and `instance` is the pod name (`ferrosa-0-0`, `ferrosa-1-0`,
`ferrosa-2-0`). Every series also carries `service_name="ferrosa"`.

The catalog (`metrics-catalog.json`) was exported from Mimir on a 3-node ring running the release
build, so it lists the series FerrosaDB exposes after the OpenTelemetry collector's normalization.
Descriptions come from the `# HELP` text of FerrosaDB's own `/metrics` output. A series without
HELP text there is marked as such. Counters end in `_total`: always use `rate()` or `increase()`.
Histograms expose `_bucket`, `_sum` and `_count` series; the bare histogram name is not a series.

## CQL Requests

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `ferrosa_cql_request_duration_seconds_bucket` | histogram series | kind,le | CQL data request latency in seconds, by kind. |
| `ferrosa_cql_request_duration_seconds_count` | histogram series | kind | CQL data request latency in seconds, by kind. |
| `ferrosa_cql_request_duration_seconds_sum` | histogram series | kind | CQL data request latency in seconds, by kind. |
| `ferrosa_cql_requests_in_flight` | gauge | kind | CQL data requests currently being handled, by kind. |
| `ferrosa_cql_requests_total` | counter | kind,outcome | CQL data requests finished, by kind and outcome (ok, error, cancelled). |

## Storage, Commit Log, Compaction and Cluster

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `ferrosa_anti_entropy_refills_no_source_total` | counter | - | Quarantined ranges with no verified-healthy replica to refill from. |
| `ferrosa_anti_entropy_refills_scheduled_total` | counter | - | Targeted refills scheduled from a healthy replica after quarantine. |
| `ferrosa_archive_status_archive_errors_total` | counter | last_archive_success | No HELP text is exported for this series. |
| `ferrosa_archive_status_oldest_unarchived_age_secs` | gauge | last_archive_success | No HELP text is exported for this series. |
| `ferrosa_archive_status_unarchived_segments` | gauge | last_archive_success | No HELP text is exported for this series. |
| `ferrosa_auto_repair_partitions_streamed_total` | counter | - | Partitions streamed (in+out) by automatic repair. |
| `ferrosa_auto_repair_sessions_failed_total` | counter | - | Automatic repair sessions that returned an error. |
| `ferrosa_auto_repair_sessions_ok_total` | counter | - | Automatic repair sessions that completed without error. |
| `ferrosa_auto_repair_skipped_not_ready_total` | counter | - | Ticks skipped because the node was not ring-ready. |
| `ferrosa_auto_repair_tables_failed_total` | counter | - | Table repair cycles with at least one failed session. |
| `ferrosa_auto_repair_tables_no_sessions_total` | counter | - | Table repair cycles in which no session was attempted. |
| `ferrosa_auto_repair_tables_repaired_total` | counter | - | Tables for which automatic repair ran a cycle. |
| `ferrosa_auto_repair_ticks_total` | counter | - | Automatic repair sub-ticks executed. |
| `ferrosa_auto_repair_timestamp_ties_total` | counter | - | Divergent partitions with identical timestamps surfaced (not auto-resolved). |
| `ferrosa_blocking_pool_capacity` | gauge | pool | Blocking jobs a pool admits at once. |
| `ferrosa_blocking_pool_in_flight` | gauge | pool | Blocking jobs currently admitted. |
| `ferrosa_blocking_pool_saturated` | gauge | pool | 1 while callers are waiting for admission. |
| `ferrosa_blocking_pool_waiting` | gauge | pool | Callers waiting for admission. |
| `ferrosa_blocking_pool_waits_total` | counter | pool | Callers that had to wait for admission. |
| `ferrosa_cgroup_memory_bytes` | gauge | kind | Cgroup memory usage and limit in bytes. |
| `ferrosa_cgroup_memory_events_total` | counter | event | Cgroup memory pressure and OOM events. |
| `ferrosa_commitlog_append_alloc_seconds_max` | gauge | - | Maximum wall time spent allocating commit-log entry space. |
| `ferrosa_commitlog_append_alloc_seconds_total` | counter | - | Total wall time spent allocating commit-log entry space. |
| `ferrosa_commitlog_append_bytes_total` | counter | - | Total bytes reserved for commit-log entries. |
| `ferrosa_commitlog_append_mark_dirty_seconds_max` | gauge | - | Maximum wall time spent updating commit-log dirty-table tracking. |
| `ferrosa_commitlog_append_mark_dirty_seconds_total` | counter | - | Total wall time spent updating commit-log dirty-table tracking. |
| `ferrosa_commitlog_append_rotate_seconds_max` | gauge | - | Maximum wall time an append spent rotating the active segment inline. |
| `ferrosa_commitlog_append_rotate_seconds_total` | counter | - | Total wall time appends spent rotating the active segment inline. |
| `ferrosa_commitlog_append_rotations_total` | counter | - | Commit-log appends that had to rotate the active segment inline. |
| `ferrosa_commitlog_append_sync_notify_seconds_max` | gauge | - | Maximum wall time an append caller spent in sync strategy notification. |
| `ferrosa_commitlog_append_sync_notify_seconds_total` | counter | - | Total wall time append callers spent in the configured sync strategy notification. |
| `ferrosa_commitlog_append_write_entry_seconds_max` | gauge | - | Maximum wall time spent serializing one commit-log entry into memory. |
| `ferrosa_commitlog_append_write_entry_seconds_total` | counter | - | Total wall time spent serializing commit-log entries into memory. |
| `ferrosa_commitlog_appends_total` | counter | - | Total commit-log appends. |
| `ferrosa_commitlog_archive_notify_dropped_total` | counter | - | Closed commit-log segments never handed to the PITR archiver (queue full or archiver stopped). |
| `ferrosa_commitlog_empty_segments_skipped_total` | counter | - | Empty or torn commit-log segments skipped during replay. |
| `ferrosa_commitlog_flush_bytes_total` | counter | - | Bytes written by incremental commit-log flushes. |
| `ferrosa_commitlog_flushes_total` | counter | - | Incremental commit-log flushes. |
| `ferrosa_commitlog_full_flush_bytes_total` | counter | - | Bytes written by full segment rewrite flushes. |
| `ferrosa_commitlog_full_flushes_total` | counter | - | Full segment rewrite flushes. |
| `ferrosa_commitlog_not_durable_refusals_total` | counter | - | Writes refused because the commit log could not make them durable (sync thread dead, fsync failing, or past the stall deadline). |
| `ferrosa_commitlog_periodic_idle_flushes_skipped_total` | counter | - | Periodic commit-log timer ticks skipped because no writes were pending. |
| `ferrosa_commitlog_replay_set_aside_mutations_total` | counter | - | Mutations set aside on disk at startup because no schema was available to replay them. |
| `ferrosa_commitlog_replay_set_aside_pending_mutations` | gauge | - | Set-aside mutations not yet re-ingested; non-zero means rows are on disk but invisible to reads. |
| `ferrosa_commitlog_sync_batch_bytes_total` | counter | - | Commit-log bytes included in sync batches. |
| `ferrosa_commitlog_sync_batch_wait_seconds_max` | gauge | - | Maximum time a sync batch stayed open before flush. |
| `ferrosa_commitlog_sync_batch_wait_seconds_total` | counter | - | Total time sync batches stayed open before flush. |
| `ferrosa_commitlog_sync_batch_writes_total` | counter | - | Commit-log writes included in sync batches. |
| `ferrosa_commitlog_sync_batches_total` | counter | - | Commit-log sync batches flushed. |
| `ferrosa_commitlog_sync_data_seconds_max` | gauge | - | Maximum wall time spent in commit-log file sync_data/fsync. |
| `ferrosa_commitlog_sync_data_seconds_total` | counter | - | Total wall time spent in commit-log file sync_data/fsync. |
| `ferrosa_commitlog_sync_file_lock_wait_seconds_max` | gauge | - | Maximum wait for the segment file mutex during commit-log sync. |
| `ferrosa_commitlog_sync_file_lock_wait_seconds_total` | counter | - | Total wall time commit-log sync spent waiting for the segment file mutex. |
| `ferrosa_commitlog_sync_parent_dir_seconds_max` | gauge | - | Maximum wall time spent syncing a commit-log parent directory. |
| `ferrosa_commitlog_sync_parent_dir_seconds_total` | counter | - | Total wall time spent syncing parent directories for newly-created commit-log files. |
| `ferrosa_commitlog_sync_pending_bytes` | gauge | - | Commit-log bytes currently waiting for sync. |
| `ferrosa_commitlog_sync_pending_writes` | gauge | - | Commit-log writes currently waiting for sync. |
| `ferrosa_commitlog_sync_seconds_max` | gauge | - | Maximum observed wall time for a commit-log fsync call. |
| `ferrosa_commitlog_sync_seconds_total` | counter | - | Total wall time spent in commit-log flush_to_disk calls that performed fsync. |
| `ferrosa_commitlog_sync_stalls_total` | counter | cause | Commit-log fsync stalls by cause. cause=no_attempt_issued means no fsync was ever issued during the stall — the sync thread did not run, which a restart may help; device_slow means an fsync was in flight the whole time, which it will not. |
| `ferrosa_commitlog_sync_wait_writers_seconds_max` | gauge | - | Maximum observed wait-for-writers time before commit-log sync. |
| `ferrosa_commitlog_sync_wait_writers_seconds_total` | counter | - | Total wall time commit-log sync spent waiting for in-flight writers to finish entries. |
| `ferrosa_commitlog_sync_write_seconds_max` | gauge | - | Maximum wall time spent writing commit-log bytes to the file before sync. |
| `ferrosa_commitlog_sync_write_seconds_total` | counter | - | Total wall time spent writing commit-log bytes to the file before sync. |
| `ferrosa_commitlog_syncs_by_mode_total` | counter | mode | Commit-log file and directory syncs by the system call issued (full = F_FULLFSYNC, barrier = F_BARRIERFSYNC, fsync = fsync(2)); only full survives power loss on macOS. |
| `ferrosa_commitlog_syncs_total` | counter | - | Successful commit-log fsync calls. |
| `ferrosa_coordinator_anti_entropy_repairs_requested_total` | counter | - | Async anti-entropy repairs requested after serving a read around a corrupt local SSTable. |
| `ferrosa_coordinator_hints_rejected_total` | counter | - | Hint writes rejected by bounded hint backpressure. |
| `ferrosa_coordinator_hints_stored_total` | counter | - | Hints successfully written by coordinators. |
| `ferrosa_coordinator_inbound_mutation_failures_total` | counter | - | MutationForward writes that failed on this node. |
| `ferrosa_coordinator_inbound_mutation_forwards_total` | counter | - | MutationForward RPCs applied on this node. |
| `ferrosa_coordinator_inbound_mutation_rows_total` | counter | - | MutationForward rows applied on this node. |
| `ferrosa_coordinator_post_quorum_remote_acks_total` | counter | - | Remote write ACKs observed after client-visible quorum was satisfied. |
| `ferrosa_coordinator_post_quorum_remote_failures_total` | counter | - | Remote write failures observed after client-visible quorum was satisfied. |
| `ferrosa_coordinator_replica_write_acks_total` | counter | target | Successful replica write acknowledgements observed by this coordinator. |
| `ferrosa_coordinator_replica_write_attempts_total` | counter | target | Replica write attempts by this coordinator. |
| `ferrosa_coordinator_replica_write_failures_total` | counter | target | Failed replica writes observed by this coordinator. |
| `ferrosa_coordinator_write_admission_in_flight` | gauge | - | Client-visible writes holding coordinator admission permits, including post-quorum replica drain. |
| `ferrosa_coordinator_write_admission_in_flight_max` | gauge | - | Maximum writes holding coordinator admission permits since process start. |
| `ferrosa_emfile_total` | counter | - | Total observed EMFILE open-file-limit errors. |
| `ferrosa_fd_budget_hard` | gauge | - | Hard process file descriptor limit (RLIMIT_NOFILE). |
| `ferrosa_fd_budget_soft` | gauge | - | Soft process file descriptor limit (RLIMIT_NOFILE). |
| `ferrosa_full_scan_reasons_count` | gauge | keyspace,table_name | No HELP text is exported for this series. |
| `ferrosa_full_scan_reasons_last_seen_ms` | gauge | keyspace,table_name | No HELP text is exported for this series. |
| `ferrosa_graph_adjacency_entries_removed_total` | counter | - | Live adjacency entries of deleted or missing edges tombstoned by reconcile. |
| `ferrosa_graph_adjacency_entries_repaired_total` | counter | - | Adjacency entries of live edges written back by reconcile. |
| `ferrosa_graph_adjacency_entries_rewritten_total` | counter | - | Adjacency entries the first-use heal rewrote although this node read them as right. |
| `ferrosa_graph_adjacency_heals_completed_total` | counter | - | Graph keyspaces whose first-use adjacency heal completed. |
| `ferrosa_graph_adjacency_heals_failed_total` | counter | - | First-use adjacency heals that were incomplete; their queries failed retryably. |
| `ferrosa_graph_adjacency_queries_refused_during_heal_total` | counter | - | Adjacency queries refused retryably because their keyspace's heal was still running. |
| `ferrosa_graph_adjacency_reconcile_errors_total` | counter | - | Reconcile reads, scans and writes that failed (the pass was incomplete). |
| `ferrosa_graph_adjacency_reconcile_passes_total` | counter | - | Adjacency reconcile passes run. |
| `ferrosa_host_block_device_in_flight` | gauge | device | Current in-flight IOs from /proc/diskstats. |
| `ferrosa_host_block_device_io_bytes_total` | counter | device,direction | Block device IO bytes from /proc/diskstats, assuming 512-byte sectors. |
| `ferrosa_host_block_device_io_seconds_total` | counter | device,direction | Block device IO time from /proc/diskstats. |
| `ferrosa_host_block_device_io_total` | counter | device,direction | Block device IO operations from /proc/diskstats. |
| `ferrosa_host_network_receive_bytes_total` | counter | interface | Host network receive bytes by interface, from /proc/net/dev where available. |
| `ferrosa_host_network_transmit_bytes_total` | counter | interface | Host network transmit bytes by interface, from /proc/net/dev where available. |
| `ferrosa_index_backfill_build_failures_total` | counter | - | Secondary-index backfill builds that failed. |
| `ferrosa_index_backfill_retries_total` | counter | - | Pending secondary-index generations resubmitted for a build after a failure or a stall. |
| `ferrosa_listener_up` | gauge | listener | 1 when a background client listener is serving, 0 when starting, failed to bind, or exited. |
| `ferrosa_net_data_lane_active` | gauge | - | Process-wide data lane RPCs currently dispatched. |
| `ferrosa_net_data_lane_active_max` | gauge | - | Maximum observed process-wide data lane RPCs dispatched since process start. |
| `ferrosa_net_data_lane_cap_blocked_total` | counter | - | Data lane dispatch attempts delayed by the process-wide in-flight cap. |
| `ferrosa_net_data_lane_max_in_flight` | gauge | - | Configured process-wide data lane in-flight cap. |
| `ferrosa_net_data_lane_rejected_total` | counter | - | Data lane RPCs rejected by local backpressure. |
| `ferrosa_net_lane_abandoned_unsent_total` | counter | lane | Queued RPCs discarded unsent because the caller stopped waiting. |
| `ferrosa_net_lane_enqueue_total` | counter | lane | Internode lane actor enqueue attempts by lane. |
| `ferrosa_net_lane_expired_in_queue_total` | counter | lane | Queued RPCs answered Timeout unsent because their deadline ran out in the lane queue. |
| `ferrosa_net_lane_pending_stream_depth` | gauge | lane | Pending request/response RPCs waiting inside each lane actor. |
| `ferrosa_net_lane_pending_stream_depth_max` | gauge | lane | Maximum pending request/response RPCs observed inside each lane actor. |
| `ferrosa_net_lane_queue_capacity` | gauge | lane | Last observed lane actor queue capacity by lane. |
| `ferrosa_net_lane_queue_depth_max` | gauge | lane | Maximum observed lane actor queue depth after enqueue by lane. |
| `ferrosa_net_lane_queue_wait_seconds_max` | gauge | lane | Maximum observed time a caller waited for lane actor queue capacity. |
| `ferrosa_net_lane_queue_wait_seconds_total` | counter | lane | Total time callers waited for lane actor queue capacity. |
| `ferrosa_net_lane_stream_capacity` | gauge | lane | Configured max in-flight request/response RPCs per peer lane. |
| `ferrosa_net_lane_stream_wait_seconds_max` | gauge | lane | Maximum observed time a caller waited for per-lane stream window capacity. |
| `ferrosa_net_lane_stream_wait_seconds_total` | counter | lane | Total time callers waited for per-lane stream window capacity. |
| `ferrosa_net_rpc_handler_failures_total` | counter | kind | Inbound internode RPC requests that got no response, by cause. |
| `ferrosa_net_rpc_in_flight` | gauge | lane | Internode RPC requests currently in flight by lane. |
| `ferrosa_net_rpc_in_flight_max` | gauge | lane | Maximum observed internode RPC requests in flight by lane since process start. |
| `ferrosa_net_rpc_orphan_responses_total` | counter | - | RPC responses received after caller timeout or cancellation. |
| `ferrosa_net_rpc_requests_total` | counter | lane | Internode RPC requests started by lane. |
| `ferrosa_net_rpc_timeouts_total` | counter | lane | Internode RPC request timeouts by lane. |
| `ferrosa_pg_suspended_portal_expiries_total` | counter | - | Suspended portals closed because no Execute touched them within the idle timeout. |
| `ferrosa_pg_suspended_portal_refusals_total` | counter | - | Portals refused suspension because a connection or the node was at its limit (SQLSTATE 53000). |
| `ferrosa_pg_suspended_portals` | gauge | - | PostgreSQL portals suspended by max_rows and still open. |
| `ferrosa_process_cpu_seconds_total` | counter | - | Total user and system CPU time spent by this process. |
| `ferrosa_process_io_read_bytes_total` | counter | - | Bytes read from storage by this process, from /proc/self/io where available. |
| `ferrosa_process_io_read_syscalls_total` | counter | - | Read syscalls issued by this process. |
| `ferrosa_process_io_write_bytes_total` | counter | - | Bytes written to storage by this process, from /proc/self/io where available. |
| `ferrosa_process_io_write_syscalls_total` | counter | - | Write syscalls issued by this process. |
| `ferrosa_process_memory_bytes` | gauge | kind | Process memory from /proc/self/status by category. |
| `ferrosa_process_resident_memory_bytes` | gauge | - | Resident set size (RSS) in bytes. |
| `ferrosa_process_smaps_rollup_bytes` | gauge | kind | Process memory accounting from /proc/self/smaps_rollup by category. |
| `ferrosa_process_virtual_memory_bytes` | gauge | - | Virtual memory size in bytes. |
| `ferrosa_raft_current_term` | gauge | - | Latest observed Raft current_term on this node. |
| `ferrosa_raft_election_storm_term_jumps_total` | counter | - | Election-storm detections (P0-17/P0-19 watchdog); non-zero should alert. |
| `ferrosa_raft_has_leader` | gauge | - | 1 if a Raft leader was known at the last poll, else 0. |
| `ferrosa_raft_is_leader` | gauge | - | 1 if this node was the Raft leader at the last poll, else 0. |
| `ferrosa_raft_replication_lag_entries` | gauge | peer | Leader committed index minus the follower's matched index, at the last leader sweep. |
| `ferrosa_raft_replication_lag_flat_sweeps` | gauge | peer | Consecutive leader sweeps in which a lagging follower's lag did not shrink; readiness reports degraded at 6. |
| `ferrosa_raft_snapshot_send_consecutive_failures` | gauge | peer | Consecutive failed snapshot chunk sends to the follower; reset by a successful send. |
| `ferrosa_rrd_runtime_settings_setting_value` | gauge | setting_name,source | No HELP text is exported for this series. |
| `ferrosa_sched_admissions_rejected_overload_total` | counter | - | Admission requests shed because the waiter queue was at its bound; non-zero means real overload backpressure. |
| `ferrosa_sched_admit_wait_micros_total` | counter | - | Cumulative microseconds producers waited for a pool slot. |
| `ferrosa_sched_consensus_headroom_cores` | gauge | - | CPU cores currently free of scheduler-pool work (>= reserved). |
| `ferrosa_sched_io_permits_acquired_total` | counter | - | Bulk-I/O permits granted since start (the I/O resource dimension, B2). |
| `ferrosa_sched_io_permits_capacity` | gauge | - | Max concurrent bulk-I/O permits (the Lane::Bulk reservation). |
| `ferrosa_sched_io_permits_in_flight` | gauge | - | Bulk-I/O permits currently held (in-flight bulk I/O). |
| `ferrosa_sched_max_chunk_micros` | gauge | - | Longest scan chunk (work between cooperative yields), microseconds. |
| `ferrosa_sched_over_budget_chunks_total` | counter | - | Scan chunks exceeding the per-chunk work budget (FM-2 yield-point gap); non-zero should alert. |
| `ferrosa_sched_pool_active` | gauge | - | Background closures currently admitted (running). |
| `ferrosa_sched_pool_capacity` | gauge | - | Max concurrent background closures the pool admits (cores - reserved). |
| `ferrosa_sched_runtime_stall_events_total` | counter | - | Async-runtime scheduling stalls (a tokio worker blocked long enough to delay the liveness task); non-zero degrades interactive latency and should alert. |
| `ferrosa_sched_runtime_stall_max_micros` | gauge | - | Longest single runtime scheduling stall (microseconds). |
| `ferrosa_sched_runtime_stall_micros_total` | counter | - | Cumulative microseconds of runtime scheduling overrun across all stalls. |
| `ferrosa_sched_scan_carrier_threads_busy` | gauge | - | Scheduler-owned threads currently running a scan producer. |
| `ferrosa_sched_scan_carrier_threads_capacity` | gauge | - | Most scheduler-owned threads scan producers can occupy. |
| `ferrosa_sched_scan_releases_total` | counter | - | Times a scan producer paused, giving back its pool slot and its thread, because its consumer stopped reading. |
| `ferrosa_sched_tasks_admitted_total` | counter | - | Closures admitted to the bounded pool since start. |
| `ferrosa_secondary_indexes_build_errors` | gauge | index_name,index_type,keyspace_name,status,table_name | No HELP text is exported for this series. |
| `ferrosa_secondary_indexes_indexed_sstable_count` | gauge | index_name,index_type,keyspace_name,status,table_name | No HELP text is exported for this series. |
| `ferrosa_secondary_indexes_lag_seconds` | gauge | index_name,index_type,keyspace_name,status,table_name | No HELP text is exported for this series. |
| `ferrosa_secondary_indexes_last_build_ms` | gauge | index_name,index_type,keyspace_name,status,table_name | No HELP text is exported for this series. |
| `ferrosa_secondary_indexes_pending_bytes` | gauge | index_name,index_type,keyspace_name,status,table_name | No HELP text is exported for this series. |
| `ferrosa_secondary_indexes_pending_sstable_count` | gauge | index_name,index_type,keyspace_name,status,table_name | No HELP text is exported for this series. |
| `ferrosa_secondary_indexes_total_builds` | gauge | index_name,index_type,keyspace_name,status,table_name | No HELP text is exported for this series. |
| `ferrosa_spill_pool_forced_total` | counter | - | Rows admitted over the limit within an operator's forward-progress floor. |
| `ferrosa_spill_pool_limit_bytes` | gauge | - | Node-wide retained bytes all spilling operators may hold in memory together. |
| `ferrosa_spill_pool_operators` | gauge | - | Spilling operators currently holding a pool charge. |
| `ferrosa_spill_pool_refused_total` | counter | - | Charges refused because the pool was full. |
| `ferrosa_spill_pool_used_bytes` | gauge | - | Retained bytes currently held in memory by spilling operators. |
| `ferrosa_sstable_dio_align_probe_fallbacks_total` | counter | - | Files where the STATX_DIOALIGN probe (T-031) could not report an alignment and MIN_BLOCK was used instead; non-zero is expected on old kernels/filesystems and is not itself an error. |
| `ferrosa_sstable_direct_read_bytes_total` | counter | - | Logical bytes returned by the direct reader since start. |
| `ferrosa_sstable_direct_read_fallbacks_total` | counter | - | Compaction input files where O_DIRECT was rejected and the reader fell back to buffered reads; non-zero means the page-cache bypass is INACTIVE for those files and should alert. |
| `ferrosa_sstable_direct_read_files_total` | counter | - | Files opened through the direct reader since start. |
| `ferrosa_sstable_direct_write_bytes_total` | counter | - | Logical bytes written through the direct writer since start. |
| `ferrosa_sstable_direct_write_fallbacks_total` | counter | - | Data.db files where O_DIRECT was rejected and the writer fell back to buffered I/O; non-zero means the page-cache-bypass mitigation is INACTIVE for those files and should alert. |
| `ferrosa_sstable_direct_write_files_total` | counter | - | Immutable files completed through the direct writer since start. |
| `ferrosa_sstable_write_pump_aborts_total` | counter | - | Pump waits that returned Err(Aborted) since start. |
| `ferrosa_sstable_write_pump_blocked_seconds_total` | counter | stage | Seconds producers spent blocked waiting for a resource, by stage. |
| `ferrosa_sstable_write_pump_inflight_segments` | gauge | - | Segments currently handed to a flusher and not yet returned; bounded by the pump's configured depth. |
| `ferrosa_sstable_write_pump_stalls_total` | counter | - | "flusher stalled" WARN edges logged since start (one per stall, not one per second). |
| `ferrosa_sstable_write_pump_sync_fallbacks_total` | counter | - | Pumps that asked for depth >= 1 but fell back to synchronous (depth 0) because the flusher thread failed to spawn; non-zero means the process is thread-starved. |
| `ferrosa_storage_compaction_cancel_latency_seconds_count_total` | counter | - | Observations of compaction cancel latency. |
| `ferrosa_storage_compaction_cancel_latency_seconds_max` | gauge | - | Maximum observed compaction cancel latency. |
| `ferrosa_storage_compaction_cancel_latency_seconds_sum_total` | counter | - | Total latency from CancelToken::cancel() to the checkpoint that observed it. |
| `ferrosa_storage_compaction_cancelled_total` | counter | - | Compaction tasks that returned Err because their CancelToken was cancelled. |
| `ferrosa_storage_compaction_completed_total` | counter | - | Compaction tasks completed successfully. |
| `ferrosa_storage_compaction_failed_total` | counter | - | Compaction tasks that failed in executor workers. |
| `ferrosa_storage_compaction_input_bytes_total` | counter | - | Input bytes read by completed compactions. |
| `ferrosa_storage_compaction_input_rows_total` | counter | - | Input rows seen by completed compactions. |
| `ferrosa_storage_compaction_last_input_bytes` | gauge | - | Input bytes read by the most recent successful compaction. |
| `ferrosa_storage_compaction_last_input_rows` | gauge | - | Input rows seen by the most recent successful compaction. |
| `ferrosa_storage_compaction_last_output_bytes` | gauge | - | Output bytes written by the most recent successful compaction. |
| `ferrosa_storage_compaction_last_output_partitions` | gauge | - | Output partitions emitted by the most recent successful compaction. |
| `ferrosa_storage_compaction_last_output_rows` | gauge | - | Output rows emitted by the most recent successful compaction. |
| `ferrosa_storage_compaction_output_bytes_total` | counter | - | Output bytes written by completed compactions. |
| `ferrosa_storage_compaction_output_partitions_total` | counter | - | Output partitions emitted by completed compactions. |
| `ferrosa_storage_compaction_output_rows_total` | counter | - | Output rows emitted by completed compactions. |
| `ferrosa_storage_compaction_panics_total` | counter | - | Compaction tasks that panicked; each was failed, its input claims released and its worker kept running. |
| `ferrosa_storage_compaction_paused_tables` | gauge | - | Tables paused after repeated output digest or verification failures. |
| `ferrosa_storage_compaction_phase_seconds_max` | gauge | phase | Maximum observed wall time for a compaction phase. |
| `ferrosa_storage_compaction_phase_seconds_total` | counter | phase | Total wall time spent in compaction phases. |
| `ferrosa_storage_compaction_phase_total` | counter | phase | Number of observations for compaction phases. |
| `ferrosa_storage_compaction_planning_deferred_total` | counter | - | Planning rounds skipped because the compaction pipeline was already saturated (see FERROSA_COMPACTION_BACKPRESSURE_PRESSURE). |
| `ferrosa_storage_compaction_pool_input_opens_total` | counter | - | Compaction input readers obtained via the engine-wide reader pool (FMEA #11). |
| `ferrosa_storage_compaction_purge_held_back_total` | counter | - | Compactions that purged every partition and wrote one anyway so the output was non-empty. |
| `ferrosa_storage_compaction_purge_policy_errors_total` | counter | - | Compactions that skipped tombstone purging because a table's gc_grace_seconds was unreadable; non-zero means a corrupt table option. |
| `ferrosa_storage_compaction_purged_markers_total` | counter | - | Deletion markers dropped by compaction after gc_grace_seconds. |
| `ferrosa_storage_compaction_queue_depth` | gauge | - | Compaction tasks waiting in executor queues. |
| `ferrosa_storage_compaction_queue_depth_max` | gauge | - | Maximum observed compaction executor queue depth. |
| `ferrosa_storage_compaction_retire_failures_total` | counter | - | Input retirement failures retained for reconciliation. |
| `ferrosa_storage_compaction_running` | gauge | - | Compaction tasks currently running. |
| `ferrosa_storage_compaction_running_max` | gauge | - | Maximum observed concurrent compaction tasks. |
| `ferrosa_storage_compaction_skipped_overlap_total` | counter | - | Compaction tasks skipped because an input SSTable was already in flight. |
| `ferrosa_storage_compaction_started_total` | counter | - | Compaction tasks started by executor workers. |
| `ferrosa_storage_compaction_submitted_total` | counter | - | Compaction tasks submitted to the executor. |
| `ferrosa_storage_eviction_audit_last_disk_bytes` | gauge | - | Bytes those candidates occupied on disk in the latest audited pass. |
| `ferrosa_storage_eviction_audit_last_evicted_bytes` | gauge | - | Bytes evicted by the latest audited pass. |
| `ferrosa_storage_eviction_audit_last_evicted_generations` | gauge | - | Generations evicted by the latest audited pass. |
| `ferrosa_storage_eviction_audit_last_manifest_bytes` | gauge | - | Bytes the manifest claimed for the eviction candidates in the latest audited pass. |
| `ferrosa_storage_eviction_audit_last_trigger` | gauge | - | Trigger of the latest audited pass: 0 cache_cap, 1 free_space, 2 both, 3 recovered. |
| `ferrosa_storage_eviction_audit_last_unix_ms` | gauge | - | Time of the latest audited eviction pass. |
| `ferrosa_storage_eviction_audit_offload_failures_total` | counter | - | Audit segment offload attempts that failed. |
| `ferrosa_storage_eviction_audit_offload_uploaded_total` | counter | - | Audit segments uploaded to S3 and removed locally. |
| `ferrosa_storage_eviction_audit_passes_total` | counter | - | Eviction passes with pressure that were audited. |
| `ferrosa_storage_eviction_audit_refused_not_in_store_total` | counter | - | Evictions refused because the object store lacks the generation the manifest lists. |
| `ferrosa_storage_eviction_audit_write_failures_total` | counter | - | Eviction audit records that could not be written. |
| `ferrosa_storage_flush_bytes_total` | counter | - | Bytes emitted by completed memtable flushes. |
| `ferrosa_storage_flush_last_bytes` | gauge | - | Bytes emitted by the most recent completed memtable flush. |
| `ferrosa_storage_flush_last_partitions` | gauge | - | Partitions emitted by the most recent completed memtable flush. |
| `ferrosa_storage_flush_last_rows` | gauge | - | Rows emitted by the most recent completed memtable flush. |
| `ferrosa_storage_flush_partitions_total` | counter | - | Partitions emitted by completed memtable flushes. |
| `ferrosa_storage_flush_phase_seconds_total` | counter | phase | Total wall time spent in memtable flush phases. |
| `ferrosa_storage_flush_phase_total` | counter | phase | Number of observations for memtable flush phases. |
| `ferrosa_storage_flush_rows_total` | counter | - | Rows emitted by completed memtable flushes. |
| `ferrosa_storage_flushes_total` | counter | - | Memtable flushes completed. |
| `ferrosa_storage_index_reload_skipped_rows_total` | counter | - | Unresolvable system_schema.indexes rows skipped during index reload (dangling registrations; clean up with DROP INDEX IF EXISTS). |
| `ferrosa_storage_index_sidecar_mapped_bytes` | gauge | - | Bytes of scalar index sidecars memory-mapped (reclaimable page cache, not heap). |
| `ferrosa_storage_index_sidecar_mapped_files` | gauge | - | Scalar index sidecar files memory-mapped. |
| `ferrosa_storage_legacy_ns_timestamps_normalised_total` | counter | source | Legacy nanosecond cell timestamps (pre-t_cf637b6e Accord writes) read as microseconds, by where they were found. Zero on every node after compaction means the compatibility shim can be removed. |
| `ferrosa_storage_memtable_backpressure_bytes` | gauge | - | Configured hard memtable write backpressure threshold. |
| `ferrosa_storage_memtable_flush_threshold_bytes` | gauge | - | Configured memtable flush request threshold. |
| `ferrosa_storage_memtable_size_bytes_max` | gauge | - | Maximum observed active memtable size across write admission checks. |
| `ferrosa_storage_observer_backpressure_total` | counter | - | Async-observer entries derived inline on the write path because the observer's queue was full; the entry was produced, the writer paid for it. |
| `ferrosa_storage_observer_queue_capacity` | gauge | - | Total bound of the async-observer queues (sum over registered observers); depth / capacity is the saturation ratio. |
| `ferrosa_storage_observer_queue_depth` | gauge | - | Mutations staged in async-observer queues now; snapshot, read alongside the capacity below. |
| `ferrosa_storage_pending_upload_ledger_corrupt_entries_total` | counter | - | Pending-upload ledger entries found damaged (unparseable, misfiled, or NUL-prefixed) and quarantined or salvaged. |
| `ferrosa_storage_pending_upload_unreplayable_total` | counter | - | Pending uploads quarantined at replay because their files were neither on local disk nor in the S3 manifest; non-zero means lost or untracked SSTables. |
| `ferrosa_storage_range_read_truncated_total` | counter | - | Capped range reads that hit their cap with more data available and failed loud instead of truncating. |
| `ferrosa_storage_read_limited_rows_flushing_hits_total` | counter | - | Flushing memtable hits observed by read_limited_rows. |
| `ferrosa_storage_read_limited_rows_found_total` | counter | - | Partition read_limited_rows calls that found at least one source. |
| `ferrosa_storage_read_limited_rows_memtable_hits_total` | counter | - | Active memtable hits observed by read_limited_rows. |
| `ferrosa_storage_read_limited_rows_seconds_max` | gauge | - | Maximum observed wall time for read_limited_rows. |
| `ferrosa_storage_read_limited_rows_seconds_total` | counter | - | Total wall time spent in read_limited_rows. |
| `ferrosa_storage_read_limited_rows_sstable_errors_total` | counter | - | SSTable read errors observed by read_limited_rows. |
| `ferrosa_storage_read_limited_rows_sstable_hits_total` | counter | - | SSTable hits observed by read_limited_rows. |
| `ferrosa_storage_read_limited_rows_sstable_probes_total` | counter | - | SSTable probes issued by read_limited_rows. |
| `ferrosa_storage_read_limited_rows_sstable_pruned_total` | counter | - | SSTables skipped before index lookup by read_limited_rows. |
| `ferrosa_storage_read_limited_rows_total` | counter | - | Partition read_limited_rows calls. |
| `ferrosa_storage_read_sstable_fanout_max` | gauge | - | Maximum SSTable descriptor fanout observed for one partition-read attempt. |
| `ferrosa_storage_read_sstable_high_fanout_total` | counter | - | Partition-read attempts whose SSTable descriptor fanout exceeded the operational threshold. |
| `ferrosa_storage_sstable_publication_refused_total` | counter | reason | Staged SSTable generations refused publication and quarantined instead of promoted. |
| `ferrosa_storage_sstable_rehydration_bytes_total` | counter | - | Bytes restored by SSTable read-through rehydration. |
| `ferrosa_storage_sstable_rehydration_components_total` | counter | - | SSTable components restored by read-through rehydration. |
| `ferrosa_storage_sstable_rehydration_failure_total` | counter | - | SSTable read-through rehydrations that failed. |
| `ferrosa_storage_sstable_rehydration_in_flight` | gauge | - | SSTable generations currently being restored by read-through rehydration. |
| `ferrosa_storage_sstable_rehydration_in_flight_max` | gauge | - | Maximum concurrent SSTable read-through rehydrations. |
| `ferrosa_storage_sstable_rehydration_requests_total` | counter | - | SSTable read-through rehydration attempts. |
| `ferrosa_storage_sstable_rehydration_seconds_max` | gauge | - | Maximum observed wall time for one SSTable read-through rehydration. |
| `ferrosa_storage_sstable_rehydration_seconds_total` | counter | - | Total wall time spent in SSTable read-through rehydration. |
| `ferrosa_storage_sstable_rehydration_success_total` | counter | - | SSTable read-through rehydrations that restored at least one component or found the requested component present. |
| `ferrosa_storage_stats_compressed_sstable_count` | gauge | keyspace,table_name | No HELP text is exported for this series. |
| `ferrosa_storage_stats_local_cache_max_bytes` | gauge | keyspace,table_name | No HELP text is exported for this series. |
| `ferrosa_storage_stats_local_sstable_cache_bytes` | gauge | keyspace,table_name | No HELP text is exported for this series. |
| `ferrosa_storage_stats_local_sstable_component_count` | gauge | keyspace,table_name | No HELP text is exported for this series. |
| `ferrosa_storage_stats_memtable_count` | gauge | keyspace,table_name | No HELP text is exported for this series. |
| `ferrosa_storage_stats_memtable_size_bytes` | gauge | keyspace,table_name | No HELP text is exported for this series. |
| `ferrosa_storage_stats_pending_compactions` | gauge | keyspace,table_name | No HELP text is exported for this series. |
| `ferrosa_storage_stats_s3_bytes` | gauge | keyspace,table_name | No HELP text is exported for this series. |
| `ferrosa_storage_stats_s3_object_count` | gauge | keyspace,table_name | No HELP text is exported for this series. |
| `ferrosa_storage_stats_sstable_count` | gauge | keyspace,table_name | No HELP text is exported for this series. |
| `ferrosa_storage_stats_sstable_size_bytes` | gauge | keyspace,table_name | No HELP text is exported for this series. |
| `ferrosa_storage_stats_uncompressed_sstable_count` | gauge | keyspace,table_name | No HELP text is exported for this series. |
| `ferrosa_storage_upload_bytes_total` | counter | - | SSTable component bytes uploaded. |
| `ferrosa_storage_upload_files_total` | counter | - | SSTable component files uploaded. |
| `ferrosa_storage_upload_phase_seconds_total` | counter | phase | Total wall time spent in upload and S3 sync phases. |
| `ferrosa_storage_upload_phase_total` | counter | phase | Number of observations for upload and S3 sync phases. |
| `ferrosa_storage_upload_queue_depth` | gauge | - | Upload tasks currently queued or in progress. |
| `ferrosa_storage_upload_queue_depth_max` | gauge | - | Maximum observed upload queue depth. |
| `ferrosa_storage_upload_task_panics_total` | counter | - | Object-store upload/delete tasks that panicked; the task's caller was told it failed and its worker kept running. |
| `ferrosa_storage_upload_tasks_total` | counter | - | Upload tasks processed by the upload worker. |
| `ferrosa_storage_vector_generation_repair_failures_total` | counter | - | Vector sidecar rebuilds of one generation that failed (ANN over that index refuses until one succeeds). |
| `ferrosa_storage_vector_generations_pending` | gauge | - | Generations awaiting a vector sidecar rebuild; ANN over their index refuses (retryable) meanwhile. |
| `ferrosa_storage_vector_generations_repaired_total` | counter | - | Generations whose vector sidecars were rebuilt from their rows (compacted without them, flushed before manifests, or a crashed build). |
| `ferrosa_storage_write_admission_delay_seconds_bucket` | histogram series | le | Soft-pressure wait duration. |
| `ferrosa_storage_write_admission_delay_seconds_count` | histogram series | - | Soft-pressure wait duration. |
| `ferrosa_storage_write_admission_delay_seconds_sum` | histogram series | - | Soft-pressure wait duration. |
| `ferrosa_storage_write_admission_delayed_total` | counter | - | CQL writes delayed in the soft-pressure zone. |
| `ferrosa_storage_write_admission_rejected_total` | counter | reason | Writes rejected at hard pressure by reason. |
| `ferrosa_storage_write_failures_by_reason_total` | counter | reason | StorageEngine::write failures partitioned by the admission or write phase that failed. |
| `ferrosa_storage_write_failures_total` | counter | - | StorageEngine::write calls that returned an error. |
| `ferrosa_storage_write_inline_flush_total` | counter | - | StorageEngine::write calls that synchronously ran a memtable flush. |
| `ferrosa_storage_write_phase_seconds_max` | gauge | phase | Maximum observed wall time for a StorageEngine::write phase. |
| `ferrosa_storage_write_phase_seconds_total` | counter | phase | Total wall time spent in StorageEngine::write phases. |
| `ferrosa_storage_write_phase_total` | counter | phase | Number of observations for StorageEngine::write phases. |
| `ferrosa_storage_write_pressure_ratio` | gauge | table | Per-table maximum of memtable and write-pump pressure, quantized to percentage points. |
| `ferrosa_storage_writes_total` | counter | - | StorageEngine::write calls completed successfully. |
| `ferrosa_supervised_task_failures_total` | counter | kind,task | Failures of a supervised background task by kind. |
| `ferrosa_supervised_task_restarts_total` | counter | task | Restarts of a supervised background task after a crash or stall. |
| `ferrosa_supervised_task_up` | gauge | task | 1 when a supervised background task is healthy, 0 while it is failing, stalled or restarting. |
| `ferrosa_table_access_summary_full_scans` | gauge | keyspace,table_name | No HELP text is exported for this series. |
| `ferrosa_table_access_summary_point_lookups` | gauge | keyspace,table_name | No HELP text is exported for this series. |
| `ferrosa_table_access_summary_range_scans` | gauge | keyspace,table_name | No HELP text is exported for this series. |
| `ferrosa_table_access_summary_reads` | gauge | keyspace,table_name | No HELP text is exported for this series. |
| `ferrosa_table_access_summary_writes` | gauge | keyspace,table_name | No HELP text is exported for this series. |
| `ferrosa_up` | gauge | - | Whether the Ferrosa node is up (always 1 when reachable). |
| `ferrosa_virtual_tables_registered` | gauge | - | Number of registered observability virtual tables. |
| `write_pump_bytes_total` | counter | component | Successful physical component bytes submitted, including padding and header rewrites. |
| `write_pump_files_total` | counter | component,mode | SSTable component pumps opened by component and I/O mode. |
| `write_pump_writes_total` | counter | component | Successful component sink write requests, with vectored batches counted once. |
