## Decisions

### JDK 21 package source

Use Ubuntu 24.04's built-in `openjdk-21-jdk` package. No PPA or custom repository is required — `openjdk-21-jdk` is available in the default Ubuntu Noble repositories. This is consistent with how JDK 8, 11, and 17 are installed in the base image.

### Alternative name pattern

The `update-java-alternatives` name for JDK 21 follows the same pattern as the existing versions:
- JDK 8 → `java-1.8.0-openjdk-$ARCH`
- JDK 11 → `java-1.11.0-openjdk-$ARCH`
- JDK 17 → `java-1.17.0-openjdk-$ARCH`
- JDK 21 → `java-1.21.0-openjdk-$ARCH`

### Scope of cassandra_versions.yaml change

Only `trunk` is updated to JDK 21. `5.0` and `5.0-HEAD` remain on JDK 11 to avoid unintended behaviour changes for stable releases.

### No changes to ant build defaults

The `install_cassandra.sh` script sets JDK 11 as the default before running ant builds. Since `trunk` uses a pre-built tarball (downloaded via URL), it never goes through the ant build path. The ant default is not changed.
