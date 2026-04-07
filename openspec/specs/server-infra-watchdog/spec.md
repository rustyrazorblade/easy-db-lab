# Server Infrastructure Watchdog

A background service that monitors whether the cluster's AWS VPC still exists while the server is running, and triggers a clean shutdown if the infrastructure has been removed.

## Requirements

### Requirement: Watchdog monitors VPC existence
The watchdog service SHALL periodically check whether the cluster's VPC still exists in AWS and trigger server shutdown when it is no longer found.

#### Scenario: VPC is present
- **WHEN** the watchdog polls AWS and the cluster VPC is found
- **THEN** the server continues running normally

#### Scenario: VPC is gone
- **WHEN** the watchdog polls AWS and the cluster VPC is not found
- **THEN** the server emits an `Event.Server.InfrastructureGone` event and exits cleanly

#### Scenario: AWS API error during check
- **WHEN** the watchdog poll encounters an AWS API exception (not a not-found result)
- **THEN** the exception is logged, the watchdog continues polling on the next interval, and the server does not shut down

### Requirement: VPC ID not available
The watchdog SHALL gracefully handle the case where no VPC ID is recorded in cluster state.

#### Scenario: No VPC ID in cluster state
- **WHEN** the watchdog starts and `ClusterState.vpcId` is null or blank
- **THEN** the watchdog logs a warning and skips all polling without triggering shutdown

### Requirement: Check runs on status refresh cycle
The VPC existence check SHALL run on the same cadence as the existing status cache refresh (`--refresh` interval).

#### Scenario: Check frequency matches refresh
- **WHEN** the server is running with `--auto-shutdown` and `--refresh 30`
- **THEN** the VPC check runs every 30 seconds alongside the status cache refresh
