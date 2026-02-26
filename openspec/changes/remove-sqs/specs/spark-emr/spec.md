## REMOVED Requirements

### Requirement: EMR log ingestion via SQS
**Reason**: The S3 -> SQS -> Vector pipeline was unreliable and blocked cluster provisioning. EMR logs remain accessible in S3 directly.
**Migration**: No automated log ingestion replacement. Users can access EMR logs in S3 via AWS console or CLI at the cluster's S3 prefix under `spark/emr-logs/`.
