# Server

`easy-db-lab server` runs a REST status server for the cluster in the current workspace.  It serves cluster status as JSON and API docs, and it refreshes the status in the background.

For live metrics on a Redis channel, use [`metrics stream`](metrics-stream.md).

## Starting the Server

```bash
easy-db-lab server
```

By default, the server binds `127.0.0.1` and picks a free port.  When it is ready, it prints the status and API docs URLs:

```
Status server ready.
Cluster status: http://127.0.0.1:53211/status
API docs:       http://127.0.0.1:53211/swagger
```

| Option | Description | Default |
|--------|-------------|---------|
| `--port`, `-p` | Port to bind (0 = any free port) | 0 |
| `--bind`, `-b` | Address to bind | `127.0.0.1` |
| `--refresh`, `-r` | Status refresh interval, in seconds | 30 |
| `--auto-shutdown` | Shut down when the cluster VPC is removed | off |

## Endpoints

| Endpoint | Description |
|----------|-------------|
| `GET /status` | The full cluster status as JSON |
| `GET /status?section=<name>` | One section of the status |
| `GET /status?live=true` | Refresh the status before answering |
| `GET /stress/status` | The status of the stress jobs |
| `GET /swagger` | The API docs |

The sections are `cluster`, `nodes`, `networking`, `securityGroup`, `spark`, `opensearch`, `s3`, `kubernetes`, `stressJobs`, `cassandraVersion` and `accessInfo`.  An unknown section answers 400.  Until the first refresh completes, `/status` answers 503.

The `accessInfo.observability` object holds the URLs of Grafana, Mimir, Loki, Tempo and Pyroscope on the control node.

## Auto-Shutdown on Infrastructure Removal

```bash
easy-db-lab server --auto-shutdown
```

With `--auto-shutdown`, the server checks on each refresh whether the cluster VPC still exists.  When the VPC is gone, it emits a shutdown event and exits with status 0.  Use it when an automated workflow tears the cluster down, or when you leave the server running after `easy-db-lab down`.

The check is skipped when no cluster state exists.  AWS API errors during the check are logged and ignored; only a confirmed "VPC not found" result shuts the server down.

## Notes

- Your AWS profile must be configured (`easy-db-lab profile setup`).
- The server runs in the foreground until you stop it with Ctrl+C.
