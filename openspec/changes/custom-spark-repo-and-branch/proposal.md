## Why

The `bulk-writer-*` Spark modules depend on `cassandra-analytics`, which is cloned from the Apache upstream and built locally. The source repo URL is hardcoded, making it impossible to build against a fork without manually editing the script. This blocks development on changes that require cassandra-analytics modifications (e.g., CASSANALYTICS-155: IAM role support for S3) until those changes land upstream.

## What Changes

- Add `spark/cassandra-analytics-source.properties` — a committed config file specifying the default repo URL and branch for cassandra-analytics builds.
- Update `bin/build-cassandra-analytics` to read defaults from this config file, with `--repo` and `--branch` CLI flags available as overrides.
- Print the resolved repo and branch at build time so it's clear which source is being used.

## Capabilities

### New Capabilities

- `cassandra-analytics-source`: Configuration of which cassandra-analytics Git repository and branch is cloned and built for the `bulk-writer-*` Spark modules.

### Modified Capabilities

- `spark-modules`: The build prerequisite for bulk-writer modules now supports a configurable upstream source rather than a hardcoded Apache repo URL.

## Impact

- `bin/build-cassandra-analytics` — add `--repo` flag, read from config file
- `spark/cassandra-analytics-source.properties` — new committed file (defaults to upstream Apache repo/trunk)
- No Gradle build file changes required
- No changes to Kotlin application code
