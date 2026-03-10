---
command: "/test-workflow"
category: "Testing & Demonstration"
purpose: "End-to-end Cassandra cluster workflow demonstration using server tools"
---

# Cassandra Cluster Test Workflow

Execute a complete end-to-end workflow demonstrating easy-db-lab capabilities using only server tools.

## Workflow Steps

Execute the following steps in order, using MCP tools exclusively:

### 1. Initialize Cluster Configuration
Use `mcp__easy-db-lab__init` to create a new cluster configuration:
- **Name**: "test"
- **Cassandra instances**: 1
- **Stress instances**: 1
- **Instance type**: "t3.medium" (cheap and sufficient for testing)
- **Stress instance type**: "t3.small"
- **Architecture**: "amd64"
- **Start automatically**: false (we'll start manually to show the process)

### 2. Provision Infrastructure
Use `mcp__easy-db-lab__up` to provision the AWS infrastructure:
- This creates EC2 instances, networking, security groups
- Wait for the operation to complete
- Report the public IPs of provisioned instances

### 3. Configure Cassandra Version
Use `mcp__easy-db-lab__use` to set Cassandra version:
- **Version**: "5.0"
- **Java version**: Leave default (auto-selected)
- This installs Cassandra 5.0 without custom configuration changes

### 4. Start the Cluster
Use `mcp__easy-db-lab__start` to start all services:
- Starts Cassandra on database nodes
- Starts cassandra-easy-stress on stress nodes
- Starts monitoring and servers on control nodes
- Report when services are ready

### 5. Get Cassandra Host IP
Use `mcp__easy-db-lab__hosts` to retrieve host information:
- Set `cassandra=true` to get Cassandra hosts in CSV format
- Extract the private IP of the first Cassandra node for stress testing

### 6. Wait for Cluster Readiness
Wait ~30 seconds for Cassandra to fully initialize and be ready to accept connections.

### 7. Check Server Status
Use `mcp__easy-db-lab__get_server_status` to check if servers are running:
- Verify easy-cass-mcp is accessible
- Verify cassandra-easy-stress is accessible
- If any are disconnected, report but continue (manual reconnection may be needed)

### 8. Run Stress Test
Use `mcp__cassandra-easy-stress__run` to execute a KeyValue workload:
- **Profile**: "KeyValue"
- **Duration**: 30 seconds
- **Host**: Use the private IP from step 5
- **Rate**: Default (let the tool decide)
- Monitor the stress test progress and report results

### 9. Collect Thread Pool Statistics
Use `mcp__easy-cass-mcp__query_system_table` to gather thread pool stats:
- **Table**: "system_views.thread_pools" or similar
- Report key metrics: active threads, pending tasks, completed tasks
- Show the current thread pool utilization

### 10. Shutdown Cluster
Use `mcp__easy-db-lab__down` to terminate the cluster:
- **Auto-approve**: true (skip confirmation)
- Clean up all AWS resources
- Confirm shutdown completed successfully

## Error Handling

- If any step fails, report the error clearly
- For MCP connection issues, provide guidance on reconnecting servers
- If stress test fails, check Cassandra readiness and retry once
- Always attempt cleanup (step 10) even if earlier steps fail

## Output Format

Provide clear status updates at each step:
- ✅ Step completed successfully
- 🔄 Step in progress
- ⚠️  Warning or non-critical issue
- ❌ Error occurred

Include timing information and key metrics from each operation.
