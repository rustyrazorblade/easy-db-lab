# Proposal: ClickHouse S3 Tier Storage Policy

## Problem

ClickHouse supports two storage modes today:
- `local`: NVMe only
- `s3_main`: S3 with local cache (data lives primarily in S3)

There is no policy for a tiered approach where data starts on fast local NVMe and migrates to S3 only when local disk fills up. This is the classic "hot/cold" tiering pattern that maximises write speed while keeping storage costs low.

## Proposed Solution

Add a new `s3_tier` storage policy to ClickHouse with:
- **Hot volume**: local NVMe disk (`local` disk)
- **Cold volume**: S3 with local cache (`s3` disk)
- **Automatic migration**: controlled by `move_factor` — when local disk is `(1 - move_factor)` full, ClickHouse moves the oldest parts to the cold volume

The move factor is configurable via `--s3-tier-move-factor` on `clickhouse init` (default: `0.2`, meaning data moves when local disk reaches 80% capacity).

## Disk Rename

As part of this change, disk names are made more descriptive:
- `default` → `local` (explicit local disk definition)
- `s3_cache` → `s3` (the cache-over-S3 disk)

This makes storage policies self-documenting: `local` policy uses `local` disk, `s3_main` policy uses `s3` disk, `s3_tier` policy uses both.

## User Experience

```bash
# Use default move factor (0.2)
easy-db-lab clickhouse init

# Use custom move factor
easy-db-lab clickhouse init --s3-tier-move-factor 0.1

easy-db-lab clickhouse start

# Create a table with tiered storage
clickhouse-query "CREATE TABLE events ... SETTINGS storage_policy = 's3_tier'"
```
