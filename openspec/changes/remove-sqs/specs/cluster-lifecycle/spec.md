## REMOVED Requirements

### Requirement: SQS queue lifecycle during provisioning
**Reason**: SQS-based log ingestion pipeline is removed. S3 bucket notification configuration was unreliable and blocked cluster provisioning.
**Migration**: No replacement. EMR logs remain in S3 for direct access.
