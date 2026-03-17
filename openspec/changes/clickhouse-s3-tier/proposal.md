# S3 Tier Support for ClickHouse

## Problem

ClickHouse supports two existing storage policies:
- `local`: data stored on local NVMe only
- `s3_main`: data stored entirely in S3 with a local cache layer

There is no policy for a tiered approach where local NVMe is used as a fast hot tier and S3 acts as overflow cold storage. This is useful for workloads that are write-heavy or have a large working set that exceeds local disk capacity, but where the most recent data must be served quickly from local storage.

## Solution

Add a new `s3_tier` storage policy to the ClickHouse configuration. The `s3_tier` policy defines two volumes:
- `hot`: local NVMe disk
- `cold`: S3 with local cache

ClickHouse automatically migrates data from hot to cold when local disk usage reaches a configurable threshold, controlled by `move_factor` (fraction of free space remaining that triggers migration).

## User-facing changes

- New `--s3-tier-move-factor` option on `clickhouse init` (default: `0.2`, meaning move when 80% full)
- Users can create tables with `SETTINGS storage_policy = 's3_tier'`
- Config saved event now shows the move factor
