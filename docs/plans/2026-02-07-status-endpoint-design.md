# Status Endpoint Design

## Overview

Add an HTTP endpoint to the MCP server (`easy-db-lab server`) that serves environment status as JSON. This mirrors what the `status` CLI command displays, but as a structured document suitable for programmatic consumption.

A background thread periodically fetches live data (EC2 states, security group rules, Cassandra version, etc.) and caches it in memory. The endpoint always reads from this cache for instant responses.

## Endpoint

```
GET /status                        → full JSON, from background cache
GET /status?live=true              → forces immediate refresh, then returns
GET /status?section=nodes          → single section from cache
GET /status?section=nodes&live=true → single section, forced refresh
```

### Server Option

```
--refresh, -r   Background refresh interval in seconds (default: 30)
```

## Background Refresh Architecture

- On server startup, immediately fetch all status data, then schedule recurring refreshes via Kotlin `Timer`
- Background thread acquires an exclusive `ReentrantLock` during fetch
- `?live=true` also acquires the lock, performs the fetch, and resets the timer
- Timer resets after every fetch (scheduled or forced), so the interval is always "N seconds since last completed fetch"
- The lock prevents concurrent fetches (scheduled vs on-demand)

### Data Flow

```
Server startup
  └─→ immediate fetch → cache populated
  └─→ Timer scheduled (every --refresh seconds)

Timer fires
  └─→ acquire lock
  └─→ fetch all sections (EC2, SG, K8s, SSH, EMR, OpenSearch)
  └─→ update in-memory cache
  └─→ release lock
  └─→ reschedule timer

GET /status
  └─→ read from cache (no lock needed, fast)

GET /status?live=true
  └─→ acquire lock
  └─→ cancel pending timer
  └─→ fetch all sections
  └─→ update cache
  └─→ release lock
  └─→ reschedule timer
  └─→ return fresh data
```

## JSON Structure

### Valid `section` values

`cluster`, `nodes`, `networking`, `securityGroup`, `spark`, `opensearch`, `s3`, `kubernetes`, `cassandraVersion`, `accessInfo`

### Full response

```json
{
  "cluster": {
    "clusterId": "uuid-string",
    "name": "my-cluster",
    "createdAt": "2025-01-15T10:30:00Z",
    "infrastructureStatus": "UP"
  },
  "nodes": {
    "database": [
      {
        "alias": "db0",
        "instanceId": "i-abc123",
        "publicIp": "54.1.2.3",
        "privateIp": "10.0.1.100",
        "availabilityZone": "us-west-2a",
        "state": "RUNNING"
      }
    ],
    "app": [],
    "control": [
      {
        "alias": "control0",
        "instanceId": "i-def456",
        "publicIp": "54.1.2.4",
        "privateIp": "10.0.1.200",
        "availabilityZone": "us-west-2a",
        "state": "RUNNING"
      }
    ]
  },
  "networking": {
    "vpcId": "vpc-abc123",
    "internetGatewayId": "igw-def456",
    "subnetIds": ["subnet-111", "subnet-222"],
    "routeTableId": "rtb-789"
  },
  "securityGroup": {
    "securityGroupId": "sg-abc123",
    "name": "my-cluster-sg",
    "inboundRules": [
      {
        "protocol": "tcp",
        "fromPort": 9042,
        "toPort": 9042,
        "cidrBlocks": ["10.0.0.0/16"],
        "description": "CQL"
      }
    ],
    "outboundRules": [
      {
        "protocol": "-1",
        "fromPort": null,
        "toPort": null,
        "cidrBlocks": ["0.0.0.0/0"],
        "description": ""
      }
    ]
  },
  "spark": {
    "clusterId": "j-ABC123",
    "clusterName": "my-cluster-spark",
    "state": "WAITING",
    "masterPublicDns": "ec2-54-1-2-3.us-west-2.compute.amazonaws.com"
  },
  "opensearch": {
    "domainName": "my-cluster-os",
    "domainId": "123456/my-cluster-os",
    "state": "Active",
    "endpoint": "https://search-my-cluster-os.us-west-2.es.amazonaws.com",
    "dashboardsEndpoint": "https://search-my-cluster-os.us-west-2.es.amazonaws.com/_dashboards"
  },
  "s3": {
    "bucket": "my-cluster-bucket",
    "paths": {
      "cassandra": "s3://my-cluster-bucket/cassandra",
      "clickhouse": "s3://my-cluster-bucket/clickhouse",
      "spark": "s3://my-cluster-bucket/spark",
      "emrLogs": "s3://my-cluster-bucket/emr-logs"
    }
  },
  "kubernetes": {
    "pods": [
      {
        "namespace": "monitoring",
        "name": "grafana-5f8b9c7d4-x2k1p",
        "ready": "1/1",
        "status": "Running",
        "restarts": 0,
        "age": "2h"
      }
    ]
  },
  "cassandraVersion": {
    "version": "4.1.3",
    "source": "live"
  },
  "accessInfo": {
    "observability": {
      "grafana": "http://10.0.1.200:3000",
      "victoriaMetrics": "http://10.0.1.200:8428",
      "victoriaLogs": "http://10.0.1.200:9428"
    },
    "clickhouse": {
      "playUi": "http://10.0.1.100:8123/play",
      "httpInterface": "http://10.0.1.100:8123",
      "nativePort": "10.0.1.100:9000"
    },
    "s3Manager": {
      "ui": "http://10.0.1.200:8080/buckets/my-cluster-bucket"
    },
    "registry": {
      "endpoint": "10.0.1.200:5000"
    }
  }
}
```

### Nullability rules

- `cluster`, `nodes`: always present
- `networking`: null when no infrastructure configured
- `securityGroup`: null when no security group configured
- `spark`: null when no EMR cluster configured
- `opensearch`: null when no OpenSearch domain configured
- `s3`: null when no S3 bucket configured
- `kubernetes`: null when no control node or kubeconfig not found
- `cassandraVersion`: null when no Cassandra nodes configured
- `accessInfo`: null when no control node or K3s not initialized
- `accessInfo.clickhouse`: null when no ClickHouse pods are running
- `accessInfo.observability`/`s3Manager`/`registry`: present when K3s is initialized

### Conditional display rules

- `accessInfo.clickhouse`: only included when ClickHouse pods exist in the `clickhouse` namespace
- Cassandra-related info in `accessInfo`: only included when Cassandra nodes are in a running state

## Implementation Plan

### New files

- `src/main/kotlin/com/rustyrazorblade/easydblab/mcp/StatusCache.kt` - Background refresh logic, in-memory cache, lock management, Timer scheduling
- `src/main/kotlin/com/rustyrazorblade/easydblab/mcp/StatusResponse.kt` - Data classes for the JSON response sections
- `src/test/kotlin/com/rustyrazorblade/easydblab/mcp/StatusCacheTest.kt` - Tests for cache refresh, locking, section filtering

### Modified files

- `McpServer.kt` - Add `/status` HTTP endpoint, instantiate StatusCache, pass `--refresh` value
- `Server.kt` - Add `--refresh` CLI option

### Implementation steps

1. Create `StatusResponse.kt` with all data classes matching the JSON schema
2. Create `StatusCache.kt`:
   - Koin component that injects the same services as the Status command
   - `ReentrantLock` for exclusive fetch access
   - `Timer` with `scheduleAtFixedRate` replaced by single-shot `schedule` that reschedules after completion
   - `fun refresh()` - acquires lock, fetches all data, updates cache, reschedules timer
   - `fun getStatus(section: String? = null): String` - reads cache, optionally filters to one section, serializes to JSON
   - `fun forceRefresh()` - cancels pending timer, calls refresh, reschedules
3. Add `/status` GET endpoint in `McpServer.kt`
4. Add `--refresh` option to `Server.kt`, pass to McpServer
5. Write tests for StatusCache

### Testing approach

- Unit test `StatusCache` with mocked services (same pattern as Status command tests)
- Test that `getStatus()` returns valid JSON with all sections
- Test that `getStatus(section="nodes")` returns only the nodes section
- Test that `forceRefresh()` acquires the lock and updates the cache
- Test invalid section name returns an error
