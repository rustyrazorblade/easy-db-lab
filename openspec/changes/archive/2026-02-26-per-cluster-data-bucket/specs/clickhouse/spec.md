## MODIFIED Requirements

### REQ-CH-002: S3 Storage Integration

The system MUST use the per-cluster data bucket for ClickHouse S3 storage.

**Scenarios:**

- **GIVEN** a provisioned cluster, **WHEN** ClickHouse S3 storage is configured, **THEN** the S3 endpoint points to the per-cluster data bucket.
- **GIVEN** S3 cache options, **WHEN** the user enables S3 caching, **THEN** ClickHouse caches S3 data locally for faster reads.
