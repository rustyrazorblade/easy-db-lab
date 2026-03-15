## Why

Cassandra `trunk` is built and tested against JDK 21. The `use-cassandra` script exits with "Unknown java version 21" when switching to trunk, making the trunk version unusable on existing AMIs. JDK 21 is available in Ubuntu 24.04's standard package repositories with no additional PPAs required.

## What Changes

- Install `openjdk-21-jdk` and `openjdk-21-dbg` in the base packer image alongside the existing JDK 8, 11, and 17 packages.
- Add a `java: "21"` branch to the `use-cassandra` script that calls `update-java-alternatives -s java-1.21.0-openjdk-$ARCH`.
- Update `trunk` in `cassandra_versions.yaml` from `java: "17"` to `java: "21"`.

## Capabilities

### Modified Capabilities

- **packer-base-image**: Now includes JDK 21 alongside JDK 8, 11, and 17.
- **use-cassandra**: Supports switching to JDK 21 when a Cassandra version requires it.
- **cassandra-versions**: `trunk` now targets JDK 21.

## Impact

- **Base AMI rebuild required**: The base image must be rebuilt to include `openjdk-21-jdk`.
- **Cassandra AMI rebuild required**: The cassandra image must be rebuilt after the base image to pick up the new JDK and updated `cassandra_versions.yaml`.
- **No behaviour change for existing versions**: `5.0`, `5.0-HEAD`, and older versions continue using JDK 11.
