# Pyroscope Configuration Parameters

Reference for Pyroscope server configuration. Source: [Grafana Pyroscope docs](https://grafana.com/docs/pyroscope/latest/configure-server/reference-configuration-parameters/).

## How Configuration Works

Pyroscope is configured via a YAML file (`-config.file` flag) or CLI flags. CLI flags take precedence over YAML values. Environment variables can be used with `-config.expand-env=true` using `${VAR}` or `${VAR:-default}` syntax.

View current config at the `/config` HTTP API endpoint.

## Key Configuration Sections

### Top-Level

```yaml
# Modules to load. 'all' enables single-binary mode.
[target: <string> | default = "all"]

api:
  [base-url: <string> | default = ""]
```

### Server

HTTP on port **4040** (default), gRPC on port **9095** (default).

```yaml
server:
  [http_listen_address: <string> | default = ""]
  [http_listen_port: <int> | default = 4040]
  [grpc_listen_port: <int> | default = 9095]
  [graceful_shutdown_timeout: <duration> | default = 30s]
  [http_server_read_timeout: <duration> | default = 30s]
  [http_server_write_timeout: <duration> | default = 30s]
  [http_server_idle_timeout: <duration> | default = 2m]
  [log_format: <string> | default = "logfmt"]  # logfmt or json
  [log_level: <string> | default = "info"]      # debug, info, warn, error
  [grpc_server_max_recv_msg_size: <int> | default = 4194304]
  [grpc_server_max_send_msg_size: <int> | default = 4194304]
  [grpc_server_max_concurrent_streams: <int> | default = 100]
```

### PyroscopeDB (Local Storage)

```yaml
pyroscopedb:
  # Directory for local storage
  [data_path: <string> | default = "./data"]
  # Max block duration
  [max_block_duration: <duration> | default = 1h]
  # Row group target size (uncompressed)
  [row_group_target_size: <int> | default = 1342177280]
  # Partition label for symbols
  [symbols_partition_label: <string> | default = ""]
  # Disk retention: minimum free disk (GiB)
  [min_free_disk_gb: <int> | default = 10]
  # Disk retention: minimum free percentage
  [min_disk_available_percentage: <float> | default = 0.05]
  # How often to enforce retention
  [enforcement_interval: <duration> | default = 5m]
  # Disable retention enforcement
  [disable_enforcement: <boolean> | default = false]
```

### Storage (Object Storage Backend)

Supported backends: `s3`, `gcs`, `azure`, `swift`, `filesystem`, `cos`.

```yaml
storage:
  [backend: <string> | default = ""]
  [prefix: <string> | default = ""]

  s3:
    [endpoint: <string> | default = ""]
    [region: <string> | default = ""]
    [bucket_name: <string> | default = ""]
    [secret_access_key: <string> | default = ""]
    [access_key_id: <string> | default = ""]
    [insecure: <boolean> | default = false]
    [signature_version: <string> | default = "v4"]
    [bucket_lookup_type: <string> | default = "auto"]
    # Use AWS SDK default auth (env vars, config files, IMDS)
    [native_aws_auth_enabled: <boolean> | default = false]
    sse:
      [type: <string> | default = ""]           # SSE-KMS or SSE-S3
      [kms_key_id: <string> | default = ""]
      [kms_encryption_context: <string> | default = ""]

  gcs:
    [bucket_name: <string> | default = ""]
    [service_account: <string> | default = ""]

  azure:
    [account_name: <string> | default = ""]
    [account_key: <string> | default = ""]
    [container_name: <string> | default = ""]

  filesystem:
    [dir: <string> | default = "./data-shared"]
```

### Distributor

```yaml
distributor:
  [pushtimeout: <duration> | default = 5s]
  ring:
    kvstore:
      [store: <string> | default = "memberlist"]  # consul, etcd, inmemory, memberlist, multi
```

### Ingester

```yaml
ingester:
  lifecycler:
    ring:
      kvstore:
        [store: <string> | default = "consul"]
      [heartbeat_timeout: <duration> | default = 1m]
      [replication_factor: <int> | default = 1]
    [num_tokens: <int> | default = 128]
    [heartbeat_period: <duration> | default = 5s]
```

### Querier

```yaml
querier:
  # Time after which queries go to storage instead of ingesters
  [query_store_after: <duration> | default = 4h]
```

### Compactor

```yaml
compactor:
  [block_ranges: <list of durations> | default = 1h0m0s,2h0m0s,8h0m0s]
  [data_dir: <string> | default = "./data-compactor"]
  [compaction_interval: <duration> | default = 30m]
  [compaction_concurrency: <int> | default = 1]
  [deletion_delay: <duration> | default = 12h]
  [downsampler_enabled: <boolean> | default = false]
```

### Limits (Per-Tenant)

```yaml
limits:
  # Ingestion rate limit (MB/s)
  [ingestion_rate_mb: <float> | default = 4]
  [ingestion_burst_size_mb: <float> | default = 2]
  # Label constraints
  [max_label_name_length: <int> | default = 1024]
  [max_label_value_length: <int> | default = 2048]
  [max_label_names_per_series: <int> | default = 30]
  # Profile constraints
  [max_profile_size_bytes: <int> | default = 4194304]
  [max_profile_stacktrace_samples: <int> | default = 16000]
  [max_profile_stacktrace_depth: <int> | default = 1000]
  # Series limits
  [max_global_series_per_tenant: <int> | default = 5000]
  # Query limits
  [max_query_lookback: <duration> | default = 1w]
  [max_query_length: <duration> | default = 1d]
  [max_flamegraph_nodes_default: <int> | default = 8192]
  [max_flamegraph_nodes_max: <int> | default = 1048576]
  # Retention
  [compactor_blocks_retention_period: <duration> | default = 0s]
  # Ingestion time bounds
  [reject_older_than: <duration> | default = 1h]
  [reject_newer_than: <duration> | default = 10m]
  # Relabeling
  [ingestion_relabeling_rules: <list of Configs> | default = []]
  [sample_type_relabeling_rules: <list of Configs> | default = []]
```

### Self-Profiling

```yaml
self_profiling:
  # Disable push profiling in single-binary mode
  [disable_push: <boolean> | default = false]
  [mutex_profile_fraction: <int> | default = 5]
  [block_profile_rate: <int> | default = 5]
```

### Memberlist (Gossip)

```yaml
memberlist:
  [bind_port: <int> | default = 7946]
  [join_members: <list of strings> | default = []]
  [gossip_interval: <duration> | default = 200ms]
  [gossip_nodes: <int> | default = 3]
  [leave_timeout: <duration> | default = 20s]
```

### Tracing

```yaml
tracing:
  [enabled: <boolean> | default = true]
```

### Multi-Tenancy

```yaml
# Require X-Scope-OrgId header; false = use "anonymous" tenant
[multitenancy_enabled: <boolean> | default = false]
```

### Embedded Grafana

```yaml
embedded_grafana:
  [data_path: <string> | default = "./data/__embedded_grafana/"]
  [listen_port: <int> | default = 4041]
  [pyroscope_url: <string> | default = "http://localhost:4040"]
```

## Port Summary

| Service | Port | Protocol |
|---------|------|----------|
| HTTP API | 4040 | HTTP |
| gRPC | 9095 | gRPC |
| Memberlist gossip | 7946 | TCP/UDP |
| Embedded Grafana | 4041 | HTTP |

## Relevant to Our Deployment

Our Pyroscope deployment (`configuration/pyroscope/PyroscopeManifestBuilder.kt`) uses:
- **S3 backend** with `native_aws_auth_enabled: true` (IAM role via instance metadata)
- **Single-binary mode** (`target: all`)
- **Port 4040** for HTTP API
- **Local data path** at `/data` (mounted from host `/mnt/db1/pyroscope`)
- Profiles received from: Java agent (Cassandra, Spark), eBPF agent (all nodes), stress jobs
